package bz.fgw.android.ui

import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bz.fgw.android.data.FilterPreferences
import bz.fgw.android.data.PersonalMark
import bz.fgw.android.data.sortOffers
import bz.fgw.android.data.CheckStatus
import bz.fgw.android.data.FeedState
import bz.fgw.android.data.StoredOffer
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var query by rememberSaveable(tab) { mutableStateOf("") }
    var storeFilter by rememberSaveable(tab) { mutableStateOf("all") }
    var historyFilter by rememberSaveable(tab) { mutableStateOf("all") }
    val preferences = remember(state.settings.filtersJson) { FilterPreferences.fromJson(state.settings.filtersJson) }
    val marks = remember(state.marks) { state.marks.associateBy { it.gameKey } }
    val activeCount = state.offers.count { it.activeAt(now) && preferences.accepts(it) && marks[it.gameKey]?.suppresses(it, now) != true }
    val source = when (tab) {
        "history" -> state.history
        "upcoming" -> state.upcoming.filter { it.start != null && it.start > now && preferences.accepts(it) && marks[it.gameKey]?.suppresses(it, now) != true }
        else -> state.offers.filter { it.activeAt(now) && preferences.accepts(it) && marks[it.gameKey]?.suppresses(it, now) != true }
    }
    val offers = sortOffers(source.filter { offer ->
        (storeFilter == "all" || offer.store == storeFilter) &&
            (query.isBlank() || offer.title.contains(query.trim(), true) || offer.id.contains(query.trim(), true)) &&
            (tab != "history" || when (historyFilter) {
                "active" -> offer.activeAt(now)
                "ended" -> offer.ended || (offer.end != null && offer.end <= now)
                "claimed" -> marks[offer.gameKey]?.claimed == true
                "ignored" -> marks[offer.gameKey]?.ignored == true
                "hidden" -> marks[offer.gameKey]?.hiddenCampaign == offer.campaignId
                "snoozed" -> (marks[offer.gameKey]?.snoozedUntil ?: 0) > now
                else -> true
            })
    }, state.settings.sortBy).let { if (tab == "upcoming") it.sortedBy { offer -> offer.start ?: Long.MAX_VALUE } else it }
    val selected = offers.find { it.campaignId == selectedId }
    val openDetails: (StoredOffer) -> Unit = { selectedId = it.campaignId }
    BackHandler(enabled = selectedId != null || tab != "giveaways") {
        if (selectedId != null) selectedId = null else tab = "giveaways"
    }

    Scaffold { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            val wide = maxWidth >= 840.dp
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
                    listOf("giveaways" to if (state.settings.badgeEnabled) "${text.giveaways} ($activeCount)" else text.giveaways, "upcoming" to text.choose("Скоро", "Upcoming"), "history" to text.history, "settings" to text.settings).forEach { (key, label) ->
                        FilterChip(
                            selected = tab == key,
                            onClick = { tab = key; selectedId = null },
                            label = { Text(label) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
                when (tab) {
                    "settings" -> SettingsScreen(state, text, model, notificationsAvailable, refreshing, onNotificationsChanged, onOpenNotificationSettings)
                    else -> Row(Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(horizontal = 20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            item {
                                Text(
                                    when (tab) { "history" -> text.history; "upcoming" -> text.choose("Будущие раздачи Epic", "Upcoming Epic giveaways"); else -> text.choose("Раздачи для ПК", "Free PC games") },
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 12.dp),
                                )
                            }
                            if (tab == "giveaways") {
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
                            item {
                                OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    label = { Text(text.choose("Поиск по названию или ID", "Search title or ID")) })
                                if (tab != "upcoming") FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    (listOf("all") + listOf("steam", "epic", "gog")).forEach { store ->
                                        FilterChip(selected = storeFilter == store, onClick = { storeFilter = store },
                                            label = { Text(if (store == "all") text.choose("Все", "All") else storeName(store)) })
                                    }
                                }
                                if (tab != "upcoming") SortSelector(state.settings.sortBy, text, model::setSort)
                                if (tab == "history") {
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf("all", "active", "ended", "claimed", "ignored", "hidden", "snoozed").forEach { status ->
                                            FilterChip(selected = historyFilter == status, onClick = { historyFilter = status },
                                                label = { Text(historyStatusLabel(status, text)) })
                                        }
                                    }
                                }
                                if (tab == "giveaways" && offers.any { !it.isRead }) {
                                    TextButton(onClick = model::markAllRead) { Text(text.choose("Отметить все прочитанными", "Mark all as read")) }
                                }
                            }
                            if (offers.isEmpty()) {
                                item {
                                    val emptyTitle = when {
                                        query.isNotBlank() || storeFilter != "all" || (tab == "history" && historyFilter != "all") -> text.choose("Ничего не найдено", "No matches")
                                        tab == "history" -> text.choose("История пока пуста", "History is empty")
                                        tab == "upcoming" -> text.choose("Будущие раздачи пока не объявлены", "No upcoming giveaways announced")
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
                                            Text(status(offer, now, marks[offer.gameKey]?.claimed == true, text))
                                            OfferMetadata(offer, text)
                                        }
                                    }
                                } else OfferCard(offer, text, now, marks[offer.gameKey], tab == "history", offer.store in state.selectedStores && !state.settings.compactView, onOpenOffer, model::act)
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
                                    OfferCard(detail, text, now, marks[detail.gameKey], true, detail.store in state.selectedStores && !state.settings.compactView, onOpenOffer, model::act)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (notificationOffer != null) {
        val current = (state.offers + state.history + state.upcoming).find { it.campaignId == notificationOffer.campaignId } ?: notificationOffer
        AlertDialog(
            onDismissRequest = onDismissNotificationOffer,
            title = { Text(current.title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(storeName(current.store))
                    Text(status(current, now, marks[current.gameKey]?.claimed == true, text))
                    Text(current.end?.let(text::deadline) ?: text.noDeadline)
                    TextButton(onClick = { model.act(current, if (marks[current.gameKey]?.claimed == true) "unclaim" else "claim") }) {
                        Text(if (marks[current.gameKey]?.claimed == true) text.undoClaim else text.claimed)
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
            if (!state.settings.trackFreeToKeep) Text(text.choose("Отслеживание приостановлено. Включите его в настройках.", "Tracking is paused. Resume it in Settings."))
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
            Button(onClick = refresh, enabled = !refreshing && state.selectedStores.isNotEmpty() && state.settings.trackFreeToKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
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
    mark: PersonalMark?,
    showCountry: Boolean,
    loadCover: Boolean,
    open: (StoredOffer) -> Unit,
    action: (StoredOffer, String) -> Unit,
) {
    var actionsOpen by remember(offer.campaignId) { mutableStateOf(false) }
    val ended = offer.ended || (offer.end != null && offer.end <= now)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loadCover) OfferCover(offer.store, offer.imageUrl, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
            Text(storeName(offer.store) + if (showCountry) " · ${text.country(offer.country)}" else "", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(offer.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (!offer.isRead && !offer.upcoming) TextButton(onClick = { action(offer, "read") }) { Text(text.choose("Новое · отметить прочитанным", "New · mark as read")) }
            Text(status(offer, now, mark?.claimed == true, text), style = MaterialTheme.typography.titleSmall)
            if (offer.start != null && offer.start > now) Text(text.choose("Начнётся ${text.time(offer.start)}", "Starts ${text.time(offer.start)}"))
            Text(offer.end?.let(text::deadline) ?: text.noDeadline)
            OfferMetadata(offer, text)
            if (offer.imported) Text(text.choose("Импортированная запись. Доступность подтвердится при проверке магазина.", "Imported record. A store check will confirm availability."), style = MaterialTheme.typography.bodySmall)
            if (mark?.ignored == true) Text(text.choose("Игра игнорируется", "Game ignored"))
            if (mark?.hiddenCampaign == offer.campaignId) Text(text.choose("Эта раздача скрыта", "This giveaway is hidden"))
            mark?.snoozedUntil?.takeIf { it > now }?.let { until ->
                Text(text.choose("Отложено до ${text.time(until)}", "Snoozed until ${text.time(until)}"))
            }
            if (now - offer.lastConfirmed > 6 * 60 * 60 * 1000L && !ended) {
                Text(text.choose("Данные устарели. Проверьте доступность в магазине.", "Saved data is old. Check availability in the store."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { open(offer); action(offer, "read") }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(text.openStore)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { action(offer, if (mark?.claimed == true) "unclaim" else "claim") }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (mark?.claimed == true) text.undoClaim else text.claimed)
                }
                Box {
                    TextButton(onClick = { actionsOpen = true }) { Text(text.choose("Ещё", "More")) }
                    DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                        val actions = listOf("snooze" to text.choose("Отложить на 24 часа", "Snooze for 24 hours"),
                            "hide" to text.choose("Скрыть эту раздачу", "Hide this giveaway"),
                            "ignore" to text.choose("Игнорировать игру", "Ignore this game"),
                            "restore" to text.choose("Снять все отметки", "Clear personal marks"))
                        actions.forEach { (key, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = { actionsOpen = false; action(offer, key) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferMetadata(offer: StoredOffer, text: UiText) {
    val price = formatOfferPrice(offer, text.locale)
    if (price != null) Text(text.choose("Обычная цена $price · скидка 100%", "Regular price $price · 100% off"), style = MaterialTheme.typography.bodyMedium)
    val rating = when {
        offer.store == "steam" && offer.ratingPercent != null -> text.choose("${offer.ratingPercent}% положительных отзывов", "${offer.ratingPercent}% positive reviews")
        offer.ratingValue != null -> "${String.format(text.locale, "%.1f", offer.ratingValue)} / 5"
        else -> null
    }
    if (rating != null) Text(rating + (offer.reviewCount?.let { text.choose(" · отзывов $it", " · $it reviews") } ?: ""), style = MaterialTheme.typography.bodyMedium)
    val type = when (offer.contentType) {
        "dlc" -> "DLC"
        "soundtrack" -> text.choose("Саундтрек", "Soundtrack")
        "demo" -> text.choose("Демо", "Demo")
        "tool" -> text.choose("Инструмент", "Tool")
        "package" -> text.choose("Набор", "Package")
        else -> null
    }
    val metadata = listOfNotNull(type, offer.genres.takeIf { it.isNotEmpty() }?.joinToString(", "),
        offer.platforms.takeIf { it.isNotEmpty() }?.joinToString(" / ") { when (it) { "mac" -> "macOS"; "windows" -> "Windows"; "linux" -> "Linux"; else -> it } })
    if (metadata.isNotEmpty()) Text(metadata.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun status(offer: StoredOffer, now: Long, claimed: Boolean, text: UiText): String {
    val lifecycle = when {
        offer.ended || (offer.end != null && offer.end <= now) -> text.ended
        offer.imported -> text.choose("Ожидает проверки", "Awaiting verification")
        offer.start != null && offer.start > now -> text.choose("Скоро", "Upcoming")
        else -> text.choose("Бесплатно навсегда", "Free to keep")
    }
    return if (claimed) "$lifecycle · ${text.claimed}" else lifecycle
}

private fun historyStatusLabel(value: String, text: UiText): String = when (value) {
    "active" -> text.choose("Активные", "Active")
    "ended" -> text.choose("Завершённые", "Ended")
    "claimed" -> text.claimed
    "ignored" -> text.choose("Игнорируемые", "Ignored")
    "hidden" -> text.choose("Скрытые", "Hidden")
    "snoozed" -> text.choose("Отложенные", "Snoozed")
    else -> text.choose("Все статусы", "All statuses")
}

@Composable
private fun SortSelector(sort: String, text: UiText, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf("ending" to text.choose("По окончанию", "Ending soon"), "newest" to text.choose("По дате обнаружения", "Recently found"),
        "rating" to text.choose("По рейтингу", "Highest rated"), "title" to text.choose("По названию", "Title"))
    Box {
        TextButton(onClick = { expanded = true }) { Text(choices.find { it.first == sort }?.second ?: choices.first().second) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; change(key) }) }
        }
    }
}

@Composable
private fun SettingsScreen(
    state: FeedState,
    text: UiText,
    model: FeedViewModel,
    notificationsAvailable: Boolean,
    refreshing: Boolean,
    notificationChange: (Boolean) -> Unit,
    notificationSettings: () -> Unit,
) {
    var countryDialog by remember { mutableStateOf(false) }
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
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Text(text.settings, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp)) }
        item {
            SettingsGroup(text.choose("Каталог", "Catalog")) {
                ToggleRow(text.choose("Отслеживать раздачи", "Track giveaways"), state.settings.trackFreeToKeep, model::setTracking)
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
                    listOf("auto" to text.choose("Системный", "System"), "ru" to "Русский", "en" to "English").forEach { (code, label) ->
                        FilterChip(selected = state.settings.language == code, onClick = { model.setLanguage(code) }, label = { Text(label) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
        }
        item {
            SettingsGroup(text.choose("Фоновая проверка", "Background checks")) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 180, 360, 720, 1440).forEach { minutes ->
                        FilterChip(selected = state.settings.intervalMinutes == minutes, onClick = { model.setIntervalMinutes(minutes) },
                            label = { Text(if (minutes < 60) text.choose("$minutes мин", "$minutes min") else text.choose("${minutes / 60} ч", "${minutes / 60} h")) }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
                Text(text.choose("Проверка выполняется при подключении к интернету. Android может отложить запуск для экономии заряда.", "Checks need an internet connection. Android may delay them to save battery."), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            SettingsGroup(text.choose("Оповещения", "Notifications")) {
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
        item {
            SettingsGroup(text.choose("Вид и фильтры", "Display and filters")) {
                ToggleRow(text.choose("Компактный вид", "Compact view"), state.settings.compactView, model::setCompact)
                ToggleRow(text.choose("Счётчик активных раздач", "Active giveaway count"), state.settings.badgeEnabled, model::setBadge)
                SortSelector(state.settings.sortBy, text, model::setSort)
                FilterSettings(FilterPreferences.fromJson(state.settings.filtersJson), text, model::setFilters)
            }
        }
        item {
            SettingsGroup(text.choose("История", "History")) {
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
        item {
            SettingsGroup(text.choose("Личные отметки", "Personal marks")) {
                PersonalMarks(state.marks, text, model::restoreMark)
                if (state.marks.isNotEmpty()) TextButton(onClick = { confirmClear = "marks" }) { Text(text.choose("Снять все личные отметки", "Clear all personal marks")) }
            }
        }
        item { BackupSection(state.language, model::exportBackup, model::importBackup) }
        item {
            SettingsGroup(text.choose("Состояние источников", "Source diagnostics")) {
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
        item {
            SettingsGroup(text.choose("О приложении", "About")) {
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
internal fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
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
