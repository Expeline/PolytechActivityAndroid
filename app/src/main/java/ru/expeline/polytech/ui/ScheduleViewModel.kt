package ru.expeline.polytech.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.expeline.polytech.data.AssignmentDTO
import ru.expeline.polytech.data.AssignmentDraft
import ru.expeline.polytech.data.AttendanceRule
import ru.expeline.polytech.data.Lesson
import ru.expeline.polytech.data.PersistedState
import ru.expeline.polytech.data.ScheduleRepository
import ru.expeline.polytech.data.ServerStatus
import ru.expeline.polytech.data.StudyClock
import ru.expeline.polytech.data.StudyGroup
import ru.expeline.polytech.data.StudyPreferences
import ru.expeline.polytech.data.StudyRepository
import ru.expeline.polytech.data.StudySettings
import ru.expeline.polytech.data.StudyState
import ru.expeline.polytech.notification.LessonNotification
import ru.expeline.polytech.widget.ScheduleWidget
import java.time.Instant
import java.time.LocalDate

enum class Section(val title: String) { SCHEDULE("Расписание"), ASSIGNMENTS("Задания"), CHANGES("Изменения") }

data class UiState(
    val data: PersistedState = PersistedState(),
    val selectedDate: LocalDate = StudyClock.today(),
    val now: Instant = Instant.now(),
    val refreshing: Boolean = false,
    val message: String? = null,
    val study: StudyState = StudyState(),
    val server: ServerStatus = ServerStatus.Off,
    val studyError: String? = null,
    val section: Section = Section.SCHEDULE
) {
    val groupId get() = data.group.id
}

private data class Base(val data: PersistedState, val date: LocalDate, val now: Instant, val refreshing: Boolean, val message: String?)

class ScheduleViewModel(
    private val repository: ScheduleRepository,
    private val study: StudyRepository,
    application: Application
) : AndroidViewModel(application) {
    private val selectedDate = MutableStateFlow(StudyClock.today())
    private val now = MutableStateFlow(Instant.now())
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    private val section = MutableStateFlow(Section.SCHEDULE)

    private val base = combine(repository.state, selectedDate, now, refreshing, message) { data, date, time, loading, note -> Base(data, date, time, loading, note) }
    val state: StateFlow<UiState> = combine(base, study.state, study.status, study.lastError, section) { b, s, server, error, page ->
        UiState(b.data, b.date, b.now, b.refreshing, b.message, s, server, error, page)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(repository.state.value, study = study.state.value))

    init {
        viewModelScope.launch { while (isActive) { now.value = Instant.now(); delay(1_000) } }
        viewModelScope.launch { repository.state.collect { syncSurfaces(it); study.select(it.group.id) } }
        refresh()
    }

    fun selectDate(date: LocalDate) { selectedDate.value = date }
    fun shiftWeek(offset: Long) { selectedDate.value = selectedDate.value.plusWeeks(offset) }
    fun show(page: Section) {
        section.value = page
        if (page == Section.CHANGES) study.markChangesSeen(repository.state.value.group.id)
    }
    fun refresh() { viewModelScope.launch {
        if (refreshing.value) return@launch
        refreshing.value = true
        val result = repository.refresh(selectedDate.value)
        study.recordLocal(result.groupId, result.changes)
        message.value = when {
            result.errors.isEmpty() -> null
            repository.lessonsFor(selectedDate.value).isEmpty() -> result.errors.first()
            else -> "Не всё удалось обновить. Показываем сохранённые данные."
        }
        refreshing.value = false
        study.sync()
    }}
    fun onForeground() { viewModelScope.launch { study.sync() } }
    fun onBackground() = study.stopLive()

    suspend fun search(query: String): Result<List<StudyGroup>> = runCatching { repository.search(query) }
    fun choose(group: StudyGroup) { viewModelScope.launch { repository.choose(group); selectedDate.value = StudyClock.today(); refresh() } }
    fun finishDay(finish: Boolean) { viewModelScope.launch { repository.finishDay(finish) } }
    fun setRule(lesson: Lesson, rule: AttendanceRule, subject: Boolean) { viewModelScope.launch { repository.setRule(lesson, rule, subject) } }
    fun preferences(block: (StudyPreferences) -> StudyPreferences) { viewModelScope.launch { repository.preferences(block) } }
    fun dayLessons(ui: UiState) = repository.lessonsFor(ui.selectedDate, ui.data)
    fun activeLesson(ui: UiState) = repository.activeOrNext(ui.now, ui.data)
    fun upcoming(ui: UiState) = repository.allLessons(ui.data).filter { it.start > ui.now }
    fun subjects(ui: UiState) = repository.subjects(ui.data)

    // Assignments
    fun newDraft(ui: UiState, subject: String? = null, lesson: Lesson? = null): AssignmentDraft {
        val name = subject ?: lesson?.subject ?: subjects(ui).firstOrNull().orEmpty()
        val target = lesson?.takeIf { it.start > Instant.now() } ?: repository.nextLesson(name, value = ui.data)
        return AssignmentDraft(subject = name, lessonKind = target?.kind ?: lesson?.kind, title = "",
            dueAt = target?.start ?: StudyClock.today().plusDays(7).atStartOfDay(ru.expeline.polytech.data.MoscowZone).toInstant(),
            dueLessonStart = target?.start)
    }
    fun save(draft: AssignmentDraft, isNew: Boolean) {
        val group = repository.state.value.group.id
        if (isNew) study.create(draft, group) else study.edit(draft, group)
    }
    fun setDone(item: AssignmentDTO, done: Boolean) = study.setDone(item, done)
    fun delete(item: AssignmentDTO) = study.delete(item)
    fun report(item: AssignmentDTO) = study.report(item)
    fun studySettings(block: (StudySettings) -> StudySettings) {
        val before = study.state.value.settings
        study.settings(block)
        viewModelScope.launch {
            if (before.displayName != study.state.value.settings.displayName) study.renameOnServer()
            study.sync()
        }
    }

    private suspend fun syncSurfaces(data: PersistedState) {
        ScheduleWidget.updateAll(getApplication())
        LessonNotification.sync(getApplication(), repository.activeOrNext(value = data), data)
    }
}

class ScheduleViewModelFactory(
    private val repository: ScheduleRepository,
    private val study: StudyRepository,
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ScheduleViewModel(repository, study, application) as T
}
