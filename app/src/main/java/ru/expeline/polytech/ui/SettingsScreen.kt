package ru.expeline.polytech.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.expeline.polytech.data.*

@Composable fun SettingsScreen(ui: UiState, model: ScheduleViewModel, back: () -> Unit, groups: () -> Unit) {
    val prefs = ui.data.preferences
    val context = LocalContext.current
    LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }; Text("Настройки", fontSize = 29.sp, fontWeight = FontWeight.Medium) } }
        item { SettingsSection("Учёба") { Row(Modifier.fillMaxWidth().clickable(onClick = groups).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(ui.data.group.name, fontWeight = FontWeight.SemiBold); Text(ui.data.group.faculty.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Icon(Icons.Rounded.ChevronRight, null) } } }
        item { SettingsSection("Оформление") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { AppTheme.entries.forEach { theme -> FilterChip(selected = prefs.theme == theme, onClick = { model.preferences { it.copy(theme = theme) } }, label = { Text(when(theme) { AppTheme.SYSTEM -> "Система"; AppTheme.LIGHT -> "Светлая"; AppTheme.DARK -> "Тёмная" }) }, modifier = Modifier.weight(1f)) } }
            Text("Акцентный цвет", fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { StudyAccent.entries.forEach { accent -> Box(Modifier.size(34.dp).clip(CircleShape).background(accent.color()).clickable { model.preferences { it.copy(accent = accent) } }, contentAlignment = Alignment.Center) { if (prefs.accent == accent) Icon(Icons.Rounded.Check, null, tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(18.dp)) } } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Масштаб текста", Modifier.weight(1f)); Text("${(prefs.scale * 100).toInt()} %", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Slider(value = prefs.scale, onValueChange = { value -> model.preferences { it.copy(scale = (value * 20).toInt() / 20f) } }, valueRange = .85f..1.35f, steps = 9)
            ToggleSetting("Компактные карточки", prefs.compact) { model.preferences { p -> p.copy(compact = it) } }
            ToggleSetting("Показывать преподавателей", prefs.showTeachers) { model.preferences { p -> p.copy(showTeachers = it) } }
            ToggleSetting("Показывать прошедшие пары", prefs.showPast) { model.preferences { p -> p.copy(showPast = it) } }
        } }
        item { SettingsSection("Посещение и активности") {
            ToggleSetting("Активность текущей и следующей пары", prefs.liveEnabled, "Постоянное уведомление с таймером") { model.preferences { p -> p.copy(liveEnabled = it) } }
            ToggleSetting("Пропускать военную подготовку", prefs.skipMilitary) { model.preferences { p -> p.copy(skipMilitary = it) } }
            ToggleSetting("Пропускать все лекции", prefs.skipLectures, "Практики остаются. Исключения задаются в карточке занятия.") { model.preferences { p -> p.copy(skipLectures = it) } }
            Text("Пропуски не скрывают пары из расписания. Они влияют на активность и виджет.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item { SettingsSection("Без интернета") {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Недель вперёд", Modifier.weight(1f)); Text("${prefs.weeksAhead}", color = MaterialTheme.colorScheme.primary) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(4, 6, 8, 12).forEach { count -> FilterChip(selected = prefs.weeksAhead == count, onClick = { model.preferences { it.copy(weeksAhead = count) }; model.refresh() }, label = { Text("$count") }, modifier = Modifier.weight(1f)) } }
            Text("Сначала показываются сохранённые данные, затем расписание обновляется в фоне.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { model.refresh() }, enabled = !ui.refreshing) { Text(if (ui.refreshing) "Обновляем…" else "Обновить сейчас") }
        } }
        item { SettingsSection("Виджет") { Text("Добавьте виджет «Политех» на главный экран. Он синхронизирован с группой, правилами посещения, темой и акцентом и показывает текущую пару.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { SettingsSection("Как обновляется таймер") { Text("Android показывает активность как постоянное системное уведомление. Фоновое обновление выполняется через WorkManager; точный момент запуска выбирает система для экономии батареи.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        item { TextButton({ context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ui.data.group.sourceUrl))) }) { Text("Расписание СПбПУ ↗") } }
    }
}

@Composable private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}

@Composable private fun ToggleSetting(title: String, value: Boolean, subtitle: String = "", set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!value) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(value, set) }
}

@Composable fun GroupPicker(model: ScheduleViewModel, current: StudyGroup, back: () -> Unit) {
    var query by remember { mutableStateOf("") }; var results by remember { mutableStateOf(emptyList<StudyGroup>()) }; var error by remember { mutableStateOf<String?>(null) }; var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { IconButton(back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }; Text("Моя группа", fontSize = 24.sp, fontWeight = FontWeight.Medium) }
        LazyColumn(contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Номер группы или ссылка ruz.spbstu.ru") }, singleLine = true, trailingIcon = { Icon(Icons.Rounded.Search, null) }) }
            item { Button(onClick = { scope.launch { searching = true; error = null; model.search(query).fold({ results = it }, { error = it.localizedMessage }); searching = false } }, enabled = query.isNotBlank() && !searching) { Text(if (searching) "Ищем…" else "Найти группу") } }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            if (!searching && results.isEmpty() && query.isNotBlank() && error == null) item { Text("Введите номер и нажмите «Найти группу».", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(results, key = { it.id }) { group -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainer).clickable { model.choose(group); back() }.padding(18.dp)) { Text(group.name, fontWeight = FontWeight.SemiBold); Text(group.faculty.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            item { Text("Сейчас выбрана: ${current.name}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium) }
        }
    }
}

