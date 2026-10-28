package ru.expeline.polytech.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ModelsTest {
    private val lesson = Lesson("1", "Математика", "Лекция", Instant.parse("2026-10-28T07:00:00Z"), Instant.parse("2026-10-28T08:30:00Z"), emptyList(), emptyList(), "")
    @Test fun parsesGroupUrl() { assertEquals(125 to 45602, StudyGroup.ids("https://ruz.spbstu.ru/faculty/125/groups/45602")) }
    @Test fun rejectsOtherHosts() { assertNull(StudyGroup.ids("https://example.com/faculty/125/groups/45602")) }
    @Test fun subjectRuleOverridesLectureFilter() {
        val prefs = StudyPreferences(skipLectures = true, subjectRules = mapOf(lesson.subjectRuleKey(1) to AttendanceRule.ATTEND))
        assertTrue(prefs.includes(lesson, 1))
    }
    @Test fun individualRuleHasHighestPriority() {
        val prefs = StudyPreferences(subjectRules = mapOf(lesson.subjectRuleKey(1) to AttendanceRule.SKIP), lessonRules = mapOf(lesson.ruleKey(1) to AttendanceRule.ATTEND))
        assertTrue(prefs.includes(lesson, 1))
    }
}

