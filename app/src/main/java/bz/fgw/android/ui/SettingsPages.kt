package bz.fgw.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal data class SettingsDestination(val id: String, val title: String, val description: String, val icon: String)

internal fun settingsDestinations(text: UiText, version: String) = listOf(
    SettingsDestination("catalog", text.choose("Магазины и регион", "Stores and region"), text.choose("Steam, Epic, GOG · страна · отслеживание", "Steam, Epic, GOG · country · tracking"), "gift"),
    SettingsDestination("filters", text.choose("Фильтры раздач", "Giveaway filters"), text.choose("Типы · рейтинг · отзывы · жанры · платформы · исключения", "Types · ratings · reviews · genres · platforms · exclusions"), "filter"),
    SettingsDestination("display", text.choose("Вид и язык", "Appearance and language"), text.choose("Тема · компактный вид · сортировка · счётчик", "Theme · compact view · sorting · count"), "settings"),
    SettingsDestination("background", text.choose("Фоновая проверка", "Background checks"), text.choose("Интервал проверки магазинов", "Store check interval"), "refresh"),
    SettingsDestination("notifications", text.choose("Оповещения", "Notifications"), text.choose("Новые раздачи · напоминания · тихие часы", "New giveaways · reminders · quiet hours"), "clock"),
    SettingsDestination("history", text.history, text.choose("Срок хранения · очистка", "Retention · clear history"), "history"),
    SettingsDestination("marks", text.choose("Личные отметки", "Personal marks"), text.choose("Забранные · отложенные · скрытые · игнорируемые", "Claimed · snoozed · hidden · ignored"), "check"),
    SettingsDestination("backup", text.choose("Перенос данных", "Data transfer"), text.choose("Импорт и экспорт · браузерное расширение", "Import and export · browser extension"), "arrow"),
    SettingsDestination("help", text.choose("Справка", "Help"), text.choose("Начало работы · значки · действия", "Getting started · icons · actions"), "help"),
    SettingsDestination("diagnostics", text.choose("Диагностика источников", "Source diagnostics"), text.choose("Последние проверки · ошибки · отчёт", "Recent checks · errors · report"), "refresh"),
    SettingsDestination("privacy", text.choose("Данные и приватность", "Data and privacy"), text.choose("Хранение и обращения к сервисам", "Storage and service requests"), "info"),
    SettingsDestination("about", text.choose("О приложении", "About"), "Free Games Watcher · $version", "info"),
)

@Composable
internal fun SettingsLink(destination: SettingsDestination, open: () -> Unit) {
    Surface(onClick = open, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(destination.icon, null, Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(destination.title, style = MaterialTheme.typography.titleMedium)
                Text(destination.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AppIcon("chevron", null, Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun HelpSettings(text: UiText) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsGroup(text.choose("Начало работы", "Getting started")) {
            Text(text.choose("Выберите магазины и страну в настройках, затем нажмите обновление в ленте. Кнопка «В магазин» открывает страницу, где можно получить игру в своём аккаунте.",
                "Choose your stores and country in Settings, then refresh the feed. Open store takes you to the giveaway page, where you can claim the game with your store account."))
            Text(text.choose("Нажмите название игры для подробностей. Здесь доступны полный срок, обычная цена, жанры, платформы и оценки, если магазин их предоставляет.",
                "Tap a game title for details, including its deadline, regular price, genres, platforms and ratings when the store provides them."))
        }
        SettingsGroup(text.choose("Значки и личные отметки", "Icons and personal marks")) {
            MarkHelp(text)
            Text(text.choose("Отменить действие можно в сообщении после нажатия. Снять отметки позднее можно в настройках → Личные отметки. Отметки устанавливаются вручную и действуют в этом приложении.",
                "Undo an action in the message shown after tapping. To clear marks later, open Settings → Personal marks. Marks are set manually and apply within this app."))
        }
        SettingsGroup(text.choose("Лента и поиск", "Feed and search")) {
            Text(text.choose("Раздачи сгруппированы по магазинам. Сортировка действует внутри каждого магазина. По умолчанию первыми идут ближайшие окончания. Доступны также новые, по названию и рейтинг Steam. В режиме рейтинга Epic и GOG остаются отсортированы по срокам.",
                "Giveaways are grouped by store and sorted within each store. Ending soon is the default. You can also sort by newest, title or Steam rating. Epic and GOG keep their deadline order when Steam rating is selected."))
            Text(text.choose("Точка у названия обозначает непрочитанную раздачу. Поиск находит название или ID. Во вкладке «Скоро» показаны будущие акции, в «Истории» — сохранённые записи и личные отметки.",
                "A dot by the title marks an unread giveaway. Search matches titles or IDs. Upcoming shows future offers. History contains saved records and personal marks."))
            Text(text.choose("Фильтры типов, рейтинга, жанров, платформ и исключений находятся в отдельном разделе настроек. Они применяются к ленте и оповещениям. История помогает найти исключённые записи.",
                "Type, rating, genre, platform and exclusion filters have their own Settings page. They apply to the feed and notifications. History helps you find excluded records."))
        }
        SettingsGroup(text.choose("Уведомления и перенос", "Notifications and transfer")) {
            Text(text.choose("Разрешите уведомления Android и выберите оповещения о новых раздачах и окончании. Во время тихих часов оповещения откладываются до следующей проверки после их окончания. Android может задерживать фоновые проверки для экономии заряда.",
                "Allow Android notifications and choose new-giveaway and expiry alerts. Quiet hours defer eligible alerts until a check after quiet hours ends. Android may delay background checks to save battery."))
            Text(text.choose("Раздел «Перенос данных» импортирует и экспортирует JSON-копии Android-приложения и браузерного расширения. Можно перенести настройки, личные отметки и историю. Перед импортом показан состав копии.",
                "Data transfer imports and exports JSON backups from the Android app and browser extension. Transfer settings, personal marks and history. Review the backup contents before importing."))
        }
    }
}
