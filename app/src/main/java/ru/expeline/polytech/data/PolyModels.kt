package ru.expeline.polytech.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/* JSON contract with PolyServer. Mirrors Shared/PolyModels.swift. */

@Serializable data class DeviceRegistration(val displayName: String)
@Serializable data class DeviceSession(val deviceID: String, val token: String, val displayName: String)
@Serializable data class GroupInfo(val group: StudyGroup, val subscribers: Int)
@Serializable data class ServerProblem(val error: Boolean = true, val reason: String)

@Serializable data class AssignmentDraft(
    /** Created on the device, so a create sent again after a network error does not duplicate. */
    val id: String = UUID.randomUUID().toString().uppercase(),
    val subject: String,
    val lessonKind: String? = null,
    val title: String,
    val details: String = "",
    @Serializable(with = EpochMillisSerializer::class) val dueAt: Instant,
    @Serializable(with = EpochMillisSerializer::class) val dueLessonStart: Instant? = null
) {
    /** A user-facing problem, or null when the draft can be published. */
    val problem: String? get() = when {
        title.isBlank() -> "Введите название задания."
        title.trim().length > 140 -> "Название длиннее 140 символов."
        details.length > 2000 -> "Описание длиннее 2000 символов."
        subject.isBlank() || subject.length > 200 -> "Выберите предмет."
        else -> null
    }
}

@Serializable data class AssignmentDTO(
    val id: String,
    val groupID: Int,
    val subject: String,
    val lessonKind: String? = null,
    val title: String,
    val details: String = "",
    @Serializable(with = EpochMillisSerializer::class) val dueAt: Instant,
    @Serializable(with = EpochMillisSerializer::class) val dueLessonStart: Instant? = null,
    val authorName: String,
    val isMine: Boolean,
    val completedCount: Int,
    val completedByMe: Boolean,
    val reportedByMe: Boolean = false,
    @Serializable(with = EpochMillisSerializer::class) val createdAt: Instant,
    @Serializable(with = EpochMillisSerializer::class) val updatedAt: Instant,
    @Serializable(with = EpochMillisSerializer::class) val deletedAt: Instant? = null
) {
    val draft get() = AssignmentDraft(id, subject, lessonKind, title, details, dueAt, dueLessonStart)
    val isOverdue get() = !completedByMe && dueAt < Instant.now()
    fun sameId(other: String) = id.equals(other, ignoreCase = true)

    /** Due at this lesson: linked to it, or due the same day for the same subject. */
    fun isDueAt(lesson: Lesson): Boolean {
        if (subject != lesson.subject) return false
        dueLessonStart?.let { return it == lesson.start }
        return StudyClock.date(dueAt) == StudyClock.date(lesson.start) && dueAt >= lesson.start.minusSeconds(3600)
    }
}

@Serializable data class AssignmentsPage(val items: List<AssignmentDTO>, @Serializable(with = EpochMillisSerializer::class) val serverTime: Instant)
@Serializable data class ChangesPage(val items: List<ScheduleChange>, @Serializable(with = EpochMillisSerializer::class) val serverTime: Instant)
@Serializable data class LiveEvent(val kind: String, val groupID: Int)

/** An action taken offline, sent to the server in order once it is reachable. */
@Serializable sealed interface OutboxAction {
    val assignmentId: String
    @Serializable data class Create(val groupId: Int, val draft: AssignmentDraft) : OutboxAction { override val assignmentId get() = draft.id }
    @Serializable data class Update(val draft: AssignmentDraft) : OutboxAction { override val assignmentId get() = draft.id }
    @Serializable data class Delete(val id: String) : OutboxAction { override val assignmentId get() = id }
    @Serializable data class Complete(val id: String, val done: Boolean) : OutboxAction { override val assignmentId get() = id }
    @Serializable data class Report(val id: String) : OutboxAction { override val assignmentId get() = id }
}

@Serializable data class StudySettings(
    val serverAddress: String = "",
    val displayName: String = "",
    val notifyChanges: Boolean = true,
    val notifyDeadlines: Boolean = true
)

/** Everything PolyStudy and PolyChanges keep on the device. */
@Serializable data class StudyState(
    val settings: StudySettings = StudySettings(),
    val changes: Map<Int, List<ScheduleChange>> = emptyMap(),
    @Serializable(with = EpochMillisMapSerializer::class) val changesSeenAt: Map<Int, Instant> = emptyMap(),
    val assignments: Map<Int, List<AssignmentDTO>> = emptyMap(),
    @Serializable(with = EpochMillisMapSerializer::class) val assignmentsCursor: Map<Int, Instant> = emptyMap(),
    @Serializable(with = EpochMillisMapSerializer::class) val changesCursor: Map<Int, Instant> = emptyMap(),
    val subscribed: Set<String> = emptySet(),
    val outbox: List<OutboxAction> = emptyList()
) {
    fun changes(groupId: Int) = changes[groupId].orEmpty()
    fun assignments(groupId: Int) = assignments[groupId].orEmpty().filter { it.deletedAt == null }.sortedBy { it.dueAt }
    fun unseenChanges(groupId: Int): Int {
        val seen = changesSeenAt[groupId] ?: Instant.EPOCH
        return changes(groupId).count { it.detectedAt > seen }
    }
    fun pending(id: String) = outbox.any { it.assignmentId.equals(id, ignoreCase = true) }
    fun change(lesson: Lesson, groupId: Int, days: Long = 7): ScheduleChange? {
        val cutoff = Instant.now().minusSeconds(days * 86_400)
        return changes(groupId).firstOrNull { it.detectedAt > cutoff && it.describes(lesson) }
    }
    /** Lessons that used to be on [day] but were cancelled or moved away. */
    fun vanished(day: LocalDate, current: List<Lesson>, groupId: Int): List<ScheduleChange> =
        changes(groupId).filter { change ->
            val old = change.old
            (change.kind == ScheduleChangeKind.CANCELLED || change.kind == ScheduleChangeKind.MOVED) && old != null &&
                StudyClock.date(old.start) == day &&
                current.none { it.subject == change.subject && it.kind == change.lessonKind && it.start == old.start }
        }.distinctBy { it.subject to it.old?.start }.sortedBy { it.old?.start }
    fun openAssignments(lesson: Lesson, groupId: Int) = assignments(groupId).filter { !it.completedByMe && it.isDueAt(lesson) }
}

object EpochMillisMapSerializer : KSerializer<Map<Int, Instant>> {
    private val delegate = MapSerializer(Int.serializer(), EpochMillisSerializer)
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: Map<Int, Instant>) = delegate.serialize(encoder, value)
    override fun deserialize(decoder: Decoder): Map<Int, Instant> = delegate.deserialize(decoder)
}
