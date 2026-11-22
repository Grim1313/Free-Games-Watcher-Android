package bz.fgw.android.data

import android.app.Notification
import android.app.NotificationManager
import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.Build
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import bz.fgw.android.providers.StoreHttpException
import bz.fgw.android.providers.StoreOffer
import bz.fgw.android.providers.StoreProvider
import bz.fgw.android.providers.StoreResult
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDeviceTest {
    private lateinit var context: Context
    private lateinit var database: WatchDatabase
    private lateinit var notifications: NotificationManager
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        notifications = context.getSystemService(NotificationManager::class.java)
        notifications.cancelAll()
        awaitNotifications(0)
        WorkManager.getInstance(context).cancelUniqueWork(AppRepository.PERIODIC_WORK_NAME).result.get()
        if (Build.VERSION.SDK_INT >= 33) {
            ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                    "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
                ),
            ).use { it.readBytes() }
        }
        database = Room.inMemoryDatabaseBuilder<WatchDatabase>(context)
            .setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        notifications.cancelAll()
    }

    private fun awaitNotifications(count: Int): List<StatusBarNotification> {
        val timeout = SystemClock.elapsedRealtime() + 3_000
        while (true) {
            // Android may add group summaries and apply notify/cancel asynchronously.
            val alerts = notifications.activeNotifications.filter {
                it.tag?.contains(":epic:fixture:") == true &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
            }
            if (alerts.size == count) return alerts
            if (SystemClock.elapsedRealtime() >= timeout) {
                throw AssertionError("Expected $count fixture notifications, got ${alerts.map { it.tag }}")
            }
            SystemClock.sleep(25)
        }
    }

    private suspend fun repository(
        fetch: (String) -> StoreResult,
        clock: () -> Long = System::currentTimeMillis,
    ): AppRepository {
        database.watchDao().initialize(WatchSettings(stores = "epic"))
        return AppRepository(context, database, mapOf("epic" to StoreProvider { country, _, _ -> fetch(country) }), clock)
    }
    private fun offer(end: Long = now + 86_400_000) = StoreOffer(
        "epic:fixture:123", "FGW device test", "https://store.epicgames.com/en-US/p/test-game",
        now - 60_000, end,
    )

    @Test
    fun workerPersistsAndNotifiesExactlyOnceAcrossDeadlineExtension() = runBlocking {
        var end = now + 86_400_000
        val repository = repository({ StoreResult(listOf(offer(end)), true) })
        repository.setNotificationsEnabled(true)
        val worker = TestListenableWorkerBuilder<EpicCheckWorker>(context).build()
        worker.repository = repository
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        awaitNotifications(1)
        val firstTag = awaitNotifications(1).single().tag
        end += 86_400_000
        assertEquals(ScanOutcome.SUCCESS, repository.refresh())
        assertEquals(1, database.watchDao().deliveredCount())
        awaitNotifications(1)
        assertEquals(firstTag, awaitNotifications(1).single().tag)
        assertEquals(end, repository.findOffer(firstTag)?.end)
        val notification = awaitNotifications(1).single().notification
        assertNotNull(notification.contentIntent)
        assertNotNull(notification.actions.single().actionIntent)
    }

    @Test
    fun concurrentManualAndWorkerChecksShareRequest() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val repository = repository({
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            StoreResult(listOf(offer()), true)
        })
        val worker = TestListenableWorkerBuilder<EpicCheckWorker>(context).build()
        worker.repository = repository
        val background = async(Dispatchers.IO) { worker.doWork() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val manual = async(Dispatchers.IO) { repository.refresh() }
        delay(150)
        release.countDown()
        assertEquals(ListenableWorker.Result.success(), background.await())
        assertEquals(ScanOutcome.SUCCESS, manual.await())
        assertEquals(1, requests.get())
    }

    @Test
    fun schedulingTwiceKeepsOnePeriodicWork() = runBlocking {
        val repository = repository({ StoreResult(emptyList(), true) })
        repository.schedule().join()
        repository.schedule().join()
        val work = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(AppRepository.PERIODIC_WORK_NAME)
            .get(10, TimeUnit.SECONDS)
        assertEquals(1, work.count { !it.state.isFinished })
    }

    @Test
    fun overlappingCallsShareOneRequestAndRegionChangeRejectsOldResult() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requests = AtomicInteger()
        val repository = repository({
            requests.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
            StoreResult(listOf(offer()), true)
        })
        val first = async(Dispatchers.IO) { repository.refresh() }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = async(Dispatchers.IO) { repository.refresh() }
        repository.setCountry("RU")
        release.countDown()
        assertEquals(ScanOutcome.STALE, first.await())
        second.await()
        assertTrue(repository.state.first().offers.all { it.country == "RU" })
        assertTrue(database.watchDao().knownOffers("epic", "US").isEmpty())
        assertTrue(requests.get() <= 2)
    }

    @Test
    fun failedRequestKeepsCachedOffersAndRecordsError() = runBlocking {
        var fail = false
        val repository = repository({
            if (fail) throw IOException("Offline test")
            StoreResult(listOf(offer()), true)
        })
        repository.refresh()
        fail = true
        assertEquals(ScanOutcome.RETRY, repository.refresh())
        val state = repository.state.first()
        assertEquals(1, state.offers.size)
        assertNotNull(state.lastSuccess)
        assertEquals("failed", state.error)
        assertEquals("Offline test", state.checks.single().details)
    }

    @Test
    fun retryAfterIsSharedByManualAndWorkerRequests() = runBlocking {
        var time = now
        var requests = 0
        val repository = repository({
            requests++
            throw StoreHttpException(429, 60_000)
        }, { time })
        assertEquals(ScanOutcome.RETRY, repository.refresh())
        val worker = TestListenableWorkerBuilder<EpicCheckWorker>(context).build()
        worker.repository = repository
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        assertEquals(1, requests)
        time += 60_001
        repository.refresh()
        assertEquals(2, requests)
    }

    @Test
    fun recoversPersistedOutboxDuringNetworkFailure() = runBlocking {
        var fail = false
        val repository = repository({
            if (fail) throw IOException("Offline")
            StoreResult(listOf(offer()), true)
        })
        repository.refresh()
        val stored = repository.state.first().offers.single()
        database.watchDao().enqueue(listOf(NotificationEvent(stored.campaignId)))
        repository.setNotificationsEnabled(true)
        fail = true
        repository.refresh()
        awaitNotifications(1)
        assertEquals(1, database.watchDao().deliveredCount())
    }

    @Test
    fun deferredAlertIsDiscardedWhenTheStoreReportsTheGiveawayEnded() = runBlocking {
        val giveaway = offer().copy(end = null)
        var result = StoreResult(listOf(giveaway))
        val repository = repository({ result })
        repository.refresh()
        val stored = repository.state.first().offers.single()
        database.watchDao().enqueue(listOf(NotificationEvent(stored.campaignId)))
        repository.setNotificationsEnabled(true)
        result = StoreResult(emptyList(), ended = setOf(giveaway.id))
        repository.refresh()
        assertTrue(repository.findOffer(stored.campaignId)!!.ended)
        assertTrue(database.watchDao().event(stored.campaignId)!!.discarded)
        awaitNotifications(0)
        assertEquals(0, database.watchDao().deliveredCount())
    }

    @Test
    fun oldUnknownDeadlineOutboxWaitsForConfirmationAfterOfflinePeriod() = runBlocking {
        val giveaway = offer().copy(end = null)
        var time = now
        var offline = false
        val repository = repository({
            if (offline) throw IOException("Offline")
            StoreResult(listOf(giveaway))
        }, { time })
        repository.refresh()
        val stored = repository.state.first().offers.single()
        database.watchDao().enqueue(listOf(NotificationEvent(stored.campaignId)))
        repository.setNotificationsEnabled(true)
        time += 7 * 60 * 60 * 1000L
        offline = true
        repository.refresh()
        awaitNotifications(0)
        offline = false
        repository.refresh()
        awaitNotifications(1)
        assertEquals(1, database.watchDao().deliveredCount())
    }

    @Test
    fun emptyCountryRecheckDoesNotConfirmAnOldPendingOffer() = runBlocking {
        var offers = listOf(offer())
        val repository = repository({ StoreResult(offers, true) })
        repository.refresh()
        val stored = repository.state.first().offers.single()
        database.watchDao().enqueue(listOf(NotificationEvent(stored.campaignId)))
        repository.setNotificationsEnabled(true)
        repository.setCountry("RU")
        repository.setCountry("US")
        offers = emptyList()
        assertEquals(ScanOutcome.SUCCESS, repository.refresh())
        awaitNotifications(0)
        assertEquals(0, database.watchDao().deliveredCount())
        offers = listOf(offer())
        assertEquals(ScanOutcome.SUCCESS, repository.refresh())
        awaitNotifications(1)
        assertEquals(1, database.watchDao().deliveredCount())
        repository.refresh()
        assertEquals(1, database.watchDao().deliveredCount())
    }

    @Test
    fun oldRegionOutboxRequiresFreshConfirmationAndExpiredOfferDoesNotNotify() = runBlocking {
        var fail = false
        val repository = repository({
            if (fail) throw IOException("Offline")
            StoreResult(listOf(offer()), true)
        })
        repository.refresh()
        val stored = repository.state.first().offers.single()
        database.watchDao().enqueue(listOf(NotificationEvent(stored.campaignId)))
        repository.setNotificationsEnabled(true)
        repository.setCountry("RU")
        repository.setCountry("US")
        fail = true
        repository.refresh()
        awaitNotifications(0)
        fail = false
        val expiredRepository = repository(
            { StoreResult(listOf(offer()), true) }, { now + 172_800_000 })
        expiredRepository.refresh()
        awaitNotifications(0)
        assertEquals(0, database.watchDao().deliveredCount())
    }

    @Test
    fun oneStoreFailurePreservesOtherStoresAndRetrySkipsSuccessfulSources() = runBlocking {
        database.watchDao().initialize(WatchSettings(stores = "epic,steam,gog"))
        val requests = mutableMapOf("epic" to 0, "steam" to 0, "gog" to 0)
        val providers = requests.keys.associateWith { store ->
            StoreProvider { _, _, _ ->
                requests[store] = requests.getValue(store) + 1
                if (store == "steam") throw IOException("Steam unavailable")
                StoreResult(listOf(offer().copy(id = "$store:test")), requests = 1)
            }
        }
        val repository = AppRepository(context, database, providers)
        assertEquals(ScanOutcome.RETRY, repository.refresh())
        val state = repository.state.first()
        assertEquals(setOf("epic", "gog"), state.offers.map { it.store }.toSet())
        assertEquals(3, state.checks.size)
        repository.refresh(backgroundRetry = true)
        assertEquals(1, requests["epic"])
        assertEquals(1, requests["gog"])
        assertEquals(2, requests["steam"])
    }

    @Test
    fun partialCoverageIsVisibleWithoutAnAutomaticRetryLoop() = runBlocking {
        val repository = repository({ StoreResult(listOf(offer()), complete = false, details = "catalog page limit") })
        assertEquals(ScanOutcome.SUCCESS, repository.refresh())
        val state = repository.state.first()
        assertFalse(state.complete)
        assertEquals("partial", state.error)
        assertEquals("catalog page limit", state.checks.single().details)
        assertEquals(1, state.offers.size)
    }

    @Test
    fun claimSuppressesOnlyItsGameAndSurvivesRepositoryRecreation() = runBlocking {
        val second = offer().copy(id = "epic:fixture:456", title = "Second game")
        val repository = repository({ StoreResult(listOf(offer(), second)) })
        repository.setNotificationsEnabled(true)
        repository.refresh()
        val first = repository.state.first().offers.first { it.id.endsWith("123") }
        awaitNotifications(2)
        repository.setClaimed(first, true)
        awaitNotifications(1)
        assertEquals(first.gameKey, repository.state.first().claimedIds.single())
        val recreated = repository({ StoreResult(listOf(offer(), second)) })
        assertEquals(first.gameKey, recreated.state.first().claimedIds.single())
        recreated.refresh()
        awaitNotifications(1)
        recreated.setClaimed(first, false)
        assertTrue(recreated.state.first().claimedIds.isEmpty())
    }

    @Test
    fun unknownDeadlineKeepsCampaignAndClaimAcrossRepeatAndNewCampaignAfterEnd() = runBlocking {
        var result = StoreResult(listOf(offer().copy(start = null, end = null)))
        val repository = repository({ result })
        repository.refresh()
        val original = repository.state.first().offers.single()
        repository.setClaimed(original, true)
        repository.refresh()
        assertEquals(original.campaignId, repository.state.first().offers.single().campaignId)
        result = StoreResult(emptyList(), ended = setOf(original.id))
        repository.refresh()
        assertTrue(repository.state.first().history.single().ended)
        result = StoreResult(listOf(offer().copy(start = null, end = null)))
        repository.refresh()
        val state = repository.state.first()
        assertEquals(2, state.history.size)
        assertEquals(1, state.history.count { it.activeAt(now) })
        assertTrue(original.gameKey in state.claimedIds)
    }

    @Test
    fun quietHoursCrossMidnightAndFollowCurrentTimezone() = runBlocking {
        var time = Instant.parse("2026-11-19T20:30:00Z").toEpochMilli()
        var timezone = ZoneId.of("Europe/Moscow")
        database.watchDao().initialize(WatchSettings(stores = "epic"))
        val repository = AppRepository(context, database,
            mapOf("epic" to StoreProvider { _, _, _ ->
                StoreResult(listOf(offer(end = time + 3 * 86_400_000).copy(start = time - 60_000)))
            }), { time }, { timezone })
        repository.setNotificationsEnabled(true)
        repository.setQuietHours(true, 22 * 60, 8 * 60)
        repository.refresh()
        awaitNotifications(0)
        timezone = ZoneId.of("America/New_York")
        repository.refresh()
        awaitNotifications(1)
        val settings = database.watchDao().settings()
        assertTrue(AppRepository.isQuiet(settings, Instant.parse("2026-11-20T04:59:59Z").toEpochMilli(), ZoneId.of("Europe/Moscow")))
        assertFalse(AppRepository.isQuiet(settings, Instant.parse("2026-11-20T05:00:00Z").toEpochMilli(), ZoneId.of("Europe/Moscow")))
    }

    @Test
    fun reminderUsesFreshCachedDeadlineWhenSearchOmitsTheGame(): Unit = runBlocking {
        var time = now
        var result = StoreResult(listOf(offer(end = now + 4 * 60 * 60 * 1000L).copy(ratingValue = 3.64, epicRatingCheckedAt = now)))
        val repository = repository({ result }, { time })
        repository.setLanguage("ru")
        repository.setNotificationsEnabled(true)
        repository.refresh()
        assertEquals(1, database.watchDao().deliveredCount())
        val rating = "Оценка игроков Epic: 3,6 из 5"
        assertTrue(awaitNotifications(1).single().notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains(rating) == true)
        assertEquals(3.64, repository.state.first().history.single().ratingValue!!, 0.0)
        notifications.cancelAll()
        time += 2 * 60 * 60 * 1000L
        result = StoreResult(emptyList())
        repository.refresh()
        val alert = awaitNotifications(1).single()
        assertEquals(REMINDERS_CHANNEL, alert.notification.channelId)
        assertTrue(alert.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.contains(rating) == true)
        assertEquals(2, database.watchDao().deliveredCount())
        repository.refresh()
        assertEquals(2, database.watchDao().deliveredCount())
        repository.setReminders(false)
        awaitNotifications(0)
    }

    @Test
    fun unknownDeadlineNeverCreatesReminderAndDisablingStoresCancelsSchedule() = runBlocking {
        val repository = repository({ StoreResult(listOf(offer().copy(end = null))) })
        repository.setNotificationsEnabled(true)
        repository.refresh()
        repository.refresh()
        assertEquals(1, database.watchDao().deliveredCount())
        assertNull(database.watchDao().event(repository.state.first().offers.single().campaignId, "reminder"))
        repository.schedule().join()
        repository.setStores(emptySet())
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppRepository.PERIODIC_WORK_NAME).get(10, TimeUnit.SECONDS)
        assertTrue(work.all { it.state.isFinished })
        assertEquals(ScanOutcome.SUCCESS, repository.refresh())
        assertTrue(repository.state.first().offers.isEmpty())
        assertEquals(1, repository.state.first().history.size)
    }

    @Test
    fun simulatedPermissionGateKeepsOutboxPendingUntilGrantThenDeliversOnce() = runBlocking {
        if (Build.VERSION.SDK_INT < 33) return@runBlocking
        // Only the permission response is controlled. Room, notification publishing, and the
        // system NotificationManager are real. Runtime permission denial is tested through the UI.
        var granted = false
        val permissionContext = object : ContextWrapper(context) {
            override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
                if (permission == Manifest.permission.POST_NOTIFICATIONS) {
                    return if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                }
                return super.checkPermission(permission, pid, uid)
            }
        }
        database.watchDao().initialize(WatchSettings(stores = "epic"))
        val repository = AppRepository(permissionContext, database,
            mapOf("epic" to StoreProvider { _, _, _ -> StoreResult(listOf(offer())) }))
        repository.setNotificationsEnabled(true)
        repository.refresh()
        assertEquals(1, repository.state.first().offers.size)
        assertEquals(0, database.watchDao().deliveredCount())
        awaitNotifications(0)
        assertTrue(database.watchDao().pendingEvents().any { it.type == "new" })
        granted = true
        repository.refresh()
        assertEquals(1, database.watchDao().deliveredCount())
        awaitNotifications(1)
        repository.refresh()
        assertEquals(1, database.watchDao().deliveredCount())
        awaitNotifications(1)
    }


    @Test
    fun futureOfferNeedsConfirmationAndOnlyNotifiesOnceActive() = runBlocking {
        var time = now
        val future = offer().copy(start = now + 60_000, end = now + 86_400_000)
        var result = StoreResult(emptyList(), upcoming = listOf(future))
        val repository = repository({ result }, { time })
        repository.setNotificationsEnabled(true)
        repository.refresh()
        val campaign = repository.state.first().upcoming.single()
        assertTrue(repository.state.first().offers.isEmpty())
        assertTrue(repository.state.first().history.isEmpty())
        awaitNotifications(0)
        time += 61_000
        assertFalse(campaign.activeAt(time))
        result = StoreResult(listOf(future))
        repository.refresh()
        val active = repository.state.first().offers.single()
        assertEquals(campaign.campaignId, active.campaignId)
        assertTrue(repository.state.first().upcoming.isEmpty())
        awaitNotifications(1)
        repository.act(active, "read")
        repository.refresh()
        assertTrue(repository.state.first().offers.single().isRead)
        assertEquals(1, database.watchDao().deliveredCount())
    }

    @Test
    fun filtersAndTimedMarksApplyToBackgroundAlerts() = runBlocking {
        var time = now
        val item = offer(now + 5 * 86_400_000L).copy(contentType = "dlc", genres = listOf("RPG"))
        val repository = repository({ StoreResult(listOf(item)) }, { time })
        repository.setNotificationsEnabled(true)
        repository.refresh()
        awaitNotifications(0)
        repository.setFilters(FilterPreferences(ignoreDlc = false))
        repository.refresh()
        val active = repository.state.first().offers.single()
        awaitNotifications(1)
        repository.act(active, "snooze")
        repository.refresh()
        awaitNotifications(0)
        time += 86_400_001
        repository.refresh()
        awaitNotifications(1)
        repository.act(active, "hide")
        assertTrue(repository.state.first().marks.single().suppresses(active, time))
        awaitNotifications(0)
        repository.restoreMark(active.gameKey)
        repository.act(active, "ignore")
        repository.clearHistory()
        assertTrue(repository.state.first().history.isEmpty())
        repository.refresh()
        assertEquals(1, repository.state.first().offers.size)
        assertTrue(repository.state.first().marks.single().ignored)
        repository.clearMarks()
        assertTrue(repository.state.first().marks.isEmpty())
        assertEquals(1, database.watchDao().deliveredCount())
    }

    @Test
    fun pauseAndResumePreserveStoreSelection() = runBlocking {
        var calls = 0
        val repository = repository({ calls++; StoreResult(listOf(offer())) })
        repository.setIntervalMinutes(15)
        repository.setTracking(false)
        repository.refresh()
        assertEquals(0, calls)
        assertEquals(setOf("epic"), repository.state.first().selectedStores)
        repository.setTracking(true)
        repository.refresh()
        assertEquals(1, calls)
        assertEquals(15, repository.state.first().intervalMinutes)
    }

    @Test
    fun undoRestoresExistingMarksAndThemeSurvivesSettingsChanges() = runBlocking {
        val repository = repository({ StoreResult(listOf(offer())) })
        repository.refresh()
        val active = repository.state.first().offers.single()
        repository.setTheme("dark")
        repository.setSort("title")
        assertEquals("dark", repository.state.first().settings.theme)
        val snoozeUndo = repository.act(active, "snooze")!!
        assertNull(snoozeUndo.previous)
        val snoozed = database.watchDao().mark(active.gameKey)
        val previous = repository.act(active, "claim")
        assertEquals(snoozed, previous!!.previous)
        assertTrue(repository.undoAction(active, previous))
        assertEquals(snoozed, database.watchDao().mark(active.gameKey))
        assertFalse(repository.undoAction(active, previous))
        val restoreUndo = repository.act(active, "restore")!!
        assertTrue(repository.undoAction(active, restoreUndo))
        assertEquals(snoozed, database.watchDao().mark(active.gameKey))
        repository.clearMarks()
        val claimUndo = repository.act(active, "claim")!!
        assertTrue(repository.undoAction(active, claimUndo))
        assertNull(database.watchDao().mark(active.gameKey))
    }

    @Test
    fun staleUndoPreservesLaterActionsClearsImportsAndSnoozeUpdates() = runBlocking {
        val repository = repository({ StoreResult(listOf(offer())) })
        repository.refresh()
        val active = repository.state.first().offers.single()
        val claim = repository.act(active, "claim")!!
        repository.act(active, "ignore")
        assertFalse(repository.undoAction(active, claim))
        assertTrue(database.watchDao().mark(active.gameKey)!!.ignored)

        val restore = repository.act(active, "restore")!!
        repository.clearMarks()
        assertFalse(repository.undoAction(active, restore))
        assertNull(database.watchDao().mark(active.gameKey))

        val snooze = repository.act(active, "snooze")!!
        val written = requireNotNull(snooze.written)
        val changed = written.copy(snoozedUntil = written.snoozedUntil + 1)
        database.watchDao().putMarks(listOf(changed))
        assertFalse(repository.undoAction(active, snooze))
        assertEquals(changed, database.watchDao().mark(active.gameKey))

        val latest = repository.act(active, "claim")!!
        repository.importBackup(BackupData(WatchSettings(stores = "epic", trackFreeToKeep = false),
            listOf(latest.written!!), emptyList(), "", false), false)
        assertFalse(repository.undoAction(active, latest))
        assertTrue(database.watchDao().mark(active.gameKey)!!.claimed)
    }

    @Test
    fun migrationPreservesBetaSettingsAndMarks() = runBlocking {
        val name = "beta-migration-test.db"
        context.deleteDatabase(name)
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("bz.fgw.android.data.WatchDatabase/3.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = json.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = json.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO settings VALUES(1,'DE',7,1,'steam,gog','en',3,1,1,1320,480,1,30,5,'{}',1,1,'title',1,90,14,1)")
            old.execSQL("INSERT INTO personal_marks VALUES('steam:app:123','Saved game','https://store.steampowered.com/app/123/',1,0,'',0,42,0)")
            old.version = 3
        }
        val migrated = Room.databaseBuilder<WatchDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver()).addMigrations(MIGRATION_3_4, MIGRATION_4_5)
            .setQueryCoroutineContext(Dispatchers.IO).build()
        try {
            val dao = migrated.watchDao()
            val settings = dao.settings()
            assertEquals("system", settings.theme)
            assertEquals("DE", settings.country)
            assertEquals(30, settings.intervalMinutes)
            assertEquals(5, settings.reminderHours)
            assertEquals("title", settings.sortBy)
            assertTrue(settings.compactView)
            assertTrue(settings.quietHoursEnabled)
            assertTrue(dao.mark("steam:app:123")!!.claimed)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migrationPreservesVersionOneSettingsOffersAndDeliveredEvents() = runBlocking {
        val name = "migration-test.db"
        context.deleteDatabase(name)
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("bz.fgw.android.data.WatchDatabase/1.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
            val entities = json.getJSONArray("entities")
            for (index in 0 until entities.length()) {
                val entity = entities.getJSONObject(index)
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = json.getJSONArray("setupQueries")
            for (index in 0 until setup.length()) old.execSQL(setup.getString(index))
            old.execSQL("INSERT INTO settings VALUES(1,'RU',7,1)")
            old.execSQL("INSERT INTO offers VALUES('old-campaign','epic:test:1','Old game','https://store.epicgames.com/en-US/p/old-game',1,9999999999999,'RU',3,7)")
            old.execSQL("INSERT INTO checks VALUES('RU',2,3,1,NULL,1,NULL,7)")
            old.execSQL("INSERT INTO notification_events VALUES('old-campaign',1,0)")
            old.version = 1
        }
        val migrated = Room.databaseBuilder<WatchDatabase>(context, name)
            .setDriver(AndroidSQLiteDriver())
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
        try {
            val dao = migrated.watchDao()
            assertEquals("RU", dao.settings().country)
            assertEquals("system", dao.settings().theme)
            assertEquals(setOf("epic"), dao.settings().selectedStores())
            assertEquals(7L, dao.settings().revision)
            assertEquals("Old game", dao.offer("old-campaign")?.title)
            assertEquals("epic", dao.offer("old-campaign")?.store)
            assertEquals(1, dao.deliveredCount())
            assertEquals(3L, dao.check("RU")?.lastSuccess)
            dao.putMarks(listOf(PersonalMark("epic:epic:test:1", "Old game", "", claimed = true)))
            assertTrue(dao.mark("epic:epic:test:1")!!.claimed)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
