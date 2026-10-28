package ru.expeline.polytech.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

class ScheduleRepository(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "schedule-state.json")
    private val mutable = MutableStateFlow(load())
    val state: StateFlow<PersistedState> = mutable.asStateFlow()

    private fun load() = runCatching { json.decodeFromString<PersistedState>(file.readText()) }.getOrDefault(PersistedState())
    private suspend fun save(value: PersistedState) = withContext(Dispatchers.IO) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(value)); temp.renameTo(file)
    }
    private suspend fun update(block: (PersistedState) -> PersistedState) {
        val value = block(mutable.value); mutable.value = value; save(value)
    }

    fun weekKey(date: LocalDate, groupId: Int = state.value.group.id) = "$groupId-${StudyClock.monday(date)}"
    fun allLessons(value: PersistedState = state.value) = value.weeks.filterKeys { it.startsWith("${value.group.id}-") }.values.flatMap { it.response.lessons() }.sortedBy { it.start }
    fun lessonsFor(date: LocalDate, value: PersistedState = state.value) = value.weeks[weekKey(date, value.group.id)]?.response?.lessons()?.filter { StudyClock.date(it.start) == date }.orEmpty()
    fun activeOrNext(now: Instant = StudyClock.now(), value: PersistedState = state.value): Lesson? {
        val eligible = value.preferences.eligible(allLessons(value), value.group.id, now)
        return eligible.filter { it.start <= now && now < it.end }.maxByOrNull { it.start }
            ?: eligible.filter { it.start > now }.minByOrNull { it.start }
    }

    suspend fun refresh(selectedDate: LocalDate): List<String> = coroutineScope {
        val snapshot = state.value
        val dates = ((0 until snapshot.preferences.weeksAhead).map { StudyClock.monday(StudyClock.today().plusWeeks(it.toLong())) } + StudyClock.monday(selectedDate)).distinct()
        val results = dates.map { date -> async { date to runCatching { fetchWeek(snapshot.group.id, date) } } }.awaitAll()
        val errors = results.mapNotNull { it.second.exceptionOrNull()?.localizedMessage }
        val successes = results.mapNotNull { (date, result) -> result.getOrNull()?.let { date to it } }
        if (successes.isNotEmpty()) update { old ->
            val weeks = old.weeks.toMutableMap()
            successes.forEach { (date, response) -> weeks[weekKey(date, snapshot.group.id)] = CachedWeek(response, Instant.now()) }
            old.copy(group = successes.firstOrNull { it.first == StudyClock.monday(selectedDate) }?.second?.group ?: old.group, weeks = weeks)
        }
        errors
    }

    suspend fun search(query: String): List<StudyGroup> {
        StudyGroup.ids(query)?.let { (faculty, group) ->
            val response = fetchWeek(group, StudyClock.today())
            return if (response.group.id == group && response.group.faculty.id == faculty) listOf(response.group) else emptyList()
        }
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name())
        @kotlinx.serialization.Serializable data class SearchResponse(val groups: List<StudyGroup>)
        return request<SearchResponse>("search/groups?q=$encoded").groups
    }

    suspend fun choose(group: StudyGroup) = update { it.copy(group = group) }
    suspend fun preferences(block: (StudyPreferences) -> StudyPreferences) = update { it.copy(preferences = block(it.preferences)) }
    suspend fun finishDay(finish: Boolean) = preferences { prefs ->
        val key = StudyClock.dayKey(StudyClock.today(), state.value.group.id)
        prefs.copy(finishedDays = if (finish) prefs.finishedDays + key else prefs.finishedDays - key)
    }
    suspend fun setRule(lesson: Lesson, rule: AttendanceRule, subject: Boolean) = preferences { prefs ->
        if (subject) prefs.copy(subjectRules = prefs.subjectRules + (lesson.subjectRuleKey(state.value.group.id) to rule))
        else prefs.copy(lessonRules = prefs.lessonRules + (lesson.ruleKey(state.value.group.id) to rule))
    }

    private suspend fun fetchWeek(groupId: Int, date: LocalDate): ScheduleResponse = request("scheduler/$groupId?date=$date")
    private suspend inline fun <reified T> request(path: String): T = withContext(Dispatchers.IO) {
        val connection = URI("https://ruz.spbstu.ru/api/v1/ruz/$path").toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000; connection.readTimeout = 25_000; connection.setRequestProperty("Accept", "application/json")
        try {
            if (connection.responseCode !in 200..299) error("Сервис расписания временно недоступен (${connection.responseCode}).")
            json.decodeFromString<T>(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}

