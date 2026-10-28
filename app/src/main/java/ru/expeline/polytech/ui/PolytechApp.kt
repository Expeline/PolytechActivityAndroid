package ru.expeline.polytech.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.expeline.polytech.data.*
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.absoluteValue

private enum class Page { SCHEDULE, SETTINGS, GROUPS }

@Composable fun PolytechApp(model: ScheduleViewModel) {
    val ui by model.state.collectAsStateWithLifecycle()
    var page by remember { mutableStateOf(Page.SCHEDULE) }
    var selectedLesson by remember { mutableStateOf<Lesson?>(null) }
    PolytechTheme(ui.data.preferences.theme, ui.data.preferences.accent) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (page) {
                Page.SCHEDULE -> ScheduleScreen(ui, model, { page = Page.SETTINGS }, { selectedLesson = it })
                Page.SETTINGS -> SettingsScreen(ui, model, { page = Page.SCHEDULE }, { page = Page.GROUPS })
                Page.GROUPS -> GroupPicker(model, ui.data.group) { page = Page.SETTINGS }
            }
        }
        selectedLesson?.let { LessonDialog(it, ui.data, model) { selectedLesson = null } }
        BackHandler(page != Page.SCHEDULE) { page = if (page == Page.GROUPS) Page.SETTINGS else Page.SCHEDULE }
    }
}

@Composable private fun ScheduleScreen(ui: UiState, model: ScheduleViewModel, settings: () -> Unit, lesson: (Lesson) -> Unit) {
    val prefs = ui.data.preferences
    val lessons = model.dayLessons(ui).filter { prefs.showPast || it.end > ui.now }
    val active = model.activeLesson(ui)
    var drag by remember { mutableFloatStateOf(0f) }
    LazyColumn(
        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Eyebrow("${ui.data.group.faculty.abbr ?: "СПбПУ"} / ${ui.data.group.name}")
                    Text("Расписание", fontSize = 31.sp * prefs.scale, fontWeight = FontWeight.SemiBold, letterSpacing = (-1).sp)
                }
                IconButton(settings) { Icon(Icons.Rounded.Tune, "Настройки") }
            }
        }
        item { ActiveLessonCard(active, ui.now, prefs.scale, lesson) }
        item {
            val finished = prefs.isFinished(StudyClock.today(), ui.data.group.id)
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
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Eyebrow(ui.selectedDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.forLanguageTag("ru")).uppercase())
                Text(ui.selectedDate.format(DateTimeFormatter.ofPattern("d MMMM", Locale.forLanguageTag("ru"))), fontSize = 25.sp * prefs.scale, fontWeight = FontWeight.Medium)
            }
        }
        if (lessons.isEmpty()) item { EmptyCard(if (ui.refreshing) "Обновляем расписание…" else "На этот день занятий нет") }
        items(lessons, key = { it.id }) { entry -> LessonCard(entry, prefs, ui.data.group.id, ui.now, lesson) }
        ui.message?.let { item { StatusCard(it) } }
        item {
            val week = ui.data.weeks[model.run { "${ui.data.group.id}-${StudyClock.monday(ui.selectedDate)}" }]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(week?.let { "Обновлено ${DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.forLanguageTag("ru")).withZone(MoscowZone).format(it.fetchedAt)}" } ?: "Данные этой недели ещё не сохранены", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Сохранено недель: ${ui.data.weeks.keys.count { it.startsWith("${ui.data.group.id}-") }}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable private fun ActiveLessonCard(lesson: Lesson?, now: java.time.Instant, scale: Float, open: (Lesson) -> Unit) {
    val accent = MaterialTheme.colorScheme.primary
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.secondary).then(if (lesson != null) Modifier.clickable { open(lesson) } else Modifier).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Eyebrow(if (lesson == null) "СВОБОДНОЕ ВРЕМЯ" else if (lesson.start <= now && now < lesson.end) "СЕЙЧАС" else "СЛЕДУЮЩАЯ ПАРА", Color.White.copy(alpha = .7f))
        if (lesson == null) Text("Ближайших занятий нет", color = Color.White, fontSize = 23.sp * scale, fontWeight = FontWeight.SemiBold)
        else {
            Text(lesson.subject, color = Color.White, fontSize = 23.sp * scale, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp * scale)
            Text("${lesson.timeRange}  ·  ${lesson.location}", color = Color.White.copy(alpha = .78f), fontSize = 13.sp * scale)
            val until = Duration.between(now, lesson.end).coerceAtLeast(Duration.ZERO)
            Text("До конца ${duration(until)}", color = Color.White, fontSize = 16.sp * scale, fontWeight = FontWeight.Medium)
            if (now >= lesson.start) LinearProgressIndicator(
                progress = { (Duration.between(lesson.start, now).toMillis().toFloat() / Duration.between(lesson.start, lesson.end).toMillis()).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(), color = accent, trackColor = Color.White.copy(alpha = .24f)
            )
        }
    }
}

private fun duration(value: Duration): String { val s = value.seconds.coerceAtLeast(0); return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) }

@Composable private fun WeekPicker(selected: LocalDate, scale: Float, select: (LocalDate) -> Unit, shift: (Long) -> Unit, today: () -> Unit, modifier: Modifier = Modifier) {
    val monday = StudyClock.monday(selected)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(selected.format(DateTimeFormatter.ofPattern("LLLL yyyy", Locale.forLanguageTag("ru"))).replaceFirstChar { it.uppercase() }, fontSize = 17.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(today) { Text("Сегодня") }
            IconButton({ shift(-1) }) { Icon(Icons.Rounded.ChevronLeft, "Предыдущая неделя") }
            IconButton({ shift(1) }) { Icon(Icons.Rounded.ChevronRight, "Следующая неделя") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(7) { index ->
                val date = monday.plusDays(index.toLong()); val chosen = date == selected; val current = date == StudyClock.today()
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { select(date) }.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("ru")).uppercase(), fontSize = 9.sp, color = if (chosen) Color.White.copy(.72f) else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${date.dayOfMonth}", fontSize = 19.sp * scale, fontWeight = FontWeight.Medium, color = if (chosen) Color.White else MaterialTheme.colorScheme.onSurface)
                    Box(Modifier.size(4.dp).clip(CircleShape).background(if (current) if (chosen) Color.White else MaterialTheme.colorScheme.primary else Color.Transparent))
                }
            }
        }
    }
}

@Composable private fun LessonCard(lesson: Lesson, prefs: StudyPreferences, groupId: Int, now: java.time.Instant, open: (Lesson) -> Unit) {
    val skipped = !prefs.includes(lesson, groupId)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { open(lesson) }.padding(if (prefs.compact) 14.dp else 18.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.width(52.dp)) { Text(StudyClock.time(lesson.start), fontWeight = FontWeight.SemiBold); Text(StudyClock.time(lesson.end), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row { Text(lesson.subject, fontSize = 16.sp * prefs.scale, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)); if (skipped) Icon(Icons.Rounded.VisibilityOff, "Пропускается", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
            Text("${lesson.kind} · ${lesson.location}", fontSize = 12.sp * prefs.scale, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (prefs.showTeachers && lesson.teachers.isNotEmpty()) Text(lesson.teachers.joinToString(), fontSize = 12.sp * prefs.scale, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lesson.end <= now) Text("Завершено", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun LessonDialog(lesson: Lesson, data: PersistedState, model: ScheduleViewModel, close: () -> Unit) {
    var one by remember { mutableStateOf(data.preferences.lessonRules[lesson.ruleKey(data.group.id)] ?: AttendanceRule.AUTOMATIC) }
    var subject by remember { mutableStateOf(data.preferences.subjectRules[lesson.subjectRuleKey(data.group.id)] ?: AttendanceRule.AUTOMATIC) }
    val context = LocalContext.current
    AlertDialog(onDismissRequest = close, confirmButton = { TextButton(close) { Text("Готово") } }, title = { Text(lesson.subject) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("${lesson.timeRange} · ${lesson.kind}"); Text(lesson.location, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lesson.teachers.isNotEmpty()) Text(lesson.teachers.joinToString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (lesson.additionalInfo.isNotBlank()) Text(lesson.additionalInfo)
            RuleSelector("Для этого занятия", one) { one = it; model.setRule(lesson, it, false) }
            RuleSelector("Для предмета и типа", subject) { subject = it; model.setRule(lesson, it, true) }
            listOfNotNull(lesson.webinarUrl, lesson.lmsUrl).forEach { url -> TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("Открыть ссылку ↗") } }
        }
    })
}

@Composable private fun RuleSelector(title: String, value: AttendanceRule, set: (AttendanceRule) -> Unit) {
    Column { Text(title, style = MaterialTheme.typography.labelMedium); Row { AttendanceRule.entries.forEach { rule -> FilterChip(selected = value == rule, onClick = { set(rule) }, label = { Text(when (rule) { AttendanceRule.AUTOMATIC -> "Общее"; AttendanceRule.SKIP -> "Пропуск"; AttendanceRule.ATTEND -> "Посещать" }) }, modifier = Modifier.padding(end = 5.dp)) } } }
}

@Composable private fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(17.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable(onClick = action).padding(17.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Rounded.ChevronRight, null, modifier = Modifier.size(18.dp))
    }
}
@Composable private fun EmptyCard(text: String) = Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(26.dp)) { Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun StatusCard(text: String) = Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp)) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable private fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) = Text(text, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.7.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)

