package ru.expeline.polytech.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ru.expeline.polytech.PolytechApplication
import ru.expeline.polytech.data.StudyClock
import ru.expeline.polytech.ui.color
import java.time.Duration

class ScheduleWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as PolytechApplication
        val data = app.repository.state.value
        val lesson = app.repository.activeOrNext(value = data)?.takeIf { it.start <= StudyClock.now() }
        val accent = data.preferences.accent.color()
        provideContent {
            Column(
                GlanceModifier.fillMaxSize().background(ColorProvider(accent)).padding(18.dp),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(if (lesson == null) "СВОБОДНОЕ ВРЕМЯ" else "СЕЙЧАС", style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .7f)), fontWeight = FontWeight.Medium))
                Spacer(GlanceModifier.height(8.dp))
                Text(lesson?.subject ?: "Текущих занятий нет", maxLines = 2, style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Bold))
                lesson?.let {
                    Spacer(GlanceModifier.height(6.dp)); Text("${it.timeRange} · ${it.location}", maxLines = 1, style = TextStyle(color = ColorProvider(Color.White.copy(alpha = .76f))))
                    val minutes = Duration.between(StudyClock.now(), it.end).toMinutes().coerceAtLeast(0)
                    Spacer(GlanceModifier.height(8.dp)); Text("До конца ${minutes / 60}:${(minutes % 60).toString().padStart(2, '0')}", style = TextStyle(color = ColorProvider(Color.White), fontWeight = FontWeight.Medium))
                }
            }
        }
    }
    companion object {
        suspend fun updateAll(context: Context) = ScheduleWidget().updateAll(context)
    }
}

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() { override val glanceAppWidget: GlanceAppWidget = ScheduleWidget() }

