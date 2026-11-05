package ru.expeline.polytech.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import ru.expeline.polytech.notification.StudyNotifications
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface ServerStatus {
    val title: String
    data object Off : ServerStatus { override val title = "Только на этом устройстве" }
    data object Connecting : ServerStatus { override val title = "Подключаемся…" }
    data object Online : ServerStatus { override val title = "Группа онлайн" }
    data class Offline(val reason: String) : ServerStatus { override val title = "Нет связи с сервером" }
}

class ServerException(message: String, val transient: Boolean = false, val unauthorized: Boolean = false) : Exception(message)

/** PolyStudy and PolyChanges: local first, synced with PolyServer when one is configured. */
class StudyRepository(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val file = File(context.filesDir, "study-state.json")
    private val sessionFile = File(context.noBackupFilesDir, "server-session.json") // never in backups
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<StudyState> = mutable.asStateFlow()
    private val statusFlow = MutableStateFlow<ServerStatus>(ServerStatus.Off)
    val status: StateFlow<ServerStatus> = statusFlow.asStateFlow()
    private val errorFlow = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = errorFlow.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val syncLock = Mutex()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).build()
    private var groupId: Int? = null
    private var socket: WebSocket? = null
    private var socketGroup: Int? = null
    private var liveJob: Job? = null

    companion object {
        const val HISTORY_DAYS = 120L
        /** The emulator reaches the computer it runs on at 10.0.2.2. */
        val defaultAddress: String get() =
            if (Build.FINGERPRINT.contains("generic") || Build.PRODUCT.contains("sdk")) "http://10.0.2.2:8080" else ""
    }

    private fun load(): StudyState {
        val saved = runCatching { json.decodeFromString<StudyState>(file.readText()) }.getOrNull()
        return saved ?: StudyState(settings = StudySettings(serverAddress = defaultAddress))
    }
    private fun update(block: (StudyState) -> StudyState) {
        val value = block(mutable.value)
        mutable.value = value
        scope.launch(Dispatchers.IO) {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(json.encodeToString(value)); temp.renameTo(file)
        }
    }

    // region PolyChanges

    /** Called after a schedule refresh with what changed compared with the cached copy. */
    fun recordLocal(groupId: Int, detected: List<ScheduleChange>) {
        if (detected.isEmpty()) return
        val known = state.value.changes(groupId).map { it.fingerprint }.toSet()
        val fresh = detected.filter { it.fingerprint !in known }
        merge(groupId, detected)
        if (state.value.settings.notifyChanges) StudyNotifications.announce(context, fresh)
    }
    private fun merge(groupId: Int, incoming: List<ScheduleChange>) = update {
        val cutoff = Instant.now().minusSeconds(HISTORY_DAYS * 86_400)
        it.copy(changes = it.changes + (groupId to ScheduleDiff.merge(it.changes(groupId), incoming, cutoff)))
    }
    fun markChangesSeen(groupId: Int) {
        if (state.value.unseenChanges(groupId) > 0) update { it.copy(changesSeenAt = it.changesSeenAt + (groupId to Instant.now())) }
    }

    // endregion
    // region PolyStudy, local first

    fun settings(block: (StudySettings) -> StudySettings) {
        val before = state.value.settings
        update { it.copy(settings = block(it.settings)) }
        val after = state.value.settings
        if (before.serverAddress != after.serverAddress) { stopLive(); statusFlow.value = if (after.serverAddress.isBlank()) ServerStatus.Off else ServerStatus.Connecting }
        if (before.notifyDeadlines != after.notifyDeadlines) rescheduleDeadlines()
    }

    fun create(draft: AssignmentDraft, groupId: Int) {
        val now = Instant.now()
        val name = state.value.settings.displayName.ifBlank { "Вы" }
        upsert(AssignmentDTO(draft.id, groupId, draft.subject, draft.lessonKind, draft.title.trim(), draft.details.trim(),
            draft.dueAt, draft.dueLessonStart, name, true, 0, false, false, now, now, null))
        enqueue(OutboxAction.Create(groupId, draft))
    }

    fun edit(draft: AssignmentDraft, groupId: Int) {
        val item = state.value.assignments[groupId]?.firstOrNull { it.sameId(draft.id) } ?: return
        upsert(item.copy(subject = draft.subject, lessonKind = draft.lessonKind, title = draft.title.trim(), details = draft.details.trim(),
            dueAt = draft.dueAt, dueLessonStart = draft.dueLessonStart))
        val queued = state.value.outbox.indexOfFirst { it is OutboxAction.Create && it.draft.id == draft.id }
        if (queued >= 0) update { s -> s.copy(outbox = s.outbox.toMutableList().also { it[queued] = OutboxAction.Create(groupId, draft) }) }
        else enqueue(OutboxAction.Update(draft))
    }

    fun setDone(item: AssignmentDTO, done: Boolean) {
        if (item.completedByMe == done) return
        upsert(item.copy(completedByMe = done, completedCount = (item.completedCount + if (done) 1 else -1).coerceAtLeast(0)))
        update { s -> s.copy(outbox = s.outbox.filterNot { it is OutboxAction.Complete && it.id == item.id }) }
        enqueue(OutboxAction.Complete(item.id, done))
    }

    fun delete(item: AssignmentDTO) {
        val neverSent = state.value.outbox.any { it is OutboxAction.Create && it.draft.id == item.id }
        update { s -> s.copy(assignments = s.assignments + (item.groupID to s.assignments[item.groupID].orEmpty().filterNot { it.sameId(item.id) }),
            outbox = s.outbox.filterNot { it.assignmentId == item.id }) }
        if (!neverSent) enqueue(OutboxAction.Delete(item.id)) else rescheduleDeadlines()
    }

    fun report(item: AssignmentDTO) {
        upsert(item.copy(reportedByMe = true))
        enqueue(OutboxAction.Report(item.id))
    }

    private fun upsert(item: AssignmentDTO) = update { s ->
        val list = s.assignments[item.groupID].orEmpty().filterNot { it.sameId(item.id) } + item
        s.copy(assignments = s.assignments + (item.groupID to list))
    }
    private fun enqueue(action: OutboxAction) {
        update { it.copy(outbox = it.outbox + action) }
        rescheduleDeadlines()
        scope.launch { sync() }
    }
    private fun rescheduleDeadlines() {
        val id = groupId ?: return
        StudyNotifications.scheduleDeadlines(context, state.value.assignments(id), state.value.settings.notifyDeadlines)
    }

    // endregion
    // region Server

    fun select(groupId: Int) {
        if (this.groupId == groupId) return
        this.groupId = groupId
        stopLive()
        rescheduleDeadlines()
        scope.launch { sync() }
    }

    private val serverKey get() = state.value.settings.serverAddress.trim().trimEnd('/').lowercase()
    private fun base(): HttpUrl? = state.value.settings.serverAddress.trim().trimEnd('/').toHttpUrlOrNull()

    private fun loadSession(): DeviceSession? = runCatching {
        json.decodeFromString<Map<String, DeviceSession>>(sessionFile.readText())[serverKey]
    }.getOrNull()
    private fun saveSession(session: DeviceSession?) {
        val all = runCatching { json.decodeFromString<Map<String, DeviceSession>>(sessionFile.readText()) }.getOrDefault(emptyMap()).toMutableMap()
        if (session == null) all.remove(serverKey) else all[serverKey] = session
        sessionFile.writeText(json.encodeToString(all))
    }

    /** Sends queued actions, then pulls new assignments and changes. Safe to call often. */
    suspend fun sync() {
        val groupId = groupId ?: return
        if (state.value.settings.serverAddress.isBlank()) { statusFlow.value = ServerStatus.Off; return }
        val base = base() ?: run { statusFlow.value = ServerStatus.Offline("Адрес сервера должен начинаться с http:// или https://"); return }
        if (syncLock.isLocked) return
        syncLock.withLock {
            if (statusFlow.value != ServerStatus.Online) statusFlow.value = ServerStatus.Connecting
            try {
                val token = withContext(Dispatchers.IO) { loadSession()?.token } ?: register(base)
                ensureSubscribed(base, token, groupId)
                flushOutbox(base, token)
                pull(base, token, groupId)
                statusFlow.value = ServerStatus.Online
                errorFlow.value = null
                startLive(base, token, groupId)
            } catch (error: ServerException) {
                if (error.unauthorized) {
                    // The server forgot this device (e.g. its database was reset): register again.
                    withContext(Dispatchers.IO) { saveSession(null) }
                    update { s -> s.copy(subscribed = s.subscribed.filterNot { it.startsWith("$serverKey#") }.toSet()) }
                    statusFlow.value = ServerStatus.Offline("Переподключаемся к серверу…")
                    scope.launch { delay(500); sync() }
                } else statusFlow.value = ServerStatus.Offline(error.message ?: "Ошибка сервера")
            }
        }
    }

    private suspend fun register(base: HttpUrl): String {
        val name = state.value.settings.displayName.trim().ifBlank { "Студент" }
        val session = call<DeviceSession>(base, null, "POST", "devices", json.encodeToString(DeviceRegistration(name)))
        withContext(Dispatchers.IO) { saveSession(session) }
        return session.token
    }

    suspend fun renameOnServer() {
        val base = base() ?: return
        val token = withContext(Dispatchers.IO) { loadSession()?.token } ?: return
        val name = state.value.settings.displayName.trim().ifBlank { return }
        runCatching { send(base, token, "PATCH", "devices/me", json.encodeToString(DeviceRegistration(name))) }
    }

    private suspend fun ensureSubscribed(base: HttpUrl, token: String, groupId: Int) {
        val key = "$serverKey#$groupId"
        if (key in state.value.subscribed) return
        call<GroupInfo>(base, token, "PUT", "groups/$groupId/subscription")
        update { it.copy(subscribed = it.subscribed + key) }
    }

    private suspend fun flushOutbox(base: HttpUrl, token: String) {
        while (true) {
            val action = state.value.outbox.firstOrNull() ?: return
            try {
                when (action) {
                    is OutboxAction.Create -> upsert(call(base, token, "POST", "groups/${action.groupId}/assignments", json.encodeToString(action.draft)))
                    is OutboxAction.Update -> upsert(call(base, token, "PATCH", "assignments/${action.draft.id}", json.encodeToString(action.draft)))
                    is OutboxAction.Delete -> send(base, token, "DELETE", "assignments/${action.id}")
                    is OutboxAction.Complete -> upsert(call(base, token, if (action.done) "PUT" else "DELETE", "assignments/${action.id}/completion"))
                    is OutboxAction.Report -> upsert(call(base, token, "POST", "assignments/${action.id}/report"))
                }
            } catch (error: ServerException) {
                if (error.transient || error.unauthorized) throw error
                // Rejected for good: drop the action and tell the user.
                errorFlow.value = error.message
                if (action is OutboxAction.Create) update { s ->
                    s.copy(assignments = s.assignments + (action.groupId to s.assignments[action.groupId].orEmpty().filterNot { it.sameId(action.draft.id) }))
                }
            }
            update { s -> s.copy(outbox = s.outbox.drop(1)) }
        }
    }

    private suspend fun pull(base: HttpUrl, token: String, groupId: Int) {
        fun since(value: Instant?) = value?.let { "?since=${it.toEpochMilli()}" }.orEmpty()
        val current = state.value
        val page = call<AssignmentsPage>(base, token, "GET", "groups/$groupId/assignments${since(current.assignmentsCursor[groupId])}")
        val list = (if (current.assignmentsCursor[groupId] == null) mutableListOf() else current.assignments[groupId].orEmpty().toMutableList())
        for (item in page.items) {
            list.removeAll { it.sameId(item.id) }
            if (item.deletedAt == null) list += item
        }
        // Keep local items still waiting to be sent.
        state.value.assignments[groupId].orEmpty().filter { local -> state.value.pending(local.id) && list.none { it.sameId(local.id) } }.forEach { list += it }
        val keepAfter = Instant.now().minusSeconds(60 * 86_400)
        update { it.copy(assignments = it.assignments + (groupId to list.filter { a -> a.dueAt > keepAfter }),
            assignmentsCursor = it.assignmentsCursor + (groupId to page.serverTime)) }

        val firstPull = state.value.changesCursor[groupId] == null
        val changes = call<ChangesPage>(base, token, "GET", "groups/$groupId/changes${since(state.value.changesCursor[groupId])}")
        val known = state.value.changes(groupId).map { it.fingerprint }.toSet()
        val fresh = changes.items.filter { it.fingerprint !in known }
        merge(groupId, changes.items)
        update { it.copy(changesCursor = it.changesCursor + (groupId to changes.serverTime)) }
        // The first download is history, not news.
        if (!firstPull && fresh.isNotEmpty() && state.value.settings.notifyChanges) StudyNotifications.announce(context, fresh)
        rescheduleDeadlines()
    }

    private suspend inline fun <reified T> call(base: HttpUrl, token: String?, method: String, path: String, body: String? = null): T {
        val text = send(base, token, method, path, body)
        return runCatching { json.decodeFromString<T>(text) }.getOrElse { throw ServerException("Сервер ответил в неизвестном формате. Обновите приложение и сервер.") }
    }

    private suspend fun send(base: HttpUrl, token: String?, method: String, path: String, body: String? = null): String {
        val url = base.newBuilder().addPathSegments("v1/" + path.substringBefore('?')).encodedQuery(path.substringAfter('?', "").ifEmpty { null }).build()
        val requestBody = body?.toRequestBody("application/json".toMediaType()) ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null
        val request = Request.Builder().url(url).method(method, requestBody).apply { token?.let { header("Authorization", "Bearer $it") } }.build()
        val response = try { http.newCall(request).await() } catch (error: IOException) {
            throw ServerException("Сервер группы недоступен. Изменения сохранятся и отправятся позже.", transient = true)
        }
        response.use {
            val text = it.body.string()
            when {
                it.isSuccessful -> return text
                it.code == 401 -> throw ServerException("Сервер не узнал это устройство.", unauthorized = true)
                it.code >= 500 && it.code != 502 -> throw ServerException("Сервер группы временно не отвечает.", transient = true)
                else -> throw ServerException(runCatching { json.decodeFromString<ServerProblem>(text).reason }.getOrDefault("Сервер отклонил запрос (${it.code})."))
            }
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) = continuation.resume(response)
            override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
        })
        continuation.invokeOnCancellation { cancel() }
    }

    // endregion
    // region Live updates while the app is open

    private fun startLive(base: HttpUrl, token: String, groupId: Int) {
        if (socket != null && socketGroup == groupId) return
        stopLive()
        socketGroup = groupId
        val url = base.newBuilder().addPathSegments("v1/groups/$groupId/live").build().toString()
            .replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val request = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = runCatching { json.decodeFromString<LiveEvent>(text) }.getOrNull()
                if (event?.groupID == groupId) scope.launch { sync() }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = reconnectLater(webSocket)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = reconnectLater(webSocket)
        })
    }
    private fun reconnectLater(closed: WebSocket) {
        scope.launch {
            if (socket !== closed) return@launch // closed on purpose or already replaced
            socket = null; socketGroup = null
            liveJob?.cancel()
            liveJob = scope.launch { delay(10_000); sync() }
        }
    }

    /** Closes the live channel; call when the app goes to the background. */
    fun stopLive() {
        liveJob?.cancel(); liveJob = null
        socket?.close(1000, null); socket = null; socketGroup = null
    }

    // endregion
}
