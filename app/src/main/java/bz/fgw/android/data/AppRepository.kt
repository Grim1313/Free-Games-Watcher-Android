package bz.fgw.android.data

import android.content.Context
import androidx.room3.immediateTransaction
import androidx.room3.useWriterConnection
import androidx.work.*
import bz.fgw.android.providers.*
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class FeedState(
    val settings: WatchSettings = WatchSettings(),
    val offers: List<StoredOffer> = emptyList(),
    val history: List<StoredOffer> = emptyList(),
    val marks: List<PersonalMark> = emptyList(),
    val upcoming: List<StoredOffer> = emptyList(),
    val checks: List<CheckStatus> = emptyList(),
) {
    val claimedIds get() = marks.filter { it.claimed }.map { it.gameKey }.toSet()
    val intervalMinutes get() = settings.intervalMinutes
    val country get() = settings.country
    val selectedStores get() = settings.selectedStores()
    val language get() = settings.displayLanguage
    val intervalHours get() = settings.intervalHours
    val notificationsEnabled get() = settings.notificationsEnabled
    val remindersEnabled get() = settings.remindersEnabled
    val quietHoursEnabled get() = settings.quietHoursEnabled
    val quietStartMinute get() = settings.quietStartMinute
    val quietEndMinute get() = settings.quietEndMinute
    val lastSuccess get() = checks.mapNotNull { it.lastSuccess }.maxOrNull()
    val lastAttempt get() = checks.maxOfOrNull { it.lastAttempt }
    val error get() = checks.mapNotNull { it.error }.joinToString("; ").ifEmpty { null }
    val complete get() = checks.size == selectedStores.size && checks.all { it.complete }
    val requestCount get() = checks.sumOf { it.requestCount }
}

enum class ScanOutcome { SUCCESS, RETRY, STALE }

class AppRepository internal constructor(
    private val context: Context,
    internal val database: WatchDatabase,
    private val providers: Map<String, StoreProvider> = mapOf(
        "epic" to EpicProvider(), "steam" to SteamProvider(), "gog" to GogProvider(),
    ),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    internal val dao = database.watchDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scanMutex = Mutex()
    private val settingsMutex = Mutex()
    private val activeScans = mutableMapOf<Long, Deferred<ScanOutcome>>()
    private val notifier = GiveawayNotifications(context)

    val state: Flow<FeedState> = combine(
        dao.observeSettings(), dao.observeOffers(), dao.observeChecks(), dao.observeMarks(),
    ) { settingsOrNull, offers, checks, marks ->
        val settings = settingsOrNull ?: WatchSettings()
        val regional = offers.filter { it.country == settings.country && it.store in settings.selectedStores() }
        FeedState(
            settings = settings,
            offers = regional.filter { !it.upcoming && !it.imported },
            upcoming = regional.filter { it.upcoming && !it.ended && !it.imported },
            history = if (settings.historyEnabled) offers.filter { !it.upcoming && it.inHistory } else emptyList(),
            marks = marks,
            checks = checks.filter { it.country == settings.country && it.store in settings.selectedStores() },
        )
    }

    fun schedule(): Job = scope.launch {
        settingsMutex.withLock {
            dao.initialize(WatchSettings())
            schedule(dao.settings())
        }
    }

    private suspend fun schedule(settings: WatchSettings) {
        notifier.createChannels(settings.displayLanguage)
        val manager = WorkManager.getInstance(context)
        if (!settings.trackFreeToKeep || settings.selectedStores().isEmpty()) {
            manager.cancelUniqueWork(PERIODIC_WORK_NAME).result.get()
            return
        }
        val request = PeriodicWorkRequestBuilder<EpicCheckWorker>(settings.intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request).result.get()
    }

    suspend fun setCountry(country: String) {
        val normalized = country.trim().uppercase(Locale.ROOT)
        require(normalized in Locale.getISOCountries())
        changeSettings(revise = true) { it.copy(country = normalized) }
    }

    suspend fun setTracking(enabled: Boolean) = changeSettings(revise = true, reschedule = true) {
        it.copy(trackFreeToKeep = enabled)
    }

    suspend fun setStores(stores: Set<String>) {
        require(stores.all { it in SUPPORTED_STORES })
        changeSettings(revise = true, reschedule = true) { it.copy(stores = stores.sorted().joinToString(",")) }
    }

    suspend fun setLanguage(language: String) {
        require(language in setOf("ru", "en", "auto"))
        changeSettings { it.copy(language = language) }
        notifier.createChannels(dao.settings().displayLanguage)
    }

    suspend fun setIntervalMinutes(minutes: Int) {
        require(minutes in setOf(15, 30, 60, 180, 360, 720, 1440))
        changeSettings(reschedule = true) { it.copy(intervalHours = minutes / 60, intervalMinutes = minutes) }
    }

    suspend fun setReminderHours(hours: Int) {
        require(hours in 1..72)
        changeSettings { it.copy(reminderHours = hours) }
    }

    suspend fun setFilters(filters: FilterPreferences) = changeSettings { it.copy(filtersJson = filters.toJson()) }

    suspend fun setSort(sort: String) {
        require(sort in setOf("ending", "newest", "rating", "title"))
        changeSettings { it.copy(sortBy = sort) }
    }

    suspend fun setCompact(compact: Boolean) = changeSettings { it.copy(compactView = compact) }

    suspend fun setBadge(enabled: Boolean) = changeSettings { it.copy(badgeEnabled = enabled) }

    suspend fun resetSettings() = withBackupSettingsLock {
        transaction {
            dao.initialize(WatchSettings())
            dao.saveSettings(WatchSettings(revision = dao.settings().revision + 1))
        }
    }

    suspend fun testNotification(): Boolean {
        dao.initialize(WatchSettings())
        return notifier.showTest(dao.settings().displayLanguage)
    }

    suspend fun setHistory(enabled: Boolean, days: Int) {
        require(days in 1..365)
        changeSettings { it.copy(historyEnabled = enabled, historyRetentionDays = days) }
    }

    suspend fun setRepeatAlerts(enabled: Boolean, days: Int) {
        require(days in 1..365)
        changeSettings { it.copy(renotifyAfterRetention = enabled, notifiedRetentionDays = days) }
    }

    suspend fun setQuietHours(enabled: Boolean, startMinute: Int, endMinute: Int) {
        require(startMinute in 0..1439 && endMinute in 0..1439)
        require(!enabled || startMinute != endMinute)
        changeSettings { it.copy(quietHoursEnabled = enabled, quietStartMinute = startMinute, quietEndMinute = endMinute) }
    }

    suspend fun setReminders(enabled: Boolean) {
        changeSettings { it.copy(remindersEnabled = enabled) }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        changeSettings { it.copy(notificationsEnabled = enabled) }
    }

    private suspend fun changeSettings(
        revise: Boolean = false,
        reschedule: Boolean = false,
        update: (WatchSettings) -> WatchSettings,
    ) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            val saved = transaction {
                dao.initialize(WatchSettings())
                val old = dao.settings()
                val changed = update(old)
                if (changed == old) return@transaction old
                val next = changed.copy(revision = old.revision + if (revise) 1 else 0)
                dao.saveSettings(next)
                when {
                    (old.trackFreeToKeep && !next.trackFreeToKeep) || old.country != next.country || (old.notificationsEnabled && !next.notificationsEnabled) ->
                        notifier.cancelAll()
                    else -> {
                        for (store in old.selectedStores() - next.selectedStores()) {
                            dao.storeOffers(store).forEach { notifier.cancel(it.campaignId) }
                        }
                        if (old.remindersEnabled && !next.remindersEnabled) notifier.cancelReminders()
                    }
                }
                next
            }
            if (reschedule) schedule(saved)
        }
    }

    suspend fun setClaimed(offer: StoredOffer, claimed: Boolean) = act(offer, if (claimed) "claim" else "unclaim")

    suspend fun act(offer: StoredOffer, action: String) = settingsMutex.withLock {
        transaction {
            if (action == "read") {
                dao.markRead(listOf(offer.campaignId))
                return@transaction
            }
            val now = clock()
            val old = dao.mark(offer.gameKey) ?: PersonalMark(offer.gameKey, offer.title, offer.url)
            val mark = when (action) {
                "claim" -> old.copy(claimed = true)
                "unclaim" -> old.copy(claimed = false)
                "ignore" -> old.copy(ignored = true)
                "hide" -> old.copy(hiddenCampaign = offer.campaignId)
                "snooze" -> old.copy(snoozedUntil = now + TimeUnit.DAYS.toMillis(1), snoozePending = true)
                "restore" -> PersonalMark(offer.gameKey, offer.title, offer.url)
                else -> error("Unknown offer action")
            }
            if (!mark.claimed && !mark.ignored && mark.hiddenCampaign.isEmpty() && !mark.snoozePending && mark.snoozedUntil <= now) {
                dao.removeMark(offer.gameKey)
            } else {
                dao.putMarks(listOf(mark.copy(title = offer.title, url = offer.url, updatedAt = now)))
            }
            if (mark.suppresses(offer, now)) {
                for (entry in dao.gameOffers(offer.store, offer.id)) {
                    if (mark.suppresses(entry, now)) notifier.cancel(entry.campaignId)
                }
            }
        }
    }

    suspend fun restoreMark(key: String) = settingsMutex.withLock { dao.removeMark(key) }

    suspend fun clearMarks() = settingsMutex.withLock { dao.clearMarks() }

    suspend fun clearHistory() = transaction {
        dao.hideHistory()
        dao.pruneHistory(clock(), Long.MAX_VALUE)
        dao.pruneOrphanEvents()
    }

    suspend fun markAllRead() = transaction {
        val settings = dao.settings()
        for (store in settings.selectedStores()) {
            val ids = dao.knownOffers(store, settings.country).filter { it.activeAt(clock()) && !it.isRead }
                .map { it.campaignId }
            ids.chunked(500).forEach { dao.markRead(it) }
        }
    }

    internal suspend fun withBackupSettingsLock(block: suspend () -> Unit) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            block()
            notifier.cancelAll()
            schedule(dao.settings())
        }
    }

    suspend fun findOffer(campaignId: String): StoredOffer? = dao.offer(campaignId)

    suspend fun refresh(backgroundRetry: Boolean = false): ScanOutcome {
        dao.initialize(WatchSettings())
        val settings = dao.settings()
        val scan = synchronized(activeScans) {
            activeScans.entries.removeAll { it.value.isCompleted }
            activeScans[settings.revision]?.takeIf { it.isActive } ?: scope.async {
                scanMutex.withLock { scan(settings, backgroundRetry) }
            }.also { activeScans[settings.revision] = it }
        }
        return scan.await()
    }

    private suspend fun scan(settings: WatchSettings, backgroundRetry: Boolean): ScanOutcome {
        if (dao.settings().revision != settings.revision) return ScanOutcome.STALE
        if (!settings.trackFreeToKeep) return ScanOutcome.SUCCESS
        var retry = false
        for (store in settings.selectedStores()) {
            if (dao.settings().revision != settings.revision) return ScanOutcome.STALE
            val started = clock()
            val previous = dao.check(settings.country, store)
            if (backgroundRetry && previous?.lastSuccess != null && previous.error != "failed" && previous.retryAt == null &&
                previous.lastSuccess >= previous.lastAttempt && started - previous.lastSuccess < 15 * 60 * 1000L) {
                deliverPending(settings.revision, store)
                continue
            }
            if ((previous?.retryAt ?: 0) > started) {
                retry = true
                deliverPending(settings.revision, store)
                continue
            }
            val known = dao.knownOffers(store, settings.country)
            val result = try {
                providers.getValue(store).fetch(
                    settings.country,
                    known.filter { !it.upcoming && !it.ended && (it.end == null || it.end > started) }
                        .map { it.toStoreOffer() },
                    started,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                StoreResult(
                    offers = emptyList(), complete = false, successful = false, requests = 1,
                    details = failure.message?.take(400) ?: failure.javaClass.simpleName,
                    retryAfterMillis = (failure as? StoreHttpException)?.retryAfterMillis,
                )
            }
            val accepted = transaction {
                if (dao.settings().revision != settings.revision) return@transaction false
                val confirmed = clock()
                val updated = mergeOffers(store, settings, known, result, confirmed)
                val obsolete = known.filter { it.upcoming && (it.end != null && it.end <= confirmed ||
                    result.complete && updated.none { next -> next.campaignId == it.campaignId }) }
                if (obsolete.isNotEmpty()) dao.deleteOffers(obsolete.map { it.campaignId })
                dao.saveOffers(updated)
                dao.saveCheck(CheckStatus(
                    country = settings.country, store = store, lastAttempt = started,
                    lastSuccess = if (result.successful) confirmed else previous?.lastSuccess,
                    complete = result.complete,
                    error = if (result.complete) null else if (result.successful) "partial" else "failed",
                    requestCount = result.requests,
                    details = result.details,
                    retryAt = result.retryAfterMillis?.let { confirmed + it.coerceAtLeast(0) },
                    confirmedRevision = if (result.successful) settings.revision else previous?.confirmedRevision,
                ))
                val current = dao.settings()
                if (current.notificationsEnabled) {
                    for (entry in updated.filter { it.activeAt(confirmed) && it.confirmedRevision == current.revision }) {
                        val event = dao.event(entry.campaignId)
                        val mark = dao.mark(entry.gameKey)
                        val snoozeExpired = mark?.snoozePending == true && mark.snoozedUntil <= confirmed
                        val repeat = current.renotifyAfterRetention && event?.delivered == true && event.deliveredAt > 0 &&
                            confirmed - event.deliveredAt >= TimeUnit.DAYS.toMillis(current.notifiedRetentionDays.toLong())
                        if (snoozeExpired || repeat) {
                            dao.resetNewEvent(entry.campaignId)
                            if (snoozeExpired) {
                                if (mark.claimed || mark.ignored || mark.hiddenCampaign.isNotEmpty()) {
                                    dao.putMarks(listOf(mark.copy(snoozePending = false, snoozedUntil = 0)))
                                } else dao.removeMark(mark.gameKey)
                            }
                        }
                        dao.enqueue(listOf(NotificationEvent(entry.campaignId)))
                    }
                    if (current.remindersEnabled) {
                        val reminderOffers = dao.knownOffers(store, settings.country).filter {
                            it.activeAt(confirmed) && it.confirmedRevision == settings.revision &&
                                confirmed - it.lastConfirmed <= NOTIFICATION_FRESHNESS &&
                                it.end != null && it.end - confirmed <= TimeUnit.HOURS.toMillis(current.reminderHours.toLong())
                        }
                        dao.enqueue(reminderOffers.map { NotificationEvent(it.campaignId, type = "reminder") })
                    }
                }
                val keepSince = if (current.historyEnabled) confirmed - TimeUnit.DAYS.toMillis(current.historyRetentionDays.toLong()) else Long.MAX_VALUE
                dao.pruneHistory(confirmed, keepSince)
                dao.pruneOrphanEvents()
                true
            }
            if (!accepted) return ScanOutcome.STALE
            if (!result.successful || result.retryAfterMillis != null) retry = true
            // Reconcile this store's result before publishing a deferred notification.
            deliverPending(settings.revision, store)
        }
        return if (retry) ScanOutcome.RETRY else ScanOutcome.SUCCESS
    }

    private fun mergeOffers(
        store: String,
        settings: WatchSettings,
        known: List<StoredOffer>,
        result: StoreResult,
        now: Long,
    ): List<StoredOffer> {
        val updates = known.filter { !it.upcoming && !it.ended && (it.id in result.ended || (it.end != null && it.end <= now)) }
            .map { it.copy(ended = true) }.associateBy { it.campaignId }.toMutableMap()
        val candidates = result.offers.filter { it.start == null || it.start <= now }.distinctBy { it.id } +
            result.upcoming.filter { it.start != null && it.start > now }.distinctBy { it.id to it.start }
        for (offer in candidates) {
            val upcoming = offer.start != null && offer.start > now
            if (offer.end != null && offer.end <= now) continue
            val previous = known.firstOrNull { old ->
                old.id == offer.id && !old.ended && old.id !in result.ended &&
                    (if (upcoming || old.upcoming) old.start == offer.start else true) &&
                    (old.end == null || old.end > now) &&
                    (old.end == null || offer.start == null || offer.start < old.end)
            }
            // Missing/changed dates do not create another campaign while an old one is active.
            val campaignId = previous?.campaignId ?: "${settings.country}:$store:${offer.id}:${UUID.randomUUID()}"
            updates[campaignId] = StoredOffer(
                campaignId, offer.id, offer.title, offer.url,
                offer.start ?: previous?.start, offer.end ?: previous?.end,
                settings.country, now, settings.revision, store,
                imageUrl = offer.imageUrl ?: previous?.imageUrl,
                originalPrice = offer.originalPrice ?: previous?.originalPrice,
                currency = offer.currency ?: previous?.currency,
                priceText = offer.priceText ?: previous?.priceText,
                ratingPercent = offer.ratingPercent ?: previous?.ratingPercent,
                reviewCount = offer.reviewCount ?: previous?.reviewCount,
                ratingValue = offer.ratingValue ?: previous?.ratingValue,
                genres = offer.genres.ifEmpty { previous?.genres.orEmpty() },
                platforms = offer.platforms.ifEmpty { previous?.platforms.orEmpty() },
                contentType = offer.contentType, firstSeen = previous?.firstSeen ?: now,
                isRead = previous?.isRead ?: false, upcoming = upcoming,
                inHistory = settings.historyEnabled && (previous?.inHistory ?: true),
            )
        }
        return updates.values.toList()
    }

    private suspend fun deliverPending(revision: Long, store: String) = settingsMutex.withLock {
        val settings = dao.settings()
        if (settings.revision != revision || !settings.notificationsEnabled || isQuiet(settings, clock(), zone())) return@withLock
        val filters = FilterPreferences.fromJson(settings.filtersJson)
        val reminderWindow = TimeUnit.HOURS.toMillis(settings.reminderHours.toLong())
        val pending = dao.pendingEvents().sortedBy { if (it.type == "new") 0 else 1 }
        for (event in pending) {
            val offer = dao.offer(event.campaignId) ?: continue
            if (offer.country != settings.country || offer.store != store) continue
            if (offer.confirmedRevision != revision) continue
            val now = clock()
            if (offer.ended || (offer.end != null && offer.end <= now)) {
                dao.markDiscarded(event.campaignId, event.type)
                continue
            }
            if (!offer.activeAt(now) || !filters.accepts(offer) || dao.mark(offer.gameKey)?.suppresses(offer, now) == true) continue
            if (now - offer.lastConfirmed > NOTIFICATION_FRESHNESS) continue
            if (event.type == "reminder") {
                if (!settings.remindersEnabled || offer.end == null || offer.end - now > reminderWindow) continue
                // A new alert delivered in this pass already tells the user about the deadline.
                if (dao.event(offer.campaignId, "reminder")?.discarded == true) continue
            }
            if (notifier.show(offer, event.type, settings.displayLanguage, now)) {
                transaction {
                    dao.markDelivered(event.campaignId, event.type, now)
                    if (event.type == "new" && offer.end != null && offer.end - now <= reminderWindow) {
                        dao.enqueue(listOf(NotificationEvent(offer.campaignId, discarded = true, type = "reminder")))
                        dao.markDiscarded(offer.campaignId, "reminder")
                    }
                }
            }
        }
    }

    internal suspend fun <T> transaction(block: suspend () -> T): T =
        database.useWriterConnection { connection -> connection.immediateTransaction { block() } }

    companion object {
        // Keep the unique name to replace the stage-one schedule on upgrade.
        const val PERIODIC_WORK_NAME = "epic-weekly-giveaways"
        private const val NOTIFICATION_FRESHNESS = 6 * 60 * 60 * 1000L

        internal fun isQuiet(settings: WatchSettings, now: Long, zone: ZoneId): Boolean {
            if (!settings.quietHoursEnabled) return false
            val time = Instant.ofEpochMilli(now).atZone(zone)
            val minute = time.hour * 60 + time.minute
            return if (settings.quietStartMinute < settings.quietEndMinute) {
                minute in settings.quietStartMinute until settings.quietEndMinute
            } else minute >= settings.quietStartMinute || minute < settings.quietEndMinute
        }
    }
}

private fun StoredOffer.toStoreOffer() = StoreOffer(
    id, title, url, start, end, imageUrl, originalPrice, currency, priceText,
    ratingPercent, reviewCount, ratingValue, genres, platforms, contentType,
)
