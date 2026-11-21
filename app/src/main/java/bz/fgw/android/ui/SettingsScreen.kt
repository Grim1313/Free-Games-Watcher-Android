package bz.fgw.android.ui

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import bz.fgw.android.data.FilterPreferences
import bz.fgw.android.data.CheckStatus
import bz.fgw.android.data.FeedState
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    state: FeedState,
    text: UiText,
    model: FeedViewModel,
    notificationsAvailable: Boolean,
    refreshing: Boolean,
    notificationChange: (Boolean) -> Unit,
    notificationSettings: () -> Unit,
    initialPage: String? = null,
) {
    var page by rememberSaveable(initialPage) { mutableStateOf(initialPage) }
    var search by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = page != null) { page = if (page == "country") "catalog" else null }
    var quietError by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var testBusy by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var reportCopied by remember { mutableStateOf<Boolean?>(null) }
    val version = remember(context) {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    }
    val uriHandler = LocalUriHandler.current
    val configuration = LocalConfiguration.current
    val pickTime: (Int, (Int) -> Unit) -> Unit = { minute, save ->
        val localizedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).apply {
            applyOverrideConfiguration(Configuration(configuration).apply { setLocale(text.locale) })
        }
        TimePickerDialog(localizedContext, { _, hour, minutes -> save(hour * 60 + minutes) }, minute / 60, minute % 60, true).show()
    }
    val destinations = remember(text, version) { settingsDestinations(text, version) }
    var countryQuery by rememberSaveable { mutableStateOf("") }
    val countries = remember(page, text.language) {
        if (page == "country") Locale.getISOCountries().sortedBy(text::country) else emptyList()
    }
    var markQuery by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (page != null) IconButton(onClick = { page = if (page == "country") "catalog" else null }) {
                AppIcon("back", label = text.back)
            }
            Text(
                if (page == "country") text.choose("Страна каталога", "Catalog country")
                else destinations.find { it.id == page }?.title ?: text.settings,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            state = androidx.compose.runtime.key(page) { androidx.compose.foundation.lazy.rememberLazyListState() },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (page == null) {
                item {
                    OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(text.choose("Поиск настроек", "Search settings")) },
                        leadingIcon = { AppIcon("search") })
                }
                val matches = destinations.filter { it.matches(search) }
                if (matches.isEmpty()) item {
                    Text(text.choose("Настройки не найдены", "No matching settings"), modifier = Modifier.padding(vertical = 8.dp))
                }
                items(matches, key = { it.id }) { destination ->
                    SettingsLink(destination) { page = destination.id }
                }
                if (destinations.none { it.title.contains(search, true) || it.description.contains(search, true) }) item {
                    Text(text.choose("Настройки не найдены. Измените поисковый запрос.", "No settings found. Try another search."))
                }
            }

            if (page == "catalog") item {
                SettingsGroup("") {
                    ToggleRow(text.choose("Отслеживать раздачи", "Track giveaways"), state.settings.trackFreeToKeep, model::setTracking)
                    Text(text.choose("Магазины", "Stores"), style = MaterialTheme.typography.titleSmall)
                    listOf("steam", "epic", "gog").forEach { store ->
                        ToggleRow(storeName(store), store in state.selectedStores) { enabled ->
                            model.setStore(store, enabled)
                        }
                    }
                    if (state.selectedStores.isEmpty()) Text(text.noStoresDetail)
                    OutlinedButton(onClick = { page = "country" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text.choose("Страна · ", "Country · ") + text.country(state.country))
                    }
                    Text(text.choose("Доступность зависит от страны вашего аккаунта магазина.", "Availability depends on your store account's country."), style = MaterialTheme.typography.bodySmall)

                }
            }
            if (page == "background") item {
                SettingsGroup("") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(15, 30, 60, 180, 360, 720, 1440).forEach { minutes ->
                            FilterChip(selected = state.settings.intervalMinutes == minutes, onClick = { model.setIntervalMinutes(minutes) },
                                label = { Text(if (minutes < 60) text.choose("$minutes мин", "$minutes min") else text.choose("${minutes / 60} ч", "${minutes / 60} h")) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Text(text.choose("Проверка выполняется при подключении к интернету. Android может отложить запуск для экономии заряда.", "Checks need an internet connection. Android may delay them to save battery."), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (page == "notifications") item {
                SettingsGroup("") {
                    ToggleRow(text.choose("Новые раздачи", "New giveaways"), state.notificationsEnabled, notificationChange)
                    ToggleRow(text.choose("Напоминание об окончании", "Expiry reminder"), state.remindersEnabled, model::setReminders)
                    if (state.remindersEnabled) {
                        NumberPreference(text.choose("За сколько часов напомнить", "Reminder lead time in hours"), state.settings.reminderHours, 1..72, text, model::setReminderHours)
                    }
                    Text(text.choose("Напоминание приходит при ближайшей проверке в выбранный период до окончания. Требуется известный срок раздачи.", "A reminder arrives at the next check within the selected period before the known deadline."), style = MaterialTheme.typography.bodySmall)
                    ToggleRow(text.choose("Повторять оповещения после срока хранения", "Allow alerts again after retention"), state.settings.renotifyAfterRetention) {
                        model.setRepeatAlerts(it, state.settings.notifiedRetentionDays)
                    }
                    NumberPreference(text.choose("Хранить отправленные оповещения, дней", "Remember sent alerts, days"), state.settings.notifiedRetentionDays, 1..365, text) {
                        model.setRepeatAlerts(state.settings.renotifyAfterRetention, it)
                    }
                    ToggleRow(text.choose("Тихие часы", "Quiet hours"), state.quietHoursEnabled) {
                        model.setQuietHours(it, state.quietStartMinute, state.quietEndMinute)
                    }
                    if (state.quietHoursEnabled) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                pickTime(state.quietStartMinute) {
                                    if (it == state.quietEndMinute) quietError = true
                                    else model.setQuietHours(true, it, state.quietEndMinute)
                                }
                            }) { Text(text.choose("С ", "From ") + clockTime(state.quietStartMinute)) }
                            OutlinedButton(onClick = {
                                pickTime(state.quietEndMinute) {
                                    if (it == state.quietStartMinute) quietError = true
                                    else model.setQuietHours(true, state.quietStartMinute, it)
                                }
                            }) { Text(text.choose("До ", "Until ") + clockTime(state.quietEndMinute)) }
                        }
                        Text(text.choose("Местное время устройства. Актуальные оповещения придут при проверке после тихих часов.", "Uses the device's local time. Eligible notifications arrive at a check after quiet hours."), style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(enabled = notificationsAvailable && !testBusy, onClick = {
                        scope.launch {
                            testBusy = true
                            testResult = null
                            try {
                                testResult = if (model.testNotification()) "sent" else "blocked"
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                testResult = "failed"
                            } finally {
                                testBusy = false
                            }
                        }
                    }, modifier = Modifier.fillMaxWidth()) { Text(text.choose("Проверить уведомления", "Test notification")) }
                    if (!notificationsAvailable) Text(text.choose("Для проверки разрешите уведомления в настройках Android.", "Allow notifications in Android settings to run the test."), style = MaterialTheme.typography.bodySmall)
                    testResult?.let { result ->
                        Text(when (result) {
                            "sent" -> text.choose("Тестовое уведомление отправлено.", "Test notification sent.")
                            "blocked" -> text.choose("Android блокирует тестовое уведомление. Проверьте разрешение и канал уведомлений.", "Android blocked the test notification. Check the permission and notification channel.")
                            else -> text.choose("Не удалось отправить тестовое уведомление.", "Could not send the test notification.")
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = notificationSettings) { Text(text.systemSettings) }
                }
            }
            if (page == "display") item {
                SettingsGroup("") {
                    Text(text.choose("Язык приложения", "App language"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to text.choose("Системный", "System"), "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                            FilterChip(selected = state.settings.language == code, onClick = { model.setLanguage(code) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                    Text(text.choose("Тема", "Theme"), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("system" to text.choose("Системная", "System"), "light" to text.choose("Светлая", "Light"), "dark" to text.choose("Тёмная OLED", "OLED dark")).forEach { (key, label) ->
                            FilterChip(selected = state.settings.theme == key, onClick = { model.setTheme(key) }, label = { Text(label) })
                        }
                    }
                    ToggleRow(text.choose("Компактный вид", "Compact view"), state.settings.compactView, model::setCompact)
                    ToggleRow(text.choose("Счётчик активных раздач", "Active giveaway count"), state.settings.badgeEnabled, model::setBadge)
                    SortSelector(state.settings.sortBy, text, model::setSort)
                    TextButton(onClick = model::markAllRead) {
                        Text(text.choose("Отметить все прочитанными", "Mark all as read"))
                    }
                }
            }
            if (page == "history") item {
                SettingsGroup("") {
                    ToggleRow(text.choose("Сохранять историю", "Keep history"), state.settings.historyEnabled) {
                        model.setHistory(it, state.settings.historyRetentionDays)
                    }
                    NumberPreference(text.choose("Хранить историю, дней", "History retention, days"), state.settings.historyRetentionDays, 1..365, text) {
                        model.setHistory(state.settings.historyEnabled, it)
                    }
                    Text(text.choose("При отключении завершённые записи удаляются после следующей проверки. Личные отметки хранятся отдельно.",
                        "When disabled, ended records are removed after the next check. Personal marks are stored separately."), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { confirmClear = "history" }) { Text(text.choose("Очистить историю", "Clear history")) }
                }
            }
            if (page == "marks") {
                item {
                    Text(text.choose("Сохранено отметок · ${state.marks.size}", "Saved marks · ${state.marks.size}"))
                    OutlinedTextField(markQuery, { markQuery = it }, singleLine = true,
                        label = { Text(text.choose("Поиск игры", "Search games")) }, modifier = Modifier.fillMaxWidth())
                    if (state.marks.isNotEmpty()) TextButton(onClick = { confirmClear = "marks" }) {
                        Text(text.choose("Снять все личные отметки", "Clear all personal marks"))
                    }
                }
                items(state.marks.filter { it.title.contains(markQuery, true) || it.gameKey.contains(markQuery, true) }, key = { it.gameKey }) { mark ->
                    PersonalMarkRow(mark, text) { model.restoreMark(mark.gameKey) }
                }
            }
            if (page == "filters") item {
                FilterSettings(FilterPreferences.fromJson(state.settings.filtersJson), text) { model.setFilters(it); page = null }
            }
            if (page == "country") {
                item {
                    OutlinedTextField(countryQuery, { countryQuery = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text(text.choose("Поиск страны или кода", "Search name or code")) })
                }
                items(countries.filter { it.contains(countryQuery, true) || text.country(it).contains(countryQuery, true) }, key = { it }) { code ->
                    TextButton(onClick = { model.setCountry(code); countryQuery = ""; page = "catalog" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(text.country(code) + " ($code)" + if (state.country == code) " ✓" else "")
                    }
                }
            }
            if (page == "backup") item { BackupSection(state.language, model::exportBackup, model::importBackup) }
            if (page == "help") item { HelpSettings(text) }
            if (page == "diagnostics") item {
                SettingsGroup("") {
                    Button(onClick = model::refresh, enabled = !refreshing && state.settings.trackFreeToKeep && state.selectedStores.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Text(if (refreshing) text.refreshing else text.refresh)
                    }
                    OutlinedButton(onClick = {
                        reportCopied = runCatching {
                            context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("Free Games Watcher diagnostics", buildDiagnosticsReport(state, version, System.currentTimeMillis())),
                            )
                        }.isSuccess
                    }, modifier = Modifier.fillMaxWidth()) { Text(text.choose("Скопировать отчёт", "Copy report")) }
                    reportCopied?.let { copied -> Text(if (copied) text.choose("Отчёт скопирован в буфер обмена.", "Report copied to clipboard.")
                        else text.choose("Не удалось скопировать отчёт.", "Could not copy the report."), style = MaterialTheme.typography.bodySmall) }
                    state.selectedStores.sorted().forEach { store ->
                        SourceDiagnostics(store, state.checks.find { it.store == store }, text)
                    }
                    if (state.selectedStores.isEmpty()) Text(text.noStores)
                }
            }
            if (page == "privacy") item {
                SettingsGroup("") {
                    Text(text.choose(
                        "История, отметки и настройки хранятся на устройстве. Приложение обращается к выбранным магазинам без входа в аккаунт.",
                        "History, claimed status and settings stay on your device. The app contacts selected stores without signing in.",
                    ))
                    Text(text.choose(
                        "Для Epic используются недельная лента и каталог. Если результат неполный, приложение обращается к EGData. Этот сервис получает IP-адрес, выбранную страну и параметры запроса.",
                        "Epic checks use the weekly feed and catalog. If their result is incomplete, the app contacts EGData. This service receives your IP address, selected country and request parameters.",
                    ), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (page == "about") item {
                SettingsGroup("") {
                    Text("Free Games Watcher · $version", style = MaterialTheme.typography.titleMedium)
                    Text(text.choose("Раздачи игр для ПК со скидкой 100%. Отметка «Уже забрал» устанавливается вручную после получения игры в магазине.",
                        "Limited-time PC game giveaways with 100% discounts. Mark a game as claimed after adding it to your store account."))
                    TextButton(onClick = { uriHandler.openUri("https://github.com/Grim1313/Free-Games-Watcher-Android/releases") }) {
                        Text(text.choose("Версии на GitHub", "Versions on GitHub"))
                    }
                    TextButton(onClick = { uriHandler.openUri("https://fgw.bz/") }) { Text(text.choose("Сайт и браузерное расширение", "Website and browser extension")) }
                    TextButton(onClick = { confirmClear = "settings" }) { Text(text.choose("Сбросить настройки", "Reset settings")) }
                }
            }
            item { Box(Modifier.padding(bottom = 12.dp)) }
        }
    }
    if (confirmClear != null) AlertDialog(
        onDismissRequest = { confirmClear = null },
        text = { Text(when (confirmClear) {
            "history" -> text.choose("Удалить завершённые записи из истории?", "Delete ended records from history?")
            "settings" -> text.choose("Вернуть настройки по умолчанию? Личные отметки и сохранённые игры останутся.", "Restore default settings? Personal marks and saved games will be kept.")
            else -> text.choose("Снять все личные отметки? Игры снова смогут появляться в ленте и уведомлениях.", "Clear all personal marks? Games can appear in the feed and notifications again.")
        }) },
        confirmButton = { TextButton(onClick = {
            when (confirmClear) {
                "history" -> model.clearHistory()
                "settings" -> model.resetSettings()
                else -> model.clearMarks()
            }
            confirmClear = null
        }) { Text(text.choose("Подтвердить", "Confirm")) } },
        dismissButton = { TextButton(onClick = { confirmClear = null }) { Text(text.choose("Отмена", "Cancel")) } },
    )
    if (quietError) {
        AlertDialog(
            onDismissRequest = { quietError = false },
            text = { Text(text.choose("Начало и окончание тихих часов должны различаться.", "Quiet hours must have different start and end times.")) },
            confirmButton = { TextButton(onClick = { quietError = false }) { Text(text.okay) } },
        )
    }
}

@Composable
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title.isNotEmpty()) Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
internal fun ToggleRow(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = value, role = Role.Switch, onValueChange = change),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = null)
    }
}

@Composable
private fun SourceDiagnostics(store: String, check: CheckStatus?, text: UiText) {
    var expanded by rememberSaveable(store) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(storeName(store), style = MaterialTheme.typography.titleMedium)
        Text(check?.lastSuccess?.let(text::lastSuccess) ?: text.firstCheck)
        if (check != null) {
            Text(when {
                check.error == "partial" -> text.partial
                check.error != null -> text.sourceError
                !check.complete -> text.partial
                else -> text.success
            })
            Text(text.choose("Запросов при последней проверке · ${check.requestCount}", "Requests in last check · ${check.requestCount}"), style = MaterialTheme.typography.bodySmall)
            check.retryAt?.let { Text(text.choose("Повтор после ${text.time(it)}", "Retry after ${text.time(it)}"), style = MaterialTheme.typography.bodySmall) }
            if (!check.details.isBlank() || check.error != null) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) text.choose("Скрыть подробности", "Hide details") else text.choose("Технические подробности", "Technical details"))
                }
                if (expanded) Text(
                    listOfNotNull(
                        check.error?.takeUnless { it == "failed" || it == "partial" },
                        check.details.takeIf { it.isNotBlank() },
                    ).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        HorizontalDivider()
    }
}


private fun clockTime(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
