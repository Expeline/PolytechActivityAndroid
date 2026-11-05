package ru.expeline.polytech.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import ru.expeline.polytech.data.AppTheme
import ru.expeline.polytech.data.StudyAccent

fun StudyAccent.color() = when (this) {
    StudyAccent.GREEN -> Color(0xFF21734D); StudyAccent.BLUE -> Color(0xFF3569C8); StudyAccent.PURPLE -> Color(0xFF7650C8)
    StudyAccent.PINK -> Color(0xFFC84586); StudyAccent.RED -> Color(0xFFC74343); StudyAccent.ORANGE -> Color(0xFFC96827)
    StudyAccent.YELLOW -> Color(0xFF9B7B16); StudyAccent.TEAL -> Color(0xFF218A86); StudyAccent.GRAPHITE -> Color(0xFF62676E)
}
fun StudyAccent.deep() = if (this == StudyAccent.GREEN) Color(0xFF0E402E) else color().copy(alpha = 0.88f)

/** Material 3 Expressive: springy motion everywhere, the app's own accent colors. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable fun PolytechTheme(theme: AppTheme, accent: StudyAccent, content: @Composable () -> Unit) {
    val dark = theme == AppTheme.DARK || (theme == AppTheme.SYSTEM && isSystemInDarkTheme())
    val scheme: ColorScheme = if (dark) darkColorScheme(
        primary = accent.color(), secondary = accent.deep(), tertiary = Color(0xFFE39A4B),
        primaryContainer = accent.color().copy(alpha = .30f), secondaryContainer = accent.color().copy(alpha = .30f),
        onPrimaryContainer = Color.White, onSecondaryContainer = Color.White,
        background = Color(0xFF101311), surface = Color(0xFF181D1A), surfaceContainer = Color(0xFF1E2421)
    ) else lightColorScheme(
        primary = accent.color(), secondary = accent.deep(), tertiary = Color(0xFFC96827),
        primaryContainer = accent.color().copy(alpha = .14f), secondaryContainer = accent.color().copy(alpha = .14f),
        onPrimaryContainer = accent.deep(), onSecondaryContainer = accent.deep(),
        background = Color(0xFFF3F4F1), surface = Color.White, surfaceContainer = Color.White
    )
    MaterialExpressiveTheme(colorScheme = scheme, motionScheme = MotionScheme.expressive(), content = content)
}

/** Expressive wavy progress for the ongoing lesson. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable fun LinearWavyProgressIndicatorOrPlain(progress: Float) {
    LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = Color.White, trackColor = Color.White.copy(alpha = .24f))
}
