package ru.expeline.polytech.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ScheduleDiffTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val before = Instant.parse("2026-10-11T00:00:00Z")

    private fun lesson(id: String, subject: String, day: String, start: String, end: String, room: String = "101") = Lesson(
        id, subject, "Практика", StudyClock.instant(day, start), StudyClock.instant(day, end), listOf("Иванов И. И."),
        listOf(Room(room, Room.Building("Главный", "ГЗ"))), ""
    )
    private val week = listOf(
        lesson("a", "Физика", "2026-10-12", "10:00", "11:30"),
        lesson("b", "Математика", "2026-10-13", "12:00", "13:30"),
        lesson("c", "История", "2026-10-14", "14:00", "15:30")
    )

    /** Must equal ScheduleDiffTests.testFingerprintMatchesAndroid in the Apple project. */
    @Test fun fingerprintMatchesApple() {
        val change = ScheduleChange(ScheduleChangeKind.MOVED, "Физика", "Практика",
            LessonSlot(Instant.ofEpochSecond(1_793_900_000), Instant.ofEpochSecond(1_793_905_400), listOf("101 · ГЗ"), listOf("Иванов И. И.")),
            LessonSlot(Instant.ofEpochSecond(1_794_000_000), Instant.ofEpochSecond(1_794_005_400), listOf("202 · ГЗ"), listOf("Иванов И. И.")),
            Instant.now())
        assertEquals("b8c11e8fe1716c70", change.fingerprint)
    }

    @Test fun sameScheduleWithNewIdsHasNoChanges() {
        assertTrue(ScheduleDiff.changes(week, week.map { it.copy(id = it.id + "-new") }, before).isEmpty())
    }

    @Test fun moveCancelAddAndRoom() {
        val new = listOf(
            week[0].copy(id = "moved", start = week[0].start.plusSeconds(86_400), end = week[0].end.plusSeconds(86_400)),
            week[1].copy(rooms = listOf(Room("999"))),
            lesson("d", "Консультация", "2026-10-16", "10:00", "11:30")
        )
        val changes = ScheduleDiff.changes(week, new, before)
        assertEquals(setOf(ScheduleChangeKind.MOVED, ScheduleChangeKind.ROOM_CHANGED, ScheduleChangeKind.CANCELLED, ScheduleChangeKind.ADDED), changes.map { it.kind }.toSet())
        assertEquals("История", changes.first { it.kind == ScheduleChangeKind.CANCELLED }.subject)
        assertTrue(changes.first { it.kind == ScheduleChangeKind.ROOM_CHANGED }.describes(new[1]))
        // Past lessons are history, not changes.
        assertTrue(ScheduleDiff.changes(week, emptyList(), week.last().end).isEmpty())
    }

    @Test fun mergeKeepsEarliestDetection() {
        val early = ScheduleDiff.changes(week, week.drop(1), before, Instant.ofEpochSecond(1_000))
        val late = ScheduleDiff.changes(week, week.drop(1), before, Instant.ofEpochSecond(2_000))
        val merged = ScheduleDiff.merge(late, early, Instant.EPOCH)
        assertEquals(1, merged.size)
        assertEquals(Instant.ofEpochSecond(1_000), merged.single().detectedAt)
    }

    @Test fun decodesServerJson() {
        // Real shape of a PolyServer response, including fractional milliseconds and an uppercase UUID.
        val text = """{"items":[{"authorName":"Алиса","details":"","reportedByMe":false,"subject":"Физика","title":"Лаба",
            "id":"A2A12321-D58F-4D97-831E-720EF7192A14","createdAt":1793915135357.2131,"isMine":false,"completedByMe":true,
            "completedCount":3,"dueAt":1794174335328,"updatedAt":1793915135357.2131,"groupID":45602}],"serverTime":1793915135400.5}"""
        val page = json.decodeFromString<AssignmentsPage>(text)
        val item = page.items.single()
        assertEquals(Instant.ofEpochMilli(1_794_174_335_328), item.dueAt)
        assertNull(item.deletedAt)
        assertTrue(item.sameId("a2a12321-d58f-4d97-831e-720ef7192a14"))
        val change = json.decodeFromString<ScheduleChange>("""{"kind":"roomChanged","subject":"Физика","lessonKind":"Лекции","detectedAt":1793915135357.5,
            "old":{"start":1794000000000,"end":1794005400000,"rooms":["101"],"teachers":[]},"new":{"start":1794000000000,"end":1794005400000,"rooms":["202"],"teachers":[]}}""")
        assertEquals(ScheduleChangeKind.ROOM_CHANGED, change.kind)
        assertEquals("202", change.new?.location)
    }

    @Test fun draftValidation() {
        assertNull(AssignmentDraft(subject = "Физика", title = "Лаба", dueAt = Instant.now()).problem)
        assertTrue(AssignmentDraft(subject = "Физика", title = "  ", dueAt = Instant.now()).problem != null)
        assertTrue(AssignmentDraft(subject = "", title = "Лаба", dueAt = Instant.now()).problem != null)
    }
}
