package ru.expeline.polytech.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.expeline.polytech.data.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class EditorRequest(val draft: AssignmentDraft, val isNew: Boolean)

private val dueFormat = DateTimeFormatter.ofPattern("EE, d MMM, HH:mm", Russian).withZone(MoscowZone)

fun ScheduleChangeKind.color() = when (this) {
    ScheduleChangeKind.CANCELLED -> Color(0xFFD14343)
    ScheduleChangeKind.MOVED, ScheduleChangeKind.ADDED -> Color(0xFF3569C8)
    ScheduleChangeKind.ROOM_CHANGED, ScheduleChangeKind.TEACHER_CHANGED -> Color(0xFFC96827)
}
private fun ScheduleChangeKind.icon(): ImageVector = when (this) {
    ScheduleChangeKind.ADDED -> Icons.Rounded.AddCircleOutline
    ScheduleChangeKind.CANCELLED -> Icons.Rounded.HighlightOff
    ScheduleChangeKind.MOVED -> Icons.Rounded.ArrowCircleRight
    ScheduleChangeKind.ROOM_CHANGED -> Icons.Rounded.MeetingRoom
    ScheduleChangeKind.TEACHER_CHANGED -> Icons.Rounded.Person
}

@Composable fun ChangeTag(kind: ScheduleChangeKind) {
    Text(kind.tag, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .8.sp, color = kind.color(),
        modifier = Modifier.clip(CircleShape).background(kind.color().copy(alpha = .13f)).padding(horizontal = 7.dp, vertical = 3.dp))
}

@Composable internal fun ServerLine(ui: UiState) {
    val color = when (ui.server) { ServerStatus.Online -> Color(0xFF2E9E5B); ServerStatus.Connecting -> Color(0xFFD9A31A); ServerStatus.Off -> MaterialTheme.colorScheme.outline; is ServerStatus.Offline -> Color(0xFFC96827) }
    val pending = ui.study.outbox.size
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(if (pending > 0) "${ui.server.title} · ждут отправки: $pending" else ui.server.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// region Assignments

@Composable fun AssignmentsScreen(ui: UiState, model: ScheduleViewModel, settings: () -> Unit, edit: (EditorRequest) -> Unit) {
    var filter by remember { mutableStateOf<String?>(null) }
    var showDone by remember { mutableStateOf(false) }
    val all = ui.study.assignments(ui.groupId)
    val items = all.filter { filter == null || it.subject == filter }
    val open = items.filter { !it.completedByMe }
    val today = StudyClock.today()
    val weekEnd = ui.now.plus(Duration.ofDays(7))
    val buckets = listOf(
        "Просрочено" to open.filter { it.dueAt < ui.now },
        "Сегодня" to open.filter { it.dueAt >= ui.now && StudyClock.date(it.dueAt) == today },
        "Ближайшие 7 дней" to open.filter { it.dueAt >= ui.now && StudyClock.date(it.dueAt) != today && it.dueAt < weekEnd },
        "Позже" to open.filter { it.dueAt >= weekEnd }
    ).filter { it.second.isNotEmpty() }
    val done = items.filter { it.completedByMe }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { ScreenHeader(ui, "Задания", settings) }
        item { ServerLine(ui) }
        ui.studyError?.let { item { StatusCard(it) } }
        val subjects = all.map { it.subject }.distinct().sorted()
        if (subjects.size > 1) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { FilterChip(filter == null, { filter = null }, { Text("Все") }) }
                items(subjects) { subject -> FilterChip(filter == subject, { filter = subject }, { Text(subject, maxLines = 1) }) }
            }
        }
        if (buckets.isEmpty()) item {
            EmptyCard(if (items.isEmpty()) "Заданий пока нет" else "Всё сделано",
                if (model.subjects(ui).isEmpty()) "Загрузите расписание, чтобы привязывать задания к предметам."
                else "Опубликуйте задание — его увидят все, кто выбрал группу ${ui.data.group.name}.")
        }
        buckets.forEach { (title, list) ->
            item(key = "h-$title") { Row { Eyebrow(title.uppercase()); Spacer(Modifier.weight(1f)); Text("${list.size}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            items(list, key = { it.id }) { AssignmentCard(it, ui, model, edit) }
        }
        if (done.isNotEmpty()) {
            item(key = "done") {
                TextButton({ showDone = !showDone }) { Text("Выполнено · ${done.size}"); Icon(if (showDone) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null) }
            }
            if (showDone) items(done.reversed(), key = { "d-" + it.id }) { AssignmentCard(it, ui, model, edit) }
        }
    }
}

@Composable fun AssignmentCard(item: AssignmentDTO, ui: UiState, model: ScheduleViewModel, edit: (EditorRequest) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 6.dp, end = 4.dp, top = 10.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        IconButton({ model.setDone(item, !item.completedByMe); if (!item.completedByMe) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }) {
            Icon(if (item.completedByMe) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                if (item.completedByMe) "Отметить невыполненным" else "Отметить выполненным",
                tint = when { item.completedByMe -> MaterialTheme.colorScheme.primary; item.isOverdue -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant })
        }
        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(item.subject.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            Text(item.title, fontWeight = FontWeight.Medium, textDecoration = if (item.completedByMe) TextDecoration.LineThrough else null)
            if (item.details.isNotBlank()) Text(item.details, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            Text(dueFormat.format(item.dueAt) + if (item.dueLessonStart != null) " · на паре" else "", fontSize = 11.sp,
                fontWeight = if (item.isOverdue) FontWeight.SemiBold else FontWeight.Normal,
                color = if (item.isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(buildString {
                append(if (item.isMine) "Вы" else item.authorName)
                if (item.completedCount > 0) append(" · выполнили ${item.completedCount}")
                if (ui.study.pending(item.id)) append(" · ждёт отправки")
            }, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Действия") }
            DropdownMenu(menu, { menu = false }) {
                if (item.isMine) {
                    DropdownMenuItem({ Text("Изменить") }, { menu = false; edit(EditorRequest(item.draft, false)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("Удалить") }, { menu = false; confirm = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null) })
                } else if (!item.reportedByMe) {
                    DropdownMenuItem({ Text("Пожаловаться") }, { menu = false; model.report(item) }, leadingIcon = { Icon(Icons.Rounded.Flag, null) })
                } else DropdownMenuItem({ Text("Жалоба отправлена") }, {}, enabled = false)
            }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Удалить задание для всей группы?") },
        confirmButton = { TextButton({ confirm = false; model.delete(item) }) { Text("Удалить") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Отмена") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AssignmentEditor(request: EditorRequest, ui: UiState, model: ScheduleViewModel, close: () -> Unit) {
    var draft by remember { mutableStateOf(request.draft) }
    var atLesson by remember { mutableStateOf(request.draft.dueLessonStart != null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    val subjects = model.subjects(ui).let { if (draft.subject.isNotEmpty() && draft.subject !in it) listOf(draft.subject) + it else it }
    val lessons = model.upcoming(ui).filter { it.subject == draft.subject }.take(8)
    fun chooseLesson(lesson: Lesson) { draft = draft.copy(dueAt = lesson.start, dueLessonStart = lesson.start, lessonKind = lesson.kind) }

    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (request.isNew) "Новое задание" else "Задание", fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Eyebrow("ПРЕДМЕТ")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(subjects) { subject ->
                    FilterChip(draft.subject == subject, {
                        val next = model.upcoming(ui).firstOrNull { it.subject == subject }
                        draft = draft.copy(subject = subject, lessonKind = next?.kind)
                        if (atLesson) { if (next != null) chooseLesson(next) else { atLesson = false; draft = draft.copy(dueLessonStart = null) } }
                    }, { Text(subject, maxLines = 1) })
                }
            }
            OutlinedTextField(draft.title, { draft = draft.copy(title = it) }, Modifier.fillMaxWidth(), label = { Text("Что сделать") }, placeholder = { Text("Например, лабораторная № 3") }, singleLine = true)
            OutlinedTextField(draft.details, { draft = draft.copy(details = it) }, Modifier.fillMaxWidth(), label = { Text("Подробности") }, minLines = 3, maxLines = 8)
            Eyebrow("СРОК СДАЧИ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Сдать на паре", fontWeight = FontWeight.Medium)
                    if (lessons.isEmpty()) Text("Ближайших пар по предмету нет в загруженном расписании.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(atLesson, { value -> atLesson = value; if (value) lessons.firstOrNull()?.let(::chooseLesson) else draft = draft.copy(dueLessonStart = null) }, enabled = lessons.isNotEmpty())
            }
            if (atLesson) lessons.forEach { lesson ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { chooseLesson(lesson) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("${ruWeekdayDayMonth(StudyClock.date(lesson.start))} · ${lesson.timeRange}", fontSize = 13.sp); Text(lesson.kind, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    RadioButton(draft.dueLessonStart == lesson.start, { chooseLesson(lesson) })
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ pickDate = true }) { Icon(Icons.Rounded.Event, null); Spacer(Modifier.width(6.dp)); Text(DateTimeFormatter.ofPattern("d MMM", Russian).withZone(MoscowZone).format(draft.dueAt)) }
                OutlinedButton({ pickTime = true }) { Icon(Icons.Rounded.Schedule, null); Spacer(Modifier.width(6.dp)); Text(StudyClock.time(draft.dueAt)) }
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({
                val final = if (atLesson) draft else draft.copy(dueLessonStart = null)
                problem = final.problem
                if (problem == null) { model.save(final, request.isNew); close() }
            }, Modifier.fillMaxWidth()) { Text(if (request.isNew) "Опубликовать для группы" else "Сохранить") }
            Text("Задание увидят все, кто выбрал группу ${ui.data.group.name}. Отметка «выполнено» — личная, группа видит только их количество.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(StudyClock.date(draft.dueAt).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog({ pickDate = false }, confirmButton = { TextButton({
            state.selectedDateMillis?.let { millis ->
                val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                draft = draft.copy(dueAt = day.atTime(draft.dueAt.atZone(MoscowZone).toLocalTime()).atZone(MoscowZone).toInstant())
            }
            pickDate = false
        }) { Text("Готово") } }) { DatePicker(state) }
    }
    if (pickTime) {
        val time = draft.dueAt.atZone(MoscowZone).toLocalTime()
        val state = rememberTimePickerState(time.hour, time.minute, is24Hour = true)
        AlertDialog({ pickTime = false }, confirmButton = { TextButton({
            val day: LocalDate = StudyClock.date(draft.dueAt)
            draft = draft.copy(dueAt = day.atTime(LocalTime.of(state.hour, state.minute)).atZone(MoscowZone).toInstant())
            pickTime = false
        }) { Text("Готово") } }, text = { TimePicker(state) })
    }
}

// endregion
// region Changes

@Composable fun ChangesScreen(ui: UiState, settings: () -> Unit) {
    var filter by remember { mutableStateOf<ScheduleChangeKind?>(null) }
    var upcomingOnly by remember { mutableStateOf(true) }
    val items = ui.study.changes(ui.groupId).filter { (filter == null || it.kind == filter) && (!upcomingOnly || it.relevantStart > ui.now) }
    val days = items.groupBy { StudyClock.date(it.detectedAt) }.toSortedMap(compareByDescending { it })
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        item { ScreenHeader(ui, "Изменения", settings) }
        item { ServerLine(ui) }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                item { FilterChip(filter == null, { filter = null }, { Text("Все") }) }
                items(ScheduleChangeKind.entries) { kind -> FilterChip(filter == kind, { filter = kind }, { Text(kind.title) }) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Только предстоящие пары", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                Switch(upcomingOnly, { upcomingOnly = it })
            }
        }
        if (days.isEmpty()) item {
            EmptyCard("Изменений нет", "Политех сравнивает расписание при каждом обновлении, а сервер группы — каждые 15 минут. Переносы, отмены и новые пары появятся здесь.")
        }
        days.forEach { (day, list) ->
            item(key = "day-$day") {
                val label = when (day) { StudyClock.today() -> "сегодня"; StudyClock.today().minusDays(1) -> "вчера"; else -> ruDayMonth(day) }
                Eyebrow("ОБНАРУЖЕНО ${label.uppercase()}")
            }
            items(list, key = { it.fingerprint }) { ChangeCard(it) }
        }
    }
}

@Composable fun ChangeCard(change: ScheduleChange, showSubject: Boolean = true) {
    val format = DateTimeFormatter.ofPattern("EE, d MMM", Russian).withZone(MoscowZone)
    @Composable fun slot(label: String, slot: LessonSlot, strikeTime: Boolean, strikePlace: Boolean, dim: Boolean) {
        val color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        Column {
            Text(label.uppercase(), fontSize = 8.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.outline)
            Text("${format.format(slot.start)} · ${slot.timeRange}", fontSize = 12.sp, color = color, textDecoration = if (strikeTime) TextDecoration.LineThrough else null)
            Text(if (change.kind == ScheduleChangeKind.TEACHER_CHANGED) slot.teachers.joinToString() else slot.location, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textDecoration = if (strikePlace) TextDecoration.LineThrough else null)
        }
    }
    val inside = change.kind == ScheduleChangeKind.ROOM_CHANGED || change.kind == ScheduleChangeKind.TEACHER_CHANGED
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(change.kind.icon(), null, tint = change.kind.color())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                ChangeTag(change.kind)
                Text(change.lessonKind.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (showSubject) Text(change.subject, fontWeight = FontWeight.Medium)
            change.old?.let { slot("Было", it, strikeTime = change.kind != ScheduleChangeKind.ADDED && !inside, strikePlace = inside, dim = true) }
            change.new?.takeIf { change.kind != ScheduleChangeKind.CANCELLED }?.let { slot(if (change.old == null) "Когда" else "Стало", it, false, false, false) }
        }
    }
}

// endregion
