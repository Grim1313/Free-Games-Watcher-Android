package bz.fgw.android.data

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import bz.fgw.android.providers.StoreOffer
import bz.fgw.android.providers.StoreProvider
import bz.fgw.android.providers.StoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDeviceTest {
    private lateinit var database: WatchDatabase
    private lateinit var repository: AppRepository
    private lateinit var context: Context
    private val now = System.currentTimeMillis()
    private val offer = StoredOffer("backup-campaign", "epic:fixture:123", "Backup game",
        "https://store.epicgames.com/en-US/p/test-game", now - 60_000, now + 86_400_000, "US", now,
        store = "epic", imported = true, confirmedRevision = -1)

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelUniqueWork(AppRepository.PERIODIC_WORK_NAME).result.get()
        database = Room.inMemoryDatabaseBuilder<WatchDatabase>(context).setDriver(AndroidSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO).build()
        database.watchDao().initialize(WatchSettings(stores = "epic"))
        repository = AppRepository(context, database, mapOf("epic" to StoreProvider { _, _, _ ->
            StoreResult(listOf(StoreOffer(offer.id, offer.title, offer.url, offer.start, offer.end)))
        }))
    }

    @After fun tearDown() {
        WorkManager.getInstance(context).cancelUniqueWork(AppRepository.PERIODIC_WORK_NAME).result.get()
        database.close()
    }

    @Test fun importedHistoryIsVerifiedWithoutNewOrReminderAlerts() = runBlocking {
        val data = BackupData(WatchSettings(stores = "epic", notificationsEnabled = true), emptyList(), listOf(offer), "", true)
        repository.importBackup(data, true)
        assertTrue(database.watchDao().offer(offer.campaignId)!!.imported)
        assertTrue(database.watchDao().pendingEvents().isEmpty())
        val delivered = database.watchDao().deliveredCount()
        repository.refresh()
        val confirmed = database.watchDao().allOffers().single()
        assertFalse(confirmed.imported)
        assertTrue(confirmed.activeAt(now))
        assertEquals(delivered, database.watchDao().deliveredCount())
        assertTrue(database.watchDao().pendingEvents().isEmpty())
    }

    @Test fun mergePreservesLocalRepeatsAndMapsHiddenCampaignToLocalRecord() = runBlocking {
        val older = offer.copy(campaignId = "older", start = now - 5 * 86_400_000, end = now - 4 * 86_400_000, imported = false, ended = true)
        val local = offer.copy(campaignId = "local", title = "Local title", imported = false)
        database.watchDao().saveOffers(listOf(older, local))
        val mark = PersonalMark(offer.gameKey, offer.title, offer.url, hiddenCampaign = offer.campaignId)
        val data = BackupData(WatchSettings(stores = "epic"), listOf(mark), listOf(offer), "", true)
        repository.importBackup(data, true)
        repository.importBackup(data, true)
        assertEquals(2, database.watchDao().allOffers().size)
        assertEquals("Local title", database.watchDao().offer("local")!!.title)
        assertEquals("local", database.watchDao().allMarks().single().hiddenCampaign)
    }

    @Test fun settingsOnlyImportReplacesMarksAndReschedulesTracking() = runBlocking {
        database.watchDao().saveOffers(listOf(offer))
        database.watchDao().putMarks(listOf(PersonalMark(offer.gameKey, offer.title, offer.url, claimed = true)))
        repository.importBackup(BackupData(WatchSettings(stores = "epic", trackFreeToKeep = false), emptyList(), emptyList(), "", false), false)
        assertEquals(1, database.watchDao().allOffers().size)
        assertTrue(database.watchDao().allMarks().isEmpty())
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppRepository.PERIODIC_WORK_NAME).get().all { it.state.isFinished })
        repository.importBackup(BackupData(WatchSettings(stores = "epic", intervalMinutes = 30), emptyList(), emptyList(), "", false), false)
        assertEquals(1, WorkManager.getInstance(context).getWorkInfosForUniqueWork(AppRepository.PERIODIC_WORK_NAME).get().count { !it.state.isFinished })
    }

    @Test fun standaloneHiddenMarkUsesKnownDatesAndKeepsUnknownCampaignIdentity() = runBlocking {
        database.watchDao().saveOffers(listOf(offer.copy(campaignId = "local", imported = false)))
        val mark = PersonalMark(offer.gameKey, offer.title, offer.url, hiddenCampaign = "browser-campaign", updatedAt = now)
        val backup = BackupData(WatchSettings(stores = "epic"), listOf(mark), emptyList(), "", false)
        repository.importBackup(backup, false)
        assertEquals("local", database.watchDao().allMarks().single().hiddenCampaign)
        database.watchDao().saveOffers(listOf(offer.copy(campaignId = "local", start = null, end = null, imported = false)))
        repository.importBackup(backup, false)
        assertEquals("browser-campaign", database.watchDao().allMarks().single().hiddenCampaign)
    }
}
