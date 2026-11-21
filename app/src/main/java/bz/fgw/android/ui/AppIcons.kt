package bz.fgw.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

@Composable
internal fun AppIcon(name: String, label: String? = null, modifier: Modifier = Modifier) {
    val vector = remember(name) {
        val path = when (name) {
            "back" -> "M19 12H5 M12 5L5 12L12 19"
            "check" -> "M12 3A9 9 0 1 1 11.99 3 M8 12L11 15L16 9"
            "clock" -> "M12 3A9 9 0 1 1 11.99 3 M12 7V12L16 14"
            "hide" -> "M3 3L21 21 M10 5C15 3 20 8 22 12C21 14 20 15 18 16 M14 19C8 20 4 16 2 12C3 10 4 9 6 7 M9 9A4 4 0 0 0 15 15"
            "ignore" -> "M12 3A9 9 0 1 1 11.99 3 M6 6L18 18"
            "search" -> "M10 3A7 7 0 1 1 9.99 3 M15 15L21 21"
            "refresh" -> "M20 8A8 8 0 1 0 20 16 M20 3V8H15"
            "gift" -> "M3 8H21V12H3Z M5 12V21H19V12 M12 8V21 M12 8C4 8 5 1 9 3C11 4 12 8 12 8C20 8 19 1 15 3C13 4 12 8 12 8"
            "settings" -> "M4 5H20 M4 12H20 M4 19H20 M8 2V8 M16 9V15 M10 16V22"
            "history" -> "M3 10A9 9 0 1 1 5 18 M3 4V10H9 M12 7V12L16 14"
            "arrow" -> "M5 19L19 5 M7 5H19V17"
            "filter" -> "M3 5H21L14 13V20L10 18V13Z"
            "sort" -> "M4 5H20 M4 12H15 M4 19H10"
            "close" -> "M6 6L18 18 M18 6L6 18"
            "info" -> "M12 3A9 9 0 1 1 11.99 3 M12 11V17 M12 7V7.1"
            "help" -> "M12 3A9 9 0 1 1 11.99 3 M9 9A3 3 0 1 1 15 9C15 11 12 11 12 14 M12 17V17.1"
            "more" -> "M5 11V13 M12 11V13 M19 11V13"
            "chevron" -> "M9 5L16 12L9 19"
            else -> "M9 5L16 12L9 19"
        }
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
    Icon(vector, contentDescription = label, modifier = modifier.size(22.dp))
}

internal fun actionLabel(action: String, text: UiText): String = when (action) {
    "claim" -> text.claimed
    "unclaim" -> text.undoClaim
    "snooze" -> text.choose("Отложить на 24 часа", "Snooze for 24 hours")
    "hide" -> text.choose("Скрыть эту раздачу", "Hide this giveaway")
    "ignore" -> text.choose("Игнорировать игру", "Ignore this game")
    else -> text.choose("Снять отметки", "Clear marks")
}

@Composable
internal fun MarkHelp(text: UiText) {
    val help = listOf(
        "check" to text.choose("Уже забрал — скрывает игру из раздач и уведомлений, включая будущие раздачи этой игры.", "Claimed hides the game from giveaways and notifications, including future giveaways of the same game."),
        "clock" to text.choose("Отложить — скрывает игру на 24 часа. После этого она снова доступна, если акция продолжается.", "Snooze hides the game for 24 hours. It returns if the giveaway is still active."),
        "hide" to text.choose("Скрыть — убирает только эту раздачу. Новые акции этой игры появятся снова.", "Hide removes this giveaway. New giveaways of the same game will appear again."),
        "ignore" to text.choose("Игнорировать — скрывает игру и её будущие раздачи, пока вы не снимете отметку.", "Ignore hides the game and its future giveaways until you clear the mark."),
    )
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        help.forEach { (icon, description) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                AppIcon(icon)
                Text(description, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
