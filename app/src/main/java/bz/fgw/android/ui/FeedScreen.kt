package bz.fgw.android.ui

import android.app.TimePickerDialog
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bz.fgw.android.data.CheckStatus
import bz.fgw.android.data.FeedState
import bz.fgw.android.data.StoredOffer
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun FgwTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFB9CAFF),
        background = Color(0xFF11151E),
        surface = Color(0xFF191E29),
        secondaryContainer = Color(0xFF283448),
    ) else lightColorScheme(
        primary = Color(0xFF385BCE),
        background = Color(0xFFF6F7FB),
        surface = Color.White,
        secondaryContainer = Color(0xFFE7EDFF),
    )
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun FeedScreen(
    model: FeedViewModel,
    notificationsAvailable: Boolean,
    remindersAvailable: Boolean,
    onNotificationsChanged: (Boolean) -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenOffer: (StoredOffer) -> Unit,
    alert: String?,
    onDismissAlert: () -> Unit,
    notificationOffer: StoredOffer?,
    onDismissNotificationOffer: () -> Unit,
) {
    val state by model.state.collectAsStateWithLifecycle()
    val refreshing by model.isRefreshing.collectAsStateWithLifecycle()
    val failed by model.actionFailed.collectAsStateWithLifecycle()
    val text = remember(state.language) { UiText(state.language) }
    var tab by rememberSaveable { mutableStateOf("giveaways") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val selected = state.history.find { it.campaignId == selectedId }
    val offers = if (tab == "history") state.history.sortedByDescending { it.lastConfirmed }
        else state.offers.filter { !it.ended && (it.start == null || it.start <= now) && (it.end == null || it.end > now) }
    val openDetails: (StoredOffer) -> Unit = { selectedId = it.campaignId }
    BackHandler(enabled = selectedId != null || tab != "giveaways") {
        if (selectedId != null) selectedId = null else tab = "giveaways"
    }

    Scaffold { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val wide = maxWidth >= 600.dp
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Free Games Watcher", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf("giveaways" to text.giveaways, "history" to text.history, "settings" to text.settings).forEach { (key, label) ->
                        FilterChip(
                            selected = tab == key,
                            onClick = { tab = key; selectedId = null },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                when (tab) {
                    "settings" -> SettingsScreen(state, text, model, onNotificationsChanged, onOpenNotificationSettings)
                    else -> Row(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item {
                                Text(
                                    if (tab == "history") text.history else text.choose("Раздачи для ПК", "Free PC games"),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            if (tab != "history") {
                                item { Text(text.country(state.country), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                item { ScanCard(state, text, refreshing, model::refresh) }
                                if (state.notificationsEnabled && !notificationsAvailable) {
                                    item {
                                        Card {
                                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(text.systemBlocked)
                                                TextButton(onClick = onOpenNotificationSettings) { Text(text.systemSettings) }
                                            }
                                        }
                                    }
                                }
                                if (state.notificationsEnabled && notificationsAvailable && state.remindersEnabled && !remindersAvailable) {
                                    item {
                                        Card {
                                            Column(Modifier.padding(16.dp)) {
                                                Text(text.choose("Android блокирует напоминания", "Android is blocking expiry reminders"))
                                                TextButton(onClick = onOpenNotificationSettings) { Text(text.systemSettings) }
                                            }
                                        }
                                    }
                                }
                            }
                            if (offers.isEmpty()) {
                                item {
                                    val emptyTitle = when {
                                        tab == "history" -> text.choose("История пока пуста", "History is empty")
                                        state.selectedStores.isEmpty() -> text.noStores
                                        refreshing -> text.choose("Ищем бесплатные игры", "Finding free games")
                                        state.checks.any { it.error != null || !it.complete } -> text.choose("Проверка не завершена", "Check is incomplete")
                                        state.checks.isEmpty() -> text.firstCheck
                                        else -> text.choose("Активных раздач пока нет", "No active giveaways")
                                    }
                                    Text(emptyTitle, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 16.dp))
                                    if (state.selectedStores.isEmpty() && tab != "history") Text(text.noStoresDetail)
                                }
                            }
                            items(offers, key = { it.campaignId }) { offer ->
                                if (wide) {
                                    Card(onClick = { openDetails(offer) }, colors = CardDefaults.cardColors(
                                        containerColor = if (offer.campaignId == selectedId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                    )) {
                                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(storeName(offer.store), style = MaterialTheme.typography.labelLarge)
                                            Text(offer.title, style = MaterialTheme.typography.titleLarge)
                                            Text(status(offer, now, offer.gameKey in state.claimedIds, text))
                                        }
                                    }
                                } else OfferCard(offer, text, now, offer.gameKey in state.claimedIds, tab == "history", offer.store in state.selectedStores, onOpenOffer, model::setClaimed)
                            }
                            item {
                                Text(
                                    if (tab == "history") text.choose("Отметка «Уже забрал» сохраняется для игры при повторных раздачах.", "Claimed status stays with the game across future giveaways.")
                                    else text.choose("Получите игру в своём аккаунте магазина до окончания акции.", "Claim the game in your store account before the offer ends."),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 24.dp),
                                )
                            }
                        }
                        if (wide) {
                            val detail = selected ?: offers.firstOrNull()
                            Box(Modifier.weight(1f).fillMaxHeight().padding(20.dp)) {
                                if (detail != null) Column(Modifier.verticalScroll(rememberScrollState())) {
                                    OfferCard(detail, text, now, detail.gameKey in state.claimedIds, true, detail.store in state.selectedStores, onOpenOffer, model::setClaimed)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (notificationOffer != null) {
        val current = state.history.find { it.campaignId == notificationOffer.campaignId } ?: notificationOffer
        AlertDialog(
            onDismissRequest = onDismissNotificationOffer,
            title = { Text(current.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(storeName(current.store))
                    Text(status(current, now, current.gameKey in state.claimedIds, text))
                    Text(current.end?.let(text::deadline) ?: text.noDeadline)
                    TextButton(onClick = { model.setClaimed(current, current.gameKey !in state.claimedIds) }) {
                        Text(if (current.gameKey in state.claimedIds) text.undoClaim else text.claimed)
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismissNotificationOffer) { Text(text.close) } },
        )
    }
    if (alert != null || failed) {
        AlertDialog(
            onDismissRequest = { onDismissAlert(); model.dismissMessage() },
            text = { Text(alert ?: text.actionFailed) },
            confirmButton = { TextButton(onClick = { onDismissAlert(); model.dismissMessage() }) { Text(text.okay) } },
        )
    }
}

@Composable
private fun ScanCard(state: FeedState, text: UiText, refreshing: Boolean, refresh: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (refreshing) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(text.refreshing)
            }
            state.selectedStores.sorted().forEach { store ->
                val check = state.checks.find { it.store == store }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(storeName(store), style = MaterialTheme.typography.titleSmall)
                    Text(check?.lastSuccess?.let(text::lastSuccess) ?: text.firstCheck, style = MaterialTheme.typography.bodySmall)
                    if (check?.error != null || check?.complete == false) {
                        Text(
                            if (check.error != null && check.error != "partial") text.sourceError else text.partial,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (state.offers.any { it.store == store }) Text(text.cached, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(onClick = refresh, enabled = !refreshing && state.selectedStores.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(text.refresh)
            }
        }
    }
}

@Composable
private fun OfferCard(
    offer: StoredOffer,
    text: UiText,
    now: Long,
    claimed: Boolean,
    showCountry: Boolean,
    loadCover: Boolean,
    open: (StoredOffer) -> Unit,
    mark: (StoredOffer, Boolean) -> Unit,
) {
    val ended = offer.ended || (offer.end != null && offer.end <= now)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loadCover) OfferCover(offer.store, offer.imageUrl, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
            Text(storeName(offer.store) + if (showCountry) " · ${text.country(offer.country)}" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(offer.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(status(offer, now, claimed, text), style = MaterialTheme.typography.titleSmall)
            Text(offer.end?.let(text::deadline) ?: text.noDeadline)
            if (now - offer.lastConfirmed > 6 * 60 * 60 * 1000L && !ended) {
                Text(text.choose("Данные устарели. Проверьте доступность в магазине.", "Saved data is old. Check availability in the store."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { open(offer) }, enabled = !ended, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(text.openStore)
            }
            TextButton(onClick = { mark(offer, !claimed) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (claimed) text.undoClaim else text.claimed)
            }
        }
    }
}

private fun status(offer: StoredOffer, now: Long, claimed: Boolean, text: UiText): String {
    val lifecycle = if (offer.ended || (offer.end != null && offer.end <= now)) text.ended
        else text.choose("Бесплатно навсегда", "Free to keep")
    return if (claimed) "$lifecycle · ${text.claimed}" else lifecycle
}

@Composable
private fun SettingsScreen(
    state: FeedState,
    text: UiText,
    model: FeedViewModel,
    notificationChange: (Boolean) -> Unit,
    notificationSettings: () -> Unit,
) {
    var countryDialog by remember { mutableStateOf(false) }
    var quietError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val pickTime: (Int, (Int) -> Unit) -> Unit = { minute, save ->
        val localizedContext = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Dialog_Alert).apply {
            applyOverrideConfiguration(Configuration(configuration).apply { setLocale(text.locale) })
        }
        TimePickerDialog(localizedContext, { _, hour, minutes -> save(hour * 60 + minutes) }, minute / 60, minute % 60, true).show()
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text.settings, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        item {
            SettingsGroup(text.choose("Каталог", "Catalog")) {
                Text(text.choose("Магазины", "Stores"), style = MaterialTheme.typography.titleSmall)
                listOf("steam", "epic", "gog").forEach { store ->
                    ToggleRow(storeName(store), store in state.selectedStores) { enabled ->
                        model.setStore(store, enabled)
                    }
                }
                if (state.selectedStores.isEmpty()) Text(text.noStoresDetail)
                OutlinedButton(onClick = { countryDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(text.choose("Страна · ", "Country · ") + text.country(state.country))
                }
                Text(text.choose("Доступность зависит от страны вашего аккаунта магазина.", "Availability depends on your store account's country."), style = MaterialTheme.typography.bodySmall)
                Text(text.choose("Язык приложения", "App language"), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ru" to "Русский", "en" to "English").forEach { (code, label) ->
                        FilterChip(selected = state.language == code, onClick = { model.setLanguage(code) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
        item {
            SettingsGroup(text.choose("Фоновая проверка", "Background checks")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 3, 6, 12).forEach { hours ->
                        FilterChip(selected = state.intervalHours == hours, onClick = { model.setInterval(hours) }, label = { Text(text.hour(hours)) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text(text.choose("Проверка выполняется при подключении к интернету. Android может отложить запуск для экономии заряда.", "Checks need an internet connection. Android may delay them to save battery."), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SettingsGroup(text.choose("Оповещения", "Notifications")) {
                ToggleRow(text.choose("Новые раздачи", "New giveaways"), state.notificationsEnabled, notificationChange)
                ToggleRow(text.choose("Напоминание об окончании", "Expiry reminder"), state.remindersEnabled, model::setReminders)
                Text(text.choose("Одно напоминание в последние 24 часа акции при ближайшей проверке. Для неизвестного срока напоминание отключено.", "One reminder during the final 24 hours at the next check. Offers with an unknown end date have no reminder."), style = MaterialTheme.typography.bodySmall)
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
                TextButton(onClick = notificationSettings) { Text(text.systemSettings) }
            }
        }
        item {
            SettingsGroup(text.choose("Состояние источников", "Source diagnostics")) {
                state.selectedStores.sorted().forEach { store ->
                    SourceDiagnostics(store, state.checks.find { it.store == store }, text)
                }
                if (state.selectedStores.isEmpty()) Text(text.noStores)
            }
        }
        item {
            SettingsGroup(text.choose("Данные и приватность", "Data and privacy")) {
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
        item { Box(Modifier.padding(bottom = 12.dp)) }
    }
    if (countryDialog) CountryDialog(text, state.country, { model.setCountry(it); countryDialog = false }, { countryDialog = false })
    if (quietError) {
        AlertDialog(
            onDismissRequest = { quietError = false },
            text = { Text(text.choose("Начало и окончание тихих часов должны различаться.", "Quiet hours must have different start and end times.")) },
            confirmButton = { TextButton(onClick = { quietError = false }) { Text(text.okay) } },
        )
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, change: (Boolean) -> Unit) {
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

@Composable
private fun CountryDialog(text: UiText, selected: String, choose: (String) -> Unit, dismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val countries = remember(text.language) { Locale.getISOCountries().sortedBy(text::country) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(text.choose("Страна каталога", "Catalog country")) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, singleLine = true, label = { Text(text.choose("Поиск страны или кода", "Search name or code")) })
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(countries.filter { it.contains(query, true) || text.country(it).contains(query, true) }) { code ->
                        TextButton(onClick = { choose(code) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Text(text.country(code) + " ($code)" + if (selected == code) " ✓" else "")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text(text.close) } },
    )
}

private fun clockTime(minute: Int) = "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60)
