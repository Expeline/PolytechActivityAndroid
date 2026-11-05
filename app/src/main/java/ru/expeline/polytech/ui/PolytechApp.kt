package ru.expeline.polytech.ui

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.expeline.polytech.data.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.absoluteValue

private enum class Page { MAIN, SETTINGS, GROUPS }
internal val Russian: Locale = Locale.forLanguageTag("ru")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PolytechApp(model: ScheduleViewModel) {
    val ui by model.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(Page.MAIN) }
    var selectedLesson by remember { mutableStateOf<Lesson?>(null) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { model.onForeground() }
    LocalNetworkPermission(ui.study.settings.serverAddress) { model.onForeground() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { model.onBackground() }
    PolytechTheme(ui.data.preferences.theme, ui.data.preferences.accent) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                Page.MAIN -> Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = { SectionBar(ui, model) },
                    floatingActionButton = {
                        if (ui.section == Section.ASSIGNMENTS && model.subjects(ui).isNotEmpty())
                            ExtendedFloatingActionButton(onClick = { editor = EditorRequest(model.newDraft(ui), true) }, icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Новое задание") })
                    }
                ) { padding ->
                    PullToRefreshBox(isRefreshing = ui.refreshing, onRefresh = { model.refresh() }, modifier = Modifier.padding(padding)) {
                        AnimatedContent(ui.section, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "section") { section ->
                            when (section) {
                                Section.SCHEDULE -> ScheduleScreen(ui, model, { page = Page.SETTINGS }, { selectedLesson = it })
                                Section.ASSIGNMENTS -> AssignmentsScreen(ui, model, { page = Page.SETTINGS }) { editor = it }
                                Section.CHANGES -> ChangesScreen(ui, { page = Page.SETTINGS })
                            }
                        }
                    }
                }
                Page.SETTINGS -> SettingsScreen(ui, model, { page = Page.MAIN }, { page = Page.GROUPS })
                Page.GROUPS -> GroupPicker(model, ui.data.group) { page = Page.SETTINGS }
            }
        }
        selectedLesson?.let { lesson -> LessonDialog(lesson, ui, model, { selectedLesson = null }) { editor = it } }
        editor?.let { request -> AssignmentEditor(request, ui, model) { editor = null } }
        BackHandler(page != Page.MAIN || ui.section != Section.SCHEDULE) {
            when (page) { Page.GROUPS -> page = Page.SETTINGS; Page.SETTINGS -> page = Page.MAIN; Page.MAIN -> model.show(Section.SCHEDULE) }
        }
    }
}

/** Android 17+ asks before an app talks to devices in the local network, such as the group server. */
@Composable private fun LocalNetworkPermission(address: String, granted: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) granted() }
    LaunchedEffect(address) {
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (address.isNotBlank() && Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) launcher.launch(permission)
    }
}

@Composable private fun SectionBar(ui: UiState, model: ScheduleViewModel) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        Section.entries.forEach { section ->
            val badge = when (section) {
                Section.SCHEDULE -> 0
                Section.ASSIGNMENTS -> ui.study.assignments(ui.groupId).count { !it.completedByMe && it.dueAt < ui.now.plus(Duration.ofDays(3)) }
                Section.CHANGES -> ui.study.unseenChanges(ui.groupId)
            }
            val icon = when (section) { Section.SCHEDULE -> Icons.Rounded.CalendarMonth; Section.ASSIGNMENTS -> Icons.Rounded.Checklist; Section.CHANGES -> Icons.Rounded.History }
            NavigationBarItem(
                selected = ui.section == section, onClick = { model.show(section) }, label = { Text(section.title) },
                icon = { BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, null) } }
            )
        }
    }
}

@Composable internal fun ScreenHeader(ui: UiState, title: String, settings: () -> Unit) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Eyebrow("${ui.data.group.faculty.abbr ?: "СПбПУ"} / ${ui.data.group.name}")
            Text(title, fontSize = 31.sp * ui.data.preferences.scale, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
        }
        FilledTonalIconButton(settings) { Icon(Icons.Rounded.Tune, "Настройки") }
    }
}

@Composable private fun ScheduleScreen(ui: UiState, model: ScheduleViewModel, settings: () -> Unit, lesson: (Lesson) -> Unit) {
    val prefs = ui.data.preferences
    val lessons = model.dayLessons(ui).filter { prefs.showPast || it.end > ui.now }
    val vanished = ui.study.vanished(ui.selectedDate, model.dayLessons(ui), ui.groupId).filter { prefs.showPast || (it.old?.end ?: Instant.EPOCH) > ui.now }
    val active = model.activeLesson(ui)
    val isToday = ui.selectedDate == StudyClock.today()
    val nowIndex = if (isToday && lessons.none { it.start <= ui.now && ui.now < it.end }) lessons.indexOfFirst { it.start > ui.now } else -1
    var drag by remember { mutableFloatStateOf(0f) }
    LazyColumn(
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item { ScreenHeader(ui, "Расписание", settings) }
        item { ActiveLessonCard(active, ui.now, prefs.scale, active?.let { ui.study.openAssignments(it, ui.groupId).size } ?: 0, lesson) }
        item { TodayRow(ui, model) }
        item {
            val finished = prefs.isFinished(StudyClock.today(), ui.groupId)
            ActionCard(
                icon = if (finished) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.CheckCircle,
                title = if (finished) "Вернуться к занятиям" else "Завершить учебный день",
                subtitle = if (finished) "Снова показывать сегодняшние пары" else "Убрать активность до следующего дня"
            ) { model.finishDay(!finished) }
        }
        item {
            WeekPicker(ui.selectedDate, prefs.scale, { model.selectDate(it) }, { model.shiftWeek(it) }, { model.selectDate(StudyClock.today()) },
                Modifier.pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = { if (drag.absoluteValue > 90) model.shiftWeek(if (drag < 0) 1 else -1); drag = 0f }) { _, amount -> drag += amount }
                })
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
                Eyebrow(ui.selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Russian).uppercase())
                Text(ruDayMonth(ui.selectedDate), fontSize = 25.sp * prefs.scale, fontWeight = FontWeight.Medium)
            }
        }
        if (lessons.isEmpty() && vanished.isEmpty()) item { EmptyCard(if (ui.refreshing) "Обновляем расписание…" else "На этот день занятий нет") }
        lessons.forEachIndexed { index, entry ->
            if (index > 0) {
                val gap = Duration.between(lessons[index - 1].end, entry.start).toMinutes()
                if (gap >= 20) item(key = "gap-${entry.id}") { BreakRow(gap) }
            }
            if (index == nowIndex) item(key = "now") { NowMarker(ui.now) }
            item(key = entry.id) {
                LessonCard(entry, prefs, ui.groupId, ui.now, ui.study.change(entry, ui.groupId), ui.study.openAssignments(entry, ui.groupId).size, lesson)
            }
        }
        items(vanished, key = { "gone-" + it.fingerprint }) { VanishedCard(it) }
        ui.message?.let { item { StatusCard(it) } }
        item {
            val week = ui.data.weeks["${ui.groupId}-${StudyClock.monday(ui.selectedDate)}"]
            Text(
                (week?.let { "Обновлено ${DateTimeFormatter.ofPattern("d MMM, HH:mm", Russian).withZone(MoscowZone).format(it.fetchedAt)}" } ?: "Данные этой недели ещё не сохранены") +
                    " · сохранено недель: ${ui.data.weeks.keys.count { it.startsWith("${ui.groupId}-") }}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Bento row: today's lessons, close deadlines, new changes. */
@Composable private fun TodayRow(ui: UiState, model: ScheduleViewModel) {
    val today = model.upcoming(ui).filter { StudyClock.date(it.start) == StudyClock.today() && ui.data.preferences.includes(it, ui.groupId) } +
        model.dayLessons(ui.copy(selectedDate = StudyClock.today())).filter { it.start <= ui.now && ui.now < it.end }
    val open = ui.study.assignments(ui.groupId).filter { !it.completedByMe }
    val soon = open.filter { it.dueAt < ui.now.plus(Duration.ofDays(3)) }
    val unseen = ui.study.unseenChanges(ui.groupId)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SummaryTile(Icons.Rounded.CalendarMonth, "${today.distinctBy { it.id }.size}",
            today.maxByOrNull { it.end }?.let { "пар сегодня, до ${StudyClock.time(it.end)}" } ?: "пар сегодня больше нет", false, Modifier.weight(1f)) { model.selectDate(StudyClock.today()) }
        SummaryTile(Icons.Rounded.Checklist, "${soon.size}", if (open.isEmpty()) "заданий нет" else "срок в 3 дня · всего ${open.size}",
            soon.any { it.isOverdue }, Modifier.weight(1f)) { model.show(Section.ASSIGNMENTS) }
        SummaryTile(Icons.Rounded.History, "$unseen", if (unseen == 0) "новых изменений нет" else "новых изменений", unseen > 0, Modifier.weight(1f)) { model.show(Section.CHANGES) }
    }
}

@Composable private fun SummaryTile(icon: ImageVector, value: String, caption: String, warn: Boolean, modifier: Modifier, open: () -> Unit) {
    Column(
        modifier.heightIn(min = 108.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = open).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(icon, null, tint = if (warn) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(caption, fontSize = 11.sp, lineHeight = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

@Composable private fun ActiveLessonCard(lesson: Lesson?, now: Instant, scale: Float, tasks: Int, open: (Lesson) -> Unit) {
    val container = MaterialTheme.colorScheme.secondary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(container, lerpToBlack(container))))
            .then(if (lesson != null) Modifier.clickable { open(lesson) } else Modifier).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val ongoing = lesson != null && lesson.start <= now && now < lesson.end
        Eyebrow(if (lesson == null) "СВОБОДНОЕ ВРЕМЯ" else if (ongoing) "СЕЙЧАС НА ПАРЕ" else "СЛЕДУЮЩАЯ ПАРА", Color.White.copy(alpha = .7f))
        if (lesson == null) Text("Ближайших занятий нет", color = Color.White, fontSize = 23.sp * scale, fontWeight = FontWeight.SemiBold)
        else {
            Text(lesson.subject, color = Color.White, fontSize = 23.sp * scale, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp * scale)
            Text("${lesson.timeRange}  ·  ${lesson.location}", color = Color.White.copy(alpha = .78f), fontSize = 13.sp * scale)
            val until = Duration.between(now, if (ongoing) lesson.end else lesson.start).coerceAtLeast(Duration.ZERO)
            Text("${if (ongoing) "До конца" else "До начала"} ${duration(until)}", color = Color.White, fontSize = 16.sp * scale, fontWeight = FontWeight.Medium)
            if (tasks > 0) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.Checklist, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Text(if (tasks == 1) "1 задание к этой паре" else "Заданий к этой паре: $tasks", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            if (ongoing) LinearWavyProgressIndicatorOrPlain(
                (Duration.between(lesson.start, now).toMillis().toFloat() / Duration.between(lesson.start, lesson.end).toMillis()).coerceIn(0f, 1f)
            )
        }
    }
}

private fun lerpToBlack(color: Color) = Color(color.red * .65f, color.green * .65f, color.blue * .65f, color.alpha)
private fun duration(value: Duration): String { val s = value.seconds.coerceAtLeast(0); return if (s >= 86_400) "${s / 86_400} д" else "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) }

@Composable private fun WeekPicker(selected: LocalDate, scale: Float, select: (LocalDate) -> Unit, shift: (Long) -> Unit, today: () -> Unit, modifier: Modifier = Modifier) {
    val monday = StudyClock.monday(selected)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected.format(DateTimeFormatter.ofPattern("LLLL yyyy", Russian)).replaceFirstChar { it.uppercase() }, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(today) { Text("Сегодня") }
            IconButton({ shift(-1) }) { Icon(Icons.Rounded.ChevronLeft, "Предыдущая неделя") }
            IconButton({ shift(1) }) { Icon(Icons.Rounded.ChevronRight, "Следующая неделя") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { index ->
                val date = monday.plusDays(index.toLong()); val chosen = date == selected; val current = date == StudyClock.today()
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { select(date) }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Russian).uppercase(), fontSize = 9.sp, color = if (chosen) Color.White.copy(.72f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${date.dayOfMonth}", fontSize = 19.sp * scale, fontWeight = FontWeight.Medium, color = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface)
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (current) if (chosen) Color.White else MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
    }
}

@Composable private fun LessonCard(lesson: Lesson, prefs: StudyPreferences, groupId: Int, now: Instant, change: ScheduleChange?, tasks: Int, open: (Lesson) -> Unit) {
    val skipped = !prefs.includes(lesson, groupId)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { open(lesson) }
            .padding(if (prefs.compact) 14.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.width(52.dp)) { Text(StudyClock.time(lesson.start), fontWeight = FontWeight.SemiBold); Text(StudyClock.time(lesson.end), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box(Modifier.width(3.dp).heightIn(min = 40.dp).clip(CircleShape).background(if (skipped) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary.copy(alpha = .6f)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(lesson.kind.uppercase(), fontSize = 9.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = MaterialTheme.colorScheme.primary)
                if (skipped) Text("ПРОПУСК", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                change?.let { ChangeTag(it.kind) }
                Spacer(Modifier.weight(1f))
                if (tasks > 0) Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Checklist, "Невыполненных заданий: $tasks", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                    Text("$tasks", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(lesson.subject, fontSize = 16.sp * prefs.scale, fontWeight = FontWeight.Medium)
            Text(lesson.location, fontSize = 12.sp * prefs.scale, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (prefs.showTeachers && !prefs.compact && lesson.teachers.isNotEmpty()) Text(lesson.teachers.joinToString(), fontSize = 12.sp * prefs.scale, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lesson.end <= now) Text("Завершено", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun VanishedCard(change: ScheduleChange) {
    val old = change.old ?: return
    val color = change.kind.color()
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).border(BorderStroke(1.dp, color.copy(alpha = .35f)), RoundedCornerShape(22.dp)).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.width(52.dp)) { Text(StudyClock.time(old.start), fontWeight = FontWeight.SemiBold, textDecoration = TextDecoration.LineThrough); Text(StudyClock.time(old.end), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ChangeTag(change.kind)
            Text(change.subject, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.LineThrough, color = MaterialTheme.colorScheme.onSurfaceVariant)
            change.new?.takeIf { change.kind == ScheduleChangeKind.MOVED }?.let {
                Text("Теперь: ${ruWeekdayDayMonth(StudyClock.date(it.start))} · ${it.timeRange}", fontSize = 12.sp)
            }
        }
    }
}

@Composable private fun BreakRow(minutes: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 6.dp)) {
        HorizontalDivider(Modifier.width(14.dp))
        Text(if (minutes >= 60) "Перерыв ${minutes / 60} ч ${minutes % 60} мин" else "Перерыв $minutes мин", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable private fun NowMarker(now: Instant) {
    val color = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text("Сейчас ${StudyClock.time(now)}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
        HorizontalDivider(Modifier.weight(1f), thickness = 1.5.dp, color = color.copy(alpha = .5f))
    }
}

@Composable private fun LessonDialog(lesson: Lesson, ui: UiState, model: ScheduleViewModel, close: () -> Unit, edit: (EditorRequest) -> Unit) {
    val data = ui.data
    var one by remember { mutableStateOf(data.preferences.lessonRules[lesson.ruleKey(data.group.id)] ?: AttendanceRule.AUTOMATIC) }
    var subject by remember { mutableStateOf(data.preferences.subjectRules[lesson.subjectRuleKey(data.group.id)] ?: AttendanceRule.AUTOMATIC) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(close) { Text("Готово") } }, title = { Text(lesson.subject) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${lesson.timeRange} · ${lesson.kind}"); Text(lesson.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lesson.teachers.isNotEmpty()) Text(lesson.teachers.joinToString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lesson.additionalInfo.isNotBlank()) Text(lesson.additionalInfo)
            val changes = ui.study.changes(ui.groupId).filter { it.describes(lesson) }
            if (changes.isNotEmpty()) {
                Eyebrow("ЧТО ИЗМЕНИЛОСЬ")
                changes.forEach { ChangeCard(it, showSubject = false) }
            }
            Eyebrow("ЗАДАНИЯ К ЭТОЙ ПАРЕ")
            ui.study.assignments(ui.groupId).filter { it.isDueAt(lesson) }.forEach { AssignmentCard(it, ui, model, edit) }
            OutlinedButton({ close(); edit(EditorRequest(model.newDraft(ui, lesson = lesson), true)) }) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить задание")
            }
            RuleSelector("Для этого занятия", one) { one = it; model.setRule(lesson, it, false) }
            RuleSelector("Для предмета и типа", subject) { subject = it; model.setRule(lesson, it, true) }
            listOfNotNull(lesson.webinarUrl, lesson.lmsUrl).forEach { url -> TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Открыть ссылку ↗") } }
        }
    })
}

@Composable private fun RuleSelector(title: String, value: AttendanceRule, set: (AttendanceRule) -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.labelMedium); Row { AttendanceRule.entries.forEach { rule -> FilterChip(selected = value == rule, onClick = { set(rule) }, label = { Text(when (rule) { AttendanceRule.AUTOMATIC -> "Общее"; AttendanceRule.SKIP -> "Пропуск"; AttendanceRule.ATTEND -> "Посещать" }) }, modifier = Modifier.padding(end = 5.dp)) } } }
}

@Composable private fun ActionCard(icon: ImageVector, title: String, subtitle: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = action).padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(18.dp))
    }
}
@Composable internal fun EmptyCard(text: String, detail: String? = null) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(26.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text, fontSize = if (detail != null) 22.sp else 15.sp, fontWeight = if (detail != null) FontWeight.Medium else FontWeight.Normal, color = if (detail != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    detail?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}
@Composable internal fun StatusCard(text: String) = Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.7.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

private val genitiveMonths = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
/** "6 ноября": Android formats MMMM in the nominative ("ноябрь") on some versions. */
internal fun ruDayMonth(date: LocalDate) = "${date.dayOfMonth} ${genitiveMonths[date.monthValue - 1]}"
internal fun ruWeekdayDayMonth(date: LocalDate) = "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Russian).replaceFirstChar { it.uppercase() }}, ${ruDayMonth(date)}"
