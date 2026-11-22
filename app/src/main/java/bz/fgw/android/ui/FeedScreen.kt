package bz.fgw.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bz.fgw.android.R
import bz.fgw.android.data.FilterPreferences
import bz.fgw.android.data.PersonalMark
import bz.fgw.android.data.StoredOffer
import bz.fgw.android.data.sortOffers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun FgwTheme(theme: String = "system", content: @Composable () -> Unit) {
    val dark = when (theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFFC0E899), secondary = Color(0xFFB7D3B1), onPrimary = Color(0xFF182713),
        background = Color.Black, onBackground = Color(0xFFF1F3EF),
        surface = Color.Black, onSurface = Color(0xFFF1F3EF),
        onSurfaceVariant = Color(0xFFB0BAB2), surfaceVariant = Color(0xFF151A17),
        secondaryContainer = Color(0xFF25371D), onSecondaryContainer = Color(0xFFC6EF92),
        outline = Color(0xFF7E8B80), outlineVariant = Color(0xFF2C322E),
    ) else lightColorScheme(
        primary = Color(0xFF245C42), secondary = Color(0xFF425C49), onPrimary = Color.White,
        background = Color(0xFFF6F8F3), onBackground = Color(0xFF18221D),
        surface = Color.White, onSurface = Color(0xFF18221D),
        onSurfaceVariant = Color(0xFF49594E), surfaceVariant = Color(0xFFE9EEE6),
        secondaryContainer = Color(0xFFC6EF92), onSecondaryContainer = Color(0xFF172F1F),
        outline = Color(0xFF738170), outlineVariant = Color(0xFFD4DDD3),
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
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable(tab) { mutableStateOf("") }
    var searching by rememberSaveable(tab) { mutableStateOf(false) }
    var storeFilter by rememberSaveable(tab) { mutableStateOf("all") }
    var historyFilter by rememberSaveable(tab) { mutableStateOf("all") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(15_000) } }
    val preferences = remember(state.settings.filtersJson) { FilterPreferences.fromJson(state.settings.filtersJson) }
    val marks = remember(state.marks) { state.marks.associateBy { it.gameKey } }
    val active = state.offers.filter { it.activeAt(now) && preferences.accepts(it) && marks[it.gameKey]?.suppresses(it, now) != true }
    val source = when (tab) {
        "history" -> state.history
        "upcoming" -> state.upcoming.filter { it.start != null && it.start > now && preferences.accepts(it) && marks[it.gameKey]?.suppresses(it, now) != true }
        else -> active
    }
    val offers = source.filter { offer ->
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
    }
    val selected = (state.offers + state.history + state.upcoming).find { it.campaignId == selectedId }
    val detail = notificationOffer ?: selected
    val closeDetail = { selectedId = null; onDismissNotificationOffer() }
    val openDetails: (StoredOffer) -> Unit = { selectedId = it.campaignId; model.act(it, "read") }
    val action: (StoredOffer, String) -> Unit = { offer, actionName ->
        scope.launch {
            try {
                val previous = model.performAction(offer, actionName)
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = "${offer.title} · ${actionLabel(actionName, text)}",
                    actionLabel = text.choose("Отменить", "Undo"), withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) model.undoAction(offer, previous)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snackbar.showSnackbar(text.actionFailed)
            }
        }
    }
    BackHandler(enabled = detail != null || tab != "giveaways") {
        if (detail != null) closeDetail() else tab = "giveaways"
    }
    val tabs = listOf("giveaways", "upcoming", "history", "settings")
    fun tabLabel(key: String): String = when (key) {
        "giveaways" -> text.giveaways
        "upcoming" -> text.choose("Скоро", "Upcoming")
        "history" -> text.history
        else -> text.settings
    }
    fun tabIcon(key: String): String = when (key) { "giveaways" -> "gift"; "upcoming" -> "clock"; else -> key }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide && detail == null) NavigationBar(containerColor = MaterialTheme.colorScheme.background, tonalElevation = 0.dp) {
                    tabs.forEach { key ->
                        NavigationBarItem(selected = tab == key, onClick = { settingsPage = null; tab = key },
                            icon = { AppIcon(tabIcon(key)) }, label = { Text(tabLabel(key), maxLines = 1) })
                    }
                }
            },
        ) { insets ->
            Row(Modifier.fillMaxSize().padding(insets)) {
                if (wide) NavigationRail(containerColor = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxHeight()) {
                    Spacer(Modifier.height(20.dp))
                    tabs.forEach { key ->
                        NavigationRailItem(selected = tab == key, onClick = { closeDetail(); settingsPage = null; tab = key },
                            icon = { AppIcon(tabIcon(key)) }, label = { Text(tabLabel(key)) })
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        detail != null -> OfferDetails(detail, text, now, marks[detail.gameKey], !state.settings.compactView, closeDetail, onOpenOffer, action)
                        tab == "settings" -> SettingsScreen(state, text, model, notificationsAvailable, refreshing, onNotificationsChanged, onOpenNotificationSettings, initialPage = settingsPage)
                        else -> {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Image(painterResource(R.drawable.ic_giftpad), null, Modifier.size(30.dp).background(Color(0xFF173C2A), RoundedCornerShape(8.dp)))
                                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                    Text(tabLabel(tab), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(state.lastSuccess?.let { last ->
                                        val time = DateTimeFormatter.ofPattern("HH:mm", text.locale).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(last))
                                        text.choose("Обновлено $time · ${state.country}", "Updated $time · ${state.country}")
                                    } ?: text.country(state.country), style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (state.settings.badgeEnabled && tab == "giveaways") Text(active.size.toString(),
                                    Modifier.background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                                if (tab == "history") HistorySelector(historyFilter, text) { historyFilter = it }
                                IconButton(onClick = { searching = !searching; if (!searching) query = "" }) { AppIcon("search", label = text.choose("Поиск", "Search")) }
                                IconButton(onClick = model::refresh, enabled = !refreshing && state.selectedStores.isNotEmpty() && state.settings.trackFreeToKeep) {
                                    if (refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else AppIcon("refresh", label = text.refresh)
                                }
                            }
                            if (searching) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                singleLine = true, label = { Text(text.choose("Название или ID", "Title or ID")) })
                            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                if (tab != "upcoming") Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf("all", "steam", "epic", "gog").forEach { store ->
                                        FilterChip(selected = storeFilter == store, onClick = { storeFilter = store },
                                            label = { Text(if (store == "all") text.choose("Все", "All") else if (store == "epic") "Epic" else storeName(store), style = MaterialTheme.typography.labelSmall) })
                                    }
                                }
                                SortSelector(state.settings.sortBy, text, model::setSort, compact = true)
                            }
                            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                                val incomplete = state.checks.filter {
                                    (tab != "upcoming" || it.store in setOf("epic", "gog")) &&
                                        (storeFilter == "all" || it.store == storeFilter) && (it.error != null || !it.complete)
                                }
                                if (incomplete.isNotEmpty()) item {
                                    TextButton(onClick = { settingsPage = "diagnostics"; tab = "settings" }, modifier = Modifier.fillMaxWidth()) {
                                        Text(incomplete.joinToString(" · ") { check ->
                                            val label = if (check.error != null && check.error != "partial")
                                                text.choose("не удалось обновить", "could not update") else text.choose("неполные данные", "partial results")
                                            "${storeName(check.store)} — $label"
                                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    }
                                }
                                if (tab == "giveaways") {
                                    if (!state.settings.trackFreeToKeep) item { FeedNotice(text.choose("Отслеживание приостановлено", "Tracking paused")) }
                                    if (state.notificationsEnabled && (!notificationsAvailable || state.remindersEnabled && !remindersAvailable)) item {
                                        TextButton(onClick = onOpenNotificationSettings, modifier = Modifier.padding(horizontal = 8.dp)) {
                                            Text(if (!notificationsAvailable) text.systemBlocked else text.choose("Android блокирует напоминания", "Android is blocking reminders"))
                                        }
                                    }
                                }
                                if (offers.isEmpty()) item {
                                    val message = when {
                                        query.isNotBlank() || storeFilter != "all" || historyFilter != "all" -> text.choose("Ничего не найдено", "No matches")
                                        tab == "history" -> text.choose("История пока пуста", "History is empty")
                                        tab == "upcoming" -> text.choose("Будущие раздачи пока не объявлены", "No upcoming giveaways announced")
                                        state.selectedStores.isEmpty() -> text.noStoresDetail
                                        refreshing -> text.refreshing
                                        state.checks.any { it.error != null || !it.complete } -> text.choose("Проверка не завершена. Подробности в диагностике источников.", "Check incomplete. See source diagnostics for details.")
                                        state.checks.isEmpty() -> text.firstCheck
                                        else -> text.choose("Активных раздач пока нет", "No active giveaways")
                                    }
                                    Text(message, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyLarge)
                                }
                                listOf("steam", "epic", "gog").forEach { store ->
                                    val group = sortOffers(offers.filter { it.store == store }, state.settings.sortBy)
                                    if (group.isNotEmpty()) {
                                        item(key = "store-$store") { StoreHeader(store, group.size) }
                                        items(group, key = { it.campaignId }) { offer ->
                                            CatalogOffer(offer, text, now, marks[offer.gameKey], !state.settings.compactView && store in state.selectedStores,
                                                { openDetails(offer) }, { onOpenOffer(offer); model.act(offer, "read") }, action)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (alert != null || failed) AlertDialog(
        onDismissRequest = { onDismissAlert(); model.dismissMessage() },
        text = { Text(alert ?: text.actionFailed) },
        confirmButton = { TextButton(onClick = { onDismissAlert(); model.dismissMessage() }) { Text(text.okay) } },
    )
}

@Composable
private fun FeedNotice(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun StoreHeader(store: String, count: Int) {
    val dark = MaterialTheme.colorScheme.background == Color.Black
    val colors = when (store) {
        "steam" -> if (dark) Color(0xFF132737) to Color(0xFFA9D6FF) else Color(0xFFDDECF9) to Color(0xFF185484)
        "epic" -> if (dark) Color(0xFF10292C) to Color(0xFF96E0E5) else Color(0xFFD9EFF1) to Color(0xFF076476)
        else -> if (dark) Color(0xFF192C1B) to Color(0xFFAFE2A4) else Color(0xFFE1EFDC) to Color(0xFF286332)
    }
    Column(Modifier.fillMaxWidth().background(colors.first).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompositionLocalProvider(LocalContentColor provides colors.second) { StoreLogo(store, Modifier.size(20.dp)) }
            Text(storeName(store), Modifier.weight(1f).padding(start = 8.dp), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = colors.second)
            Text(count.toString(), style = MaterialTheme.typography.labelLarge, color = colors.second)
        }
    }
}

@Composable
private fun CatalogOffer(
    offer: StoredOffer, text: UiText, now: Long, mark: PersonalMark?, cover: Boolean,
    details: () -> Unit, open: () -> Unit, action: (StoredOffer, String) -> Unit,
) {
    val largeText = LocalDensity.current.fontScale > 1.3f
    BoxWithConstraints(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp)) {
        val stacked = maxWidth < 320.dp || largeText
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clickable(onClick = details), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (cover && offer.imageUrl != null) OfferCover(offer.store, offer.imageUrl, Modifier.width(if (stacked) 88.dp else 80.dp).clip(RoundedCornerShape(5.dp)))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text((if (!offer.isRead && !offer.upcoming) "• " else "") + offer.title,
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = if (largeText) 3 else 2, overflow = TextOverflow.Ellipsis)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            ContentBadge(offer.contentType, text)
                            ratingLabel(offer, text)?.let { Text("☆ $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
                if (!stacked) OfferStoreButton(offer, text, now, open)
            }
            if (stacked) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { OfferStoreButton(offer, text, now, open) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(offerTiming(offer, now, text), style = MaterialTheme.typography.labelSmall,
                        color = if (offer.end != null && offer.end > now && offer.end - now < 86_400_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    offer.genres.firstOrNull()?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                MarkActions(offer, mark, text, action)
            }
            markLabel(mark, offer, now, text)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun ContentBadge(type: String, text: UiText) {
    val dark = MaterialTheme.colorScheme.background == Color.Black
    val color = when (type) {
        "dlc" -> if (dark) Color(0xFF96E0E5) else Color(0xFF076476)
        "tool", "software" -> if (dark) Color(0xFFAFE2A4) else Color(0xFF286332)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(contentLabel(type, text),
        Modifier.background(color.copy(alpha = 0.10f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        color = color, style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun OfferStoreButton(offer: StoredOffer, text: UiText, now: Long, open: () -> Unit) {
    val active = offer.activeAt(now)
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.widthIn(max = 112.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (active) Text("0", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            formatOfferPrice(offer, text.locale)?.let { Text(it, style = MaterialTheme.typography.labelSmall,
                textDecoration = if (active) TextDecoration.LineThrough else null, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Button(onClick = open, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(8.dp)) {
            Text(text.choose("В магазин", "Store"), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            AppIcon("arrow", Modifier.size(16.dp))
        }
    }
}

@Composable
private fun MarkActions(offer: StoredOffer, mark: PersonalMark?, text: UiText, action: (StoredOffer, String) -> Unit) {
    Row {
        listOf((if (mark?.claimed == true) "unclaim" else "claim") to "check", "snooze" to "clock", "hide" to "hide", "ignore" to "ignore").forEach { (key, icon) ->
            IconButton(onClick = { action(offer, key) }, modifier = Modifier.size(48.dp)) { AppIcon(icon, label = actionLabel(key, text)) }
        }
    }
}

@Composable
private fun OfferDetails(offer: StoredOffer, text: UiText, now: Long, mark: PersonalMark?, cover: Boolean, back: () -> Unit,
    open: (StoredOffer) -> Unit, action: (StoredOffer, String) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { AppIcon("back", label = text.back) }
            Text(storeName(offer.store), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (cover) OfferCover(offer.store, offer.imageUrl, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)))
            Text(offer.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(contentLabel(offer.contentType, text), color = MaterialTheme.colorScheme.primary)
            Text(offerTiming(offer, now, text))
            offer.start?.let { Text(text.choose("Начало · ${text.time(it)}", "Starts · ${text.time(it)}")) }
            Text(offer.end?.let(text::deadline) ?: text.noDeadline)
            formatOfferPrice(offer, text.locale)?.let { Text(text.choose("Обычная цена · $it", "Regular price · $it")) }
            ratingLabel(offer, text)?.let { rating ->
                val label = if (offer.store == "epic") text.choose("Оценка игроков Epic · $rating", "Epic player rating · $rating")
                    else rating + (offer.reviewCount?.let { text.choose(" · $it отзывов", " · $it reviews") } ?: "")
                Text(label)
            }
            if (offer.genres.isNotEmpty()) Text(offer.genres.joinToString(" · "))
            if (offer.platforms.isNotEmpty()) Text(offer.platforms.joinToString(" · ") { when (it) { "windows" -> "Windows"; "mac" -> "macOS"; "linux" -> "Linux"; else -> it } })
            Text(text.country(offer.country), style = MaterialTheme.typography.bodySmall)
            if (offer.imported) Text(text.choose("Импортированная запись. Доступность подтвердится при проверке магазина.", "Imported record. A store check will confirm availability."))
            markLabel(mark, offer, now, text)?.let { Text(it) }
            if (now - offer.lastConfirmed > 21_600_000 && offer.activeAt(now)) Text(text.choose("Данные устарели. Проверьте доступность в магазине.", "Saved data is old. Check availability in the store."), color = MaterialTheme.colorScheme.error)
            Button(onClick = { open(offer) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(text.openStore); Spacer(Modifier.width(8.dp)); AppIcon("arrow") }
            MarkActions(offer, mark, text, action)
            if (mark != null) TextButton(onClick = { action(offer, "restore") }) { Text(text.choose("Снять все отметки", "Clear all marks")) }
        }
    }
}

private fun contentLabel(type: String, text: UiText): String = when (type) {
    "dlc" -> "DLC"
    "soundtrack" -> text.choose("Саундтрек", "Soundtrack")
    "demo" -> text.choose("Демо", "Demo")
    "tool", "software" -> text.choose("Программа", "Software")
    "package" -> text.choose("Набор", "Package")
    else -> text.choose("Игра", "Game")
}

private fun ratingLabel(offer: StoredOffer, text: UiText): String? = when {
    offer.store == "steam" && offer.ratingPercent != null -> "${offer.ratingPercent}%"
    offer.ratingValue != null -> "${String.format(text.locale, "%.1f", offer.ratingValue)} / 5"
    else -> null
}

private fun offerTiming(offer: StoredOffer, now: Long, text: UiText): String {
    if (offer.ended || offer.end != null && offer.end <= now) return text.ended
    if (offer.imported) return text.choose("Ожидает проверки", "Awaiting verification")
    if (offer.start != null && offer.start > now) return text.choose("Начнётся ${shortDate(offer.start, text)}", "Starts ${shortDate(offer.start, text)}")
    val end = offer.end ?: return text.choose("Срок неизвестен", "End date unknown")
    val hours = (end - now) / 3_600_000
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val endDay = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
    val time = DateTimeFormatter.ofPattern("HH:mm", text.locale).withZone(zone).format(Instant.ofEpochMilli(end))
    return when {
        hours < 1 -> text.choose("Осталось меньше часа", "Less than an hour left")
        endDay == today -> text.choose("Сегодня, $time", "Today, $time")
        endDay == today.plusDays(1) -> text.choose("Завтра, $time", "Tomorrow, $time")
        else -> text.choose("До ${shortDate(end, text)}", "Until ${shortDate(end, text)}")
    }
}

private fun shortDate(value: Long, text: UiText): String = DateTimeFormatter.ofPattern(if (text.language == "en") "MMM d · HH:mm" else "d MMM · HH:mm", text.locale)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))

private fun markLabel(mark: PersonalMark?, offer: StoredOffer, now: Long, text: UiText): String? {
    if (mark == null) return null
    return listOfNotNull(text.claimed.takeIf { mark.claimed }, text.choose("Игнорируется", "Ignored").takeIf { mark.ignored },
        text.choose("Скрыта", "Hidden").takeIf { mark.hiddenCampaign == offer.campaignId },
        text.choose("Отложена", "Snoozed").takeIf { mark.snoozedUntil > now }).joinToString(" · ").ifBlank { null }
}

@Composable
private fun StoreLogo(store: String, modifier: Modifier = Modifier) {
    val drawable = when (store) { "steam" -> R.drawable.ic_store_steam; "gog" -> R.drawable.ic_store_gog; else -> R.drawable.ic_store_epic }
    Icon(painterResource(drawable), contentDescription = null, modifier = modifier)
}

@Composable
internal fun SortSelector(sort: String, text: UiText, change: (String) -> Unit, compact: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf("ending" to text.choose("Скоро завершатся", "Ending soon"), "newest" to text.choose("Сначала новые", "Newest first"),
        "title" to text.choose("По названию", "Title"), "rating" to text.choose("Рейтинг Steam", "Steam rating"))
    Box {
        val label = if (compact) when (sort) {
            "newest" -> text.choose("Новые", "Newest")
            "title" -> text.choose("А–Я", "A–Z")
            "rating" -> text.choose("Рейтинг", "Rating")
            else -> text.choose("По сроку", "Ending")
        } else choices.find { it.first == sort }?.second ?: choices.first().second
        TextButton(onClick = { expanded = true }) {
            AppIcon("sort")
            Spacer(Modifier.width(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; change(key) }) }
        }
    }
}

@Composable
private fun HistorySelector(selected: String, text: UiText, select: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf("all" to text.choose("Все", "All"), "active" to text.choose("Активные", "Active"), "ended" to text.choose("Завершённые", "Ended"),
        "claimed" to text.claimed, "ignored" to text.choose("Игнорируемые", "Ignored"), "hidden" to text.choose("Скрытые", "Hidden"), "snoozed" to text.choose("Отложенные", "Snoozed"))
    Box {
        IconButton(onClick = { expanded = true }, colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (selected == "all") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
        )) {
            AppIcon("filter", label = text.choose("Фильтр истории · ", "History filter · ") + (choices.find { it.first == selected }?.second ?: choices.first().second))
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { expanded = false; select(key) }) }
        }
    }
}
