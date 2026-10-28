package ru.expeline.polytech.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ru.expeline.polytech.data.AppTheme
import ru.expeline.polytech.data.StudyAccent

fun StudyAccent.color() = when (this) {
    StudyAccent.GREEN -> Color(0xFF21734D); StudyAccent.BLUE -> Color(0xFF3569C8); StudyAccent.PURPLE -> Color(0xFF7650C8)
    StudyAccent.PINK -> Color(0xFFC84586); StudyAccent.RED -> Color(0xFFC74343); StudyAccent.ORANGE -> Color(0xFFC96827)
    StudyAccent.YELLOW -> Color(0xFF9B7B16); StudyAccent.TEAL -> Color(0xFF218A86); StudyAccent.GRAPHITE -> Color(0xFF62676E)
}
fun StudyAccent.deep() = if (this == StudyAccent.GREEN) Color(0xFF0E402E) else color().copy(alpha = 0.88f)
fun StudyAccent.highlight() = if (this == StudyAccent.GREEN) Color(0xFFC2EF8A) else Color.White.copy(alpha = 0.92f)

@Composable fun PolytechTheme(theme: AppTheme, accent: StudyAccent, content: @Composable () -> Unit) {
    val dark = theme == AppTheme.DARK || (theme == AppTheme.SYSTEM && isSystemInDarkTheme())
    val scheme: ColorScheme = if (dark) darkColorScheme(
        primary = accent.color(), secondary = accent.highlight(), background = Color(0xFF111412), surface = Color(0xFF1A1F1C), surfaceContainer = Color(0xFF202622)
    ) else lightColorScheme(
        primary = accent.color(), secondary = accent.deep(), background = Color(0xFFF4F5F2), surface = Color.White, surfaceContainer = Color.White
    )
    MaterialTheme(colorScheme = scheme, typography = MaterialTheme.typography, content = content)
}

