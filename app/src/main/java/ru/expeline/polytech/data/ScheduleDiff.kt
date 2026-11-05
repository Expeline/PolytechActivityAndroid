package ru.expeline.polytech.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/** Dates in the server API are milliseconds since 1970; the server may send fractions. */
object EpochMillisSerializer : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("EpochMillis", PrimitiveKind.DOUBLE)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeDouble().roundToLong())
}

@Serializable enum class ScheduleChangeKind(val title: String, val tag: String) {
    @SerialName("added") ADDED("Добавлено", "НОВАЯ"),
    @SerialName("cancelled") CANCELLED("Отменено", "ОТМЕНЕНО"),
    @SerialName("moved") MOVED("Перенесено", "ПЕРЕНЕСЕНО"),
    @SerialName("roomChanged") ROOM_CHANGED("Другая аудитория", "АУДИТОРИЯ"),
    @SerialName("teacherChanged") TEACHER_CHANGED("Другой преподаватель", "ПРЕПОДАВАТЕЛЬ");

    /** Same strings as the Apple app and the server, used in fingerprints. */
    val raw: String get() = when (this) {
        ADDED -> "added"; CANCELLED -> "cancelled"; MOVED -> "moved"
        ROOM_CHANGED -> "roomChanged"; TEACHER_CHANGED -> "teacherChanged"
    }
}

@Serializable data class LessonSlot(
    @Serializable(with = EpochMillisSerializer::class) val start: Instant,
    @Serializable(with = EpochMillisSerializer::class) val end: Instant,
    val rooms: List<String>,
    val teachers: List<String>
) {
    constructor(lesson: Lesson) : this(lesson.start, lesson.end, lesson.rooms.map { it.label }, lesson.teachers)
    val timeRange get() = "${StudyClock.time(start)}–${StudyClock.time(end)}"
    val location get() = if (rooms.isEmpty()) "Аудитория не указана" else rooms.joinToString(", ")
}

@Serializable data class ScheduleChange(
    val kind: ScheduleChangeKind,
    val subject: String,
    val lessonKind: String,
    val old: LessonSlot? = null,
    val new: LessonSlot? = null,
    @Serializable(with = EpochMillisSerializer::class) val detectedAt: Instant
) {
    /** Identical on Android, iOS/macOS and the server for the same change, so histories merge cleanly. */
    val fingerprint: String get() {
        fun slot(value: LessonSlot?) = value?.let {
            "${it.start.epochSecond},${it.end.epochSecond},${it.rooms.sorted().joinToString("+")},${it.teachers.sorted().joinToString("+")}"
        } ?: "-"
        val text = listOf(kind.raw, subject, lessonKind, slot(old), slot(new)).joinToString("|")
        var hash = 0xcbf29ce484222325uL
        for (byte in text.toByteArray(Charsets.UTF_8)) hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3uL
        return hash.toString(16)
    }
    val relevantStart: Instant get() = new?.start ?: old?.start ?: detectedAt
    fun describes(lesson: Lesson) = new != null && kind != ScheduleChangeKind.CANCELLED &&
        new.start == lesson.start && subject == lesson.subject && lessonKind == lesson.kind
}

/** Finds what changed between two downloads of the same weeks. Mirrors Shared/ScheduleDiff.swift. */
object ScheduleDiff {
    fun changes(old: List<Lesson>, new: List<Lesson>, after: Instant, detectedAt: Instant = Instant.now()): List<ScheduleChange> {
        val removed = old.filter { it.start > after }.toMutableList()
        val added = new.filter { it.start > after }.toMutableList()
        val result = mutableListOf<ScheduleChange>()
        fun same(a: Lesson, b: Lesson) = a.subject == b.subject && a.kind == b.kind
        fun change(kind: ScheduleChangeKind, before: Lesson?, after: Lesson?): ScheduleChange {
            val lesson = after ?: before!!
            return ScheduleChange(kind, lesson.subject, lesson.kind, before?.let(::LessonSlot), after?.let(::LessonSlot), detectedAt)
        }

        // 1. Identical lessons cancel out (ids may differ: they contain the position in the day).
        for (lesson in added.toList()) {
            val index = removed.indexOfFirst { same(it, lesson) && LessonSlot(it) == LessonSlot(lesson) }
            if (index >= 0) { removed.removeAt(index); added.remove(lesson) }
        }
        // 2. Same time, something inside changed.
        for (lesson in added.toList()) {
            val index = removed.indexOfFirst { same(it, lesson) && it.start == lesson.start && it.end == lesson.end }
            if (index < 0) continue
            val before = removed.removeAt(index)
            added.remove(lesson)
            val rooms = before.rooms.map { it.label }.toSet() != lesson.rooms.map { it.label }.toSet()
            val teachers = before.teachers.toSet() != lesson.teachers.toSet()
            if (rooms || teachers) result += change(if (rooms) ScheduleChangeKind.ROOM_CHANGED else ScheduleChangeKind.TEACHER_CHANGED, before, lesson)
        }
        // 3. Disappeared here, appeared nearby: a move. Closest pairs first.
        val window = Duration.ofDays(14).seconds
        val candidates = removed.flatMap { before ->
            added.filter { same(before, it) }.map { Triple(before, it, abs(it.start.epochSecond - before.start.epochSecond)) }
        }.filter { it.third <= window }.sortedWith(compareBy({ it.third }, { it.first.start }))
        for ((before, after, _) in candidates) {
            if (before in removed && after in added) {
                removed.remove(before); added.remove(after)
                result += change(ScheduleChangeKind.MOVED, before, after)
            }
        }
        // 4. The rest.
        result += removed.map { change(ScheduleChangeKind.CANCELLED, it, null) }
        result += added.map { change(ScheduleChangeKind.ADDED, null, it) }
        return result.sortedWith(compareBy({ it.relevantStart }, { it.fingerprint }))
    }

    /** Keeps the earliest detection of each change and drops records older than [keepAfter]. */
    fun merge(history: List<ScheduleChange>, incoming: List<ScheduleChange>, keepAfter: Instant): List<ScheduleChange> {
        val byId = linkedMapOf<String, ScheduleChange>()
        for (change in history + incoming) {
            val existing = byId[change.fingerprint]
            if (existing == null || change.detectedAt < existing.detectedAt) byId[change.fingerprint] = change
        }
        return byId.values.filter { it.detectedAt >= keepAfter }
            .sortedWith(compareByDescending<ScheduleChange> { it.detectedAt }.thenBy { it.relevantStart })
    }
}
