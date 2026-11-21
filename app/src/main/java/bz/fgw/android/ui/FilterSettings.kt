package bz.fgw.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import bz.fgw.android.data.FilterPreferences
import bz.fgw.android.data.PersonalMark
import java.util.Locale

@Composable
internal fun FilterSettings(initial: FilterPreferences, text: UiText, save: (FilterPreferences) -> Unit) {
    var draft by rememberSaveable(stateSaver = androidx.compose.runtime.saveable.Saver<FilterPreferences, String>(
        save = { it.toJson() }, restore = { FilterPreferences.fromJson(it) },
    )) { mutableStateOf(initial) }
    var ids by rememberSaveable { mutableStateOf(initial.blockedAppIds.joinToString("\n")) }
    var keywords by rememberSaveable { mutableStateOf(initial.blockedKeywords.joinToString("\n")) }
    var genres by rememberSaveable { mutableStateOf(initial.genres.joinToString("\n")) }
    var rating by rememberSaveable { mutableStateOf(initial.minRating.toString()) }
    var reviews by rememberSaveable { mutableStateOf(initial.minReviews.toString()) }
    fun entries(value: String): Set<String> = value.split('\n', ',').map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()
    val valid = rating.toIntOrNull() in 0..100 && reviews.toIntOrNull() in 0..10_000_000 &&
        entries(ids).all { it.toLongOrNull()?.let { number -> number > 0 } == true }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text.choose("Фильтры применяются к раздачам и уведомлениям. История позволяет найти скрытые записи.",
            "Filters apply to giveaways and notifications. Search History to find excluded records."), style = MaterialTheme.typography.bodySmall)
        ToggleRow(text.choose("Показывать DLC", "Include DLC"), !draft.ignoreDlc) { draft = draft.copy(ignoreDlc = !it) }
        ToggleRow(text.choose("Показывать саундтреки", "Include soundtracks"), !draft.ignoreSoundtracks) { draft = draft.copy(ignoreSoundtracks = !it) }
        ToggleRow(text.choose("Показывать демоверсии", "Include demos"), !draft.ignoreDemos) { draft = draft.copy(ignoreDemos = !it) }
        ToggleRow(text.choose("Показывать программы и инструменты", "Include software and tools"), !draft.ignoreTools) { draft = draft.copy(ignoreTools = !it) }
        ToggleRow(text.choose("Показывать наборы", "Include packages"), !draft.ignorePackages) { draft = draft.copy(ignorePackages = !it) }
        Text(text.choose("Рейтинги Steam", "Steam reviews"), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(rating, { rating = it }, label = { Text(text.choose("Минимум положительных, %", "Minimum positive reviews, %")) },
            modifier = Modifier.fillMaxWidth(), isError = rating.toIntOrNull() !in 0..100, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(reviews, { reviews = it }, label = { Text(text.choose("Минимальное число отзывов", "Minimum review count")) },
            modifier = Modifier.fillMaxWidth(), isError = reviews.toIntOrNull() !in 0..10_000_000, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        ToggleRow(text.choose("Показывать игры без отзывов", "Include unrated games"), draft.includeUnrated) { draft = draft.copy(includeUnrated = it) }
        OutlinedTextField(ids, { ids = it }, label = { Text(text.choose("Исключённые Steam ID", "Excluded Steam IDs")) }, minLines = 2, modifier = Modifier.fillMaxWidth(),
            isError = entries(ids).any { it.toLongOrNull()?.let { number -> number > 0 } != true },
            supportingText = { Text(text.choose("Положительные числовые ID, через запятую или по одному в строке.", "Positive numeric IDs, separated by commas or new lines.")) })
        OutlinedTextField(keywords, { keywords = it }, label = { Text(text.choose("Исключить слова в названии", "Exclude title keywords")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(genres, { genres = it }, label = { Text(text.choose("Жанры", "Genres")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
        Text(text.choose("Слова и жанры разделяйте запятыми или строками. Указывайте названия жанров из магазина, например RPG. Пустое поле включает все жанры.",
            "Separate words and genres with commas or new lines. Use store genre names, such as RPG. Leave empty for all genres."), style = MaterialTheme.typography.bodySmall)
        Text(text.choose("Платформы", "Platforms"), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("windows" to "Windows", "mac" to "macOS", "linux" to "Linux").forEach { (key, label) ->
                FilterChip(selected = key in draft.platforms, onClick = {
                    draft = draft.copy(platforms = if (key in draft.platforms) draft.platforms - key else draft.platforms + key)
                }, label = { Text(label) })
            }
        }
        Text(text.choose("Без выбранных платформ показываются все игры. При выборе жанра или платформы игры с неизвестными данными скрываются.",
            "No platform selected includes all games. Selecting genres or platforms excludes games with unknown metadata."), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { draft = FilterPreferences(); ids = ""; keywords = ""; genres = ""; rating = "0"; reviews = "0" }) {
            Text(text.choose("По умолчанию", "Restore defaults"))
        }
        Button(enabled = valid, onClick = {
            save(draft.copy(blockedAppIds = entries(ids), blockedKeywords = entries(keywords), genres = entries(genres), minRating = rating.toInt(), minReviews = reviews.toInt()))
        }, modifier = Modifier.fillMaxWidth()) { Text(text.choose("Сохранить фильтры", "Save filters")) }
    }
}

@Composable
internal fun NumberPreference(label: String, value: Int, range: IntRange, text: UiText, save: (Int) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(value, editing) { mutableStateOf(value.toString()) }
    TextButton(onClick = { editing = true }) { Text("$label · $value") }
    if (editing) AlertDialog(
        onDismissRequest = { editing = false },
        title = { Text(label) },
        text = { OutlinedTextField(draft, { draft = it }, singleLine = true, isError = draft.toIntOrNull() !in range,
            supportingText = { Text("${range.first}–${range.last}") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
        confirmButton = { TextButton(enabled = draft.toIntOrNull() in range, onClick = { save(draft.toInt()); editing = false }) { Text(text.choose("Сохранить", "Save")) } },
        dismissButton = { TextButton(onClick = { editing = false }) { Text(text.choose("Отмена", "Cancel")) } },
    )
}

@Composable
internal fun PersonalMarkRow(mark: PersonalMark, text: UiText, restore: () -> Unit) {
    SettingsGroup("") {
        Text(mark.title.ifBlank { mark.gameKey }, style = MaterialTheme.typography.titleMedium)
        Text(storeName(mark.gameKey.substringBefore(':')), style = MaterialTheme.typography.bodySmall)
        val labels = listOfNotNull(
            text.claimed.takeIf { mark.claimed },
            text.choose("Игнорируется", "Ignored").takeIf { mark.ignored },
            text.choose("Раздача скрыта", "Giveaway hidden").takeIf { mark.hiddenCampaign.isNotBlank() },
            text.choose("Отложено до ${text.time(mark.snoozedUntil)}", "Snoozed until ${text.time(mark.snoozedUntil)}").takeIf { mark.snoozedUntil > System.currentTimeMillis() },
        )
        Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = restore) { Text(text.choose("Снять отметки", "Clear marks")) }
    }
}
