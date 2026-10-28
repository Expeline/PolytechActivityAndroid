package ru.expeline.polytech.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

val MoscowZone: ZoneId = ZoneId.of("Europe/Moscow")

object StudyClock {
    fun today(): LocalDate = LocalDate.now(MoscowZone)
    fun now(): Instant = Instant.now()
    fun monday(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    fun instant(day: String, time: String): Instant = LocalDateTime.of(LocalDate.parse(day), LocalTime.parse(time)).atZone(MoscowZone).toInstant()
    fun dayKey(date: LocalDate, groupId: Int) = "$groupId:$date"
    fun date(instant: Instant): LocalDate = instant.atZone(MoscowZone).toLocalDate()
    fun time(instant: Instant): String = DateTimeFormatter.ofPattern("HH:mm").withZone(MoscowZone).format(instant)
}

@Serializable data class Faculty(val id: Int, val name: String, val abbr: String? = null)

@Serializable data class StudyGroup(val id: Int, val name: String, val faculty: Faculty) {
    companion object {
        val Default = StudyGroup(45602, "5130904/40102", Faculty(125, "Институт компьютерных наук и кибербезопасности", "ИКНК"))
        private val url = Regex("https://ruz\\.spbstu\\.ru/faculty/(\\d+)/groups/(\\d+)/?")
        fun ids(text: String): Pair<Int, Int>? = url.matchEntire(text.trim())?.destructured?.let { (faculty, group) -> faculty.toInt() to group.toInt() }
    }
    val sourceUrl get() = "https://ruz.spbstu.ru/faculty/${faculty.id}/groups/$id"
}

@Serializable data class ScheduleResponse(val week: Week, val days: List<ScheduleDay>, val group: StudyGroup) {
    @Serializable data class Week(val date_start: String, val date_end: String, val is_odd: Boolean)
    fun lessons(): List<Lesson> = days.flatMap { day -> day.lessons.mapIndexedNotNull { index, entry ->
        runCatching {
            Lesson(
                id = "${group.id}-${day.date}-$index-${entry.time_start}",
                subject = entry.subject,
                kind = entry.typeObj?.name ?: "Занятие",
                start = StudyClock.instant(day.date, entry.time_start),
                end = StudyClock.instant(day.date, entry.time_end),
                teachers = entry.teachers.orEmpty().map { it.full_name },
                rooms = entry.auditories.orEmpty(),
                additionalInfo = entry.additional_info.orEmpty(),
                webinarUrl = entry.webinar_url,
                lmsUrl = entry.lms_url
            )
        }.getOrNull()
    }}.sortedWith(compareBy<Lesson> { it.start }.thenBy { it.id })
}

@Serializable data class ScheduleDay(val date: String, val lessons: List<ScheduleEntry>)
@Serializable data class ScheduleEntry(
    val subject: String,
    val time_start: String,
    val time_end: String,
    @SerialName("typeObj") val typeObj: LessonKind? = null,
    val teachers: List<Teacher>? = null,
    val auditories: List<Room>? = null,
    val additional_info: String? = null,
    val webinar_url: String? = null,
    val lms_url: String? = null
) {
    @Serializable data class LessonKind(val name: String)
    @Serializable data class Teacher(val full_name: String)
}

@Serializable data class Room(val name: String, val building: Building? = null) {
    @Serializable data class Building(val name: String, val abbr: String? = null, val address: String? = null)
    val label: String get() = if (name.contains("дистанц", true)) "Дистанционно" else listOfNotNull(name, building?.abbr ?: building?.name).filter { it.isNotBlank() }.joinToString(" · ")
}

@Serializable data class Lesson(
    val id: String,
    val subject: String,
    val kind: String,
    @Serializable(with = InstantSerializer::class) val start: Instant,
    @Serializable(with = InstantSerializer::class) val end: Instant,
    val teachers: List<String>,
    val rooms: List<Room>,
    val additionalInfo: String,
    val webinarUrl: String? = null,
    val lmsUrl: String? = null
) {
    val isMilitary get() = subject.lowercase().let { "военн" in it || "воинск" in it }
    val isLecture get() = kind.contains("лек", true)
    val location get() = rooms.takeIf { it.isNotEmpty() }?.joinToString { it.label } ?: "Аудитория не указана"
    val timeRange get() = "${StudyClock.time(start)}–${StudyClock.time(end)}"
    fun ruleKey(groupId: Int) = "$groupId|$subject|$kind|${start.epochSecond}"
    fun subjectRuleKey(groupId: Int) = "$groupId|$subject|$kind"
}

object InstantSerializer : kotlinx.serialization.KSerializer<Instant> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("Instant", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: Instant) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): Instant = Instant.parse(decoder.decodeString())
}

@Serializable enum class AttendanceRule { AUTOMATIC, SKIP, ATTEND }
@Serializable enum class AppTheme { SYSTEM, LIGHT, DARK }
@Serializable enum class StudyAccent { GREEN, BLUE, PURPLE, PINK, RED, ORANGE, YELLOW, TEAL, GRAPHITE }

@Serializable data class StudyPreferences(
    val skipMilitary: Boolean = true,
    val skipLectures: Boolean = false,
    val subjectRules: Map<String, AttendanceRule> = emptyMap(),
    val lessonRules: Map<String, AttendanceRule> = emptyMap(),
    val finishedDays: Set<String> = emptySet(),
    val scale: Float = 1f,
    val compact: Boolean = false,
    val showTeachers: Boolean = true,
    val showPast: Boolean = true,
    val weeksAhead: Int = 6,
    val liveEnabled: Boolean = true,
    val accent: StudyAccent = StudyAccent.GREEN,
    val theme: AppTheme = AppTheme.SYSTEM
) {
    fun includes(lesson: Lesson, groupId: Int): Boolean {
        lessonRules[lesson.ruleKey(groupId)]?.takeIf { it != AttendanceRule.AUTOMATIC }?.let { return it == AttendanceRule.ATTEND }
        subjectRules[lesson.subjectRuleKey(groupId)]?.takeIf { it != AttendanceRule.AUTOMATIC }?.let { return it == AttendanceRule.ATTEND }
        return !(skipMilitary && lesson.isMilitary) && !(skipLectures && lesson.isLecture)
    }
    fun isFinished(date: LocalDate, groupId: Int) = StudyClock.dayKey(date, groupId) in finishedDays
    fun eligible(lessons: List<Lesson>, groupId: Int, now: Instant = StudyClock.now()) = lessons.filter {
        it.end > now && includes(it, groupId) && !isFinished(StudyClock.date(it.start), groupId)
    }
}

@Serializable data class CachedWeek(val response: ScheduleResponse, @Serializable(with = InstantSerializer::class) val fetchedAt: Instant)
@Serializable data class PersistedState(val group: StudyGroup = StudyGroup.Default, val preferences: StudyPreferences = StudyPreferences(), val weeks: Map<String, CachedWeek> = emptyMap())

