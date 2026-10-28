package ru.expeline.polytech.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.expeline.polytech.data.AttendanceRule
import ru.expeline.polytech.data.Lesson
import ru.expeline.polytech.data.PersistedState
import ru.expeline.polytech.data.ScheduleRepository
import ru.expeline.polytech.data.StudyClock
import ru.expeline.polytech.data.StudyGroup
import ru.expeline.polytech.data.StudyPreferences
import ru.expeline.polytech.notification.LessonNotification
import ru.expeline.polytech.widget.ScheduleWidget
import java.time.Instant
import java.time.LocalDate

data class UiState(
    val data: PersistedState = PersistedState(),
    val selectedDate: LocalDate = StudyClock.today(),
    val now: Instant = Instant.now(),
    val refreshing: Boolean = false,
    val message: String? = null
)

class ScheduleViewModel(private val repository: ScheduleRepository, application: Application) : AndroidViewModel(application) {
    private val selectedDate = MutableStateFlow(StudyClock.today())
    private val now = MutableStateFlow(Instant.now())
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)
    val state: StateFlow<UiState> = combine(repository.state, selectedDate, now, refreshing, message) { data, date, time, loading, note ->
        UiState(data, date, time, loading, note)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState(repository.state.value))
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { while (isActive) { now.value = Instant.now(); delay(1_000) } }
        refresh()
        viewModelScope.launch { repository.state.collect { syncSurfaces(it) } }
    }

    fun selectDate(date: LocalDate) { selectedDate.value = date }
    fun shiftWeek(offset: Long) { selectedDate.value = selectedDate.value.plusWeeks(offset) }
    fun clearMessage() { message.value = null }
    fun refresh() { viewModelScope.launch {
        if (refreshing.value) return@launch
        refreshing.value = true
        val errors = repository.refresh(selectedDate.value)
        message.value = when { errors.isEmpty() -> null; repository.lessonsFor(selectedDate.value).isEmpty() -> errors.first(); else -> "Не всё удалось обновить. Показываем сохранённые данные." }
        refreshing.value = false
    }}
    suspend fun search(query: String): Result<List<StudyGroup>> = runCatching { repository.search(query) }
    fun choose(group: StudyGroup) { viewModelScope.launch { repository.choose(group); selectedDate.value = StudyClock.today(); refresh() } }
    fun finishDay(finish: Boolean) { viewModelScope.launch { repository.finishDay(finish) } }
    fun setRule(lesson: Lesson, rule: AttendanceRule, subject: Boolean) { viewModelScope.launch { repository.setRule(lesson, rule, subject) } }
    fun preferences(block: (StudyPreferences) -> StudyPreferences) { viewModelScope.launch { repository.preferences(block) } }
    fun dayLessons(ui: UiState) = repository.lessonsFor(ui.selectedDate, ui.data)
    fun activeLesson(ui: UiState) = repository.activeOrNext(ui.now, ui.data)
    private suspend fun syncSurfaces(data: PersistedState) {
        ScheduleWidget.updateAll(getApplication())
        LessonNotification.sync(getApplication(), repository.activeOrNext(value = data), data)
    }
}

class ScheduleViewModelFactory(private val repository: ScheduleRepository, private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = ScheduleViewModel(repository, application) as T
}

