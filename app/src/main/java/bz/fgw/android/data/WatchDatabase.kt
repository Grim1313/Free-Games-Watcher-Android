package bz.fgw.android.data

import androidx.room3.*
import androidx.room3.migration.Migration
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "settings")
data class WatchSettings(
    @PrimaryKey val id: Int = 1,
    val country: String = "US",
    val revision: Long = 0,
    val notificationsEnabled: Boolean = false,
    val stores: String = "epic,steam,gog",
    val language: String = "ru",
    val intervalHours: Int = 3,
    val remindersEnabled: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 8 * 60,
) {
    fun selectedStores(): Set<String> = stores.split(',').filter { it in SUPPORTED_STORES }.toSet()
}

val SUPPORTED_STORES = setOf("epic", "steam", "gog")

@Entity(tableName = "offers")
data class StoredOffer(
    @PrimaryKey val campaignId: String,
    val id: String,
    val title: String,
    val url: String,
    val start: Long?,
    val end: Long?,
    val country: String,
    val lastConfirmed: Long,
    val confirmedRevision: Long = 0,
    val store: String = "epic",
    val ended: Boolean = false,
    val imageUrl: String? = null,
) {
    val gameKey: String get() = "$store:$id"
    fun activeAt(now: Long): Boolean = !ended && (start == null || start <= now) && (end == null || end > now)
}

@Entity(tableName = "checks", primaryKeys = ["store", "country"])
data class CheckStatus(
    val country: String,
    val lastAttempt: Long,
    val lastSuccess: Long? = null,
    val complete: Boolean = false,
    val error: String? = null,
    val requestCount: Int = 0,
    val retryAt: Long? = null,
    val confirmedRevision: Long? = null,
    val store: String = "epic",
    val details: String = "",
)

@Entity(tableName = "notification_events", primaryKeys = ["campaignId", "type"])
data class NotificationEvent(
    val campaignId: String,
    val delivered: Boolean = false,
    val discarded: Boolean = false,
    val type: String = "new",
)

@Entity(tableName = "claims")
data class Claim(@PrimaryKey val gameKey: String, val claimedAt: Long)

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun initialize(settings: WatchSettings)

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun settings(): WatchSettings

    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<WatchSettings?>

    @Upsert suspend fun saveSettings(settings: WatchSettings)

    @Query("SELECT * FROM offers ORDER BY lastConfirmed DESC, title")
    fun observeOffers(): Flow<List<StoredOffer>>

    @Query("SELECT * FROM offers WHERE store = :store AND country = :country ORDER BY lastConfirmed DESC")
    suspend fun knownOffers(store: String, country: String): List<StoredOffer>

    @Query("SELECT * FROM offers WHERE store = :store")
    suspend fun storeOffers(store: String): List<StoredOffer>

    @Query("SELECT * FROM offers WHERE store = :store AND id = :id")
    suspend fun gameOffers(store: String, id: String): List<StoredOffer>

    @Query("SELECT * FROM checks")
    fun observeChecks(): Flow<List<CheckStatus>>

    @Query("SELECT * FROM checks WHERE country = :country AND store = :store")
    suspend fun check(country: String, store: String = "epic"): CheckStatus?

    @Upsert suspend fun saveCheck(check: CheckStatus)
    @Upsert suspend fun saveOffers(offers: List<StoredOffer>)

    @Query("SELECT * FROM offers WHERE campaignId = :campaignId")
    suspend fun offer(campaignId: String): StoredOffer?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(events: List<NotificationEvent>)

    @Query("SELECT * FROM notification_events WHERE delivered = 0 AND discarded = 0 ORDER BY type DESC")
    suspend fun pendingEvents(): List<NotificationEvent>

    @Query("SELECT * FROM notification_events WHERE campaignId = :campaignId AND type = :type")
    suspend fun event(campaignId: String, type: String = "new"): NotificationEvent?

    @Query("UPDATE notification_events SET delivered = 1 WHERE campaignId = :campaignId AND type = :type")
    suspend fun markDelivered(campaignId: String, type: String = "new")

    @Query("UPDATE notification_events SET discarded = 1 WHERE campaignId = :campaignId AND type = :type")
    suspend fun markDiscarded(campaignId: String, type: String = "new")

    @Query("SELECT COUNT(*) FROM notification_events WHERE delivered = 1")
    suspend fun deliveredCount(): Int

    @Query("SELECT gameKey FROM claims")
    fun observeClaims(): Flow<List<String>>

    @Query("SELECT EXISTS(SELECT 1 FROM claims WHERE gameKey = :gameKey)")
    suspend fun isClaimed(gameKey: String): Boolean

    @Upsert suspend fun claim(claim: Claim)
    @Query("DELETE FROM claims WHERE gameKey = :gameKey")
    suspend fun unclaim(gameKey: String)
}

@Database(
    entities = [WatchSettings::class, StoredOffer::class, CheckStatus::class, NotificationEvent::class, Claim::class],
    version = 2,
    exportSchema = true,
)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao
}

val MIGRATION_1_2 = Migration(1, 2) { db ->
    db.execSQL("CREATE TABLE settings_new (id INTEGER NOT NULL PRIMARY KEY, country TEXT NOT NULL, revision INTEGER NOT NULL, notificationsEnabled INTEGER NOT NULL, stores TEXT NOT NULL, language TEXT NOT NULL, intervalHours INTEGER NOT NULL, remindersEnabled INTEGER NOT NULL, quietHoursEnabled INTEGER NOT NULL, quietStartMinute INTEGER NOT NULL, quietEndMinute INTEGER NOT NULL)")
    // Existing installations retain the user's previously selected source.
    db.execSQL("INSERT INTO settings_new SELECT id,country,revision,notificationsEnabled,'epic','ru',3,1,0,1320,480 FROM settings")
    db.execSQL("DROP TABLE settings")
    db.execSQL("ALTER TABLE settings_new RENAME TO settings")
    db.execSQL("CREATE TABLE offers_new (campaignId TEXT NOT NULL PRIMARY KEY, id TEXT NOT NULL, title TEXT NOT NULL, url TEXT NOT NULL, start INTEGER, end INTEGER, country TEXT NOT NULL, lastConfirmed INTEGER NOT NULL, confirmedRevision INTEGER NOT NULL, store TEXT NOT NULL, ended INTEGER NOT NULL, imageUrl TEXT)")
    db.execSQL("INSERT INTO offers_new SELECT campaignId,id,title,url,start,end,country,lastConfirmed,confirmedRevision,'epic',0,NULL FROM offers")
    db.execSQL("DROP TABLE offers")
    db.execSQL("ALTER TABLE offers_new RENAME TO offers")
    db.execSQL("CREATE TABLE checks_new (country TEXT NOT NULL, lastAttempt INTEGER NOT NULL, lastSuccess INTEGER, complete INTEGER NOT NULL, error TEXT, requestCount INTEGER NOT NULL, retryAt INTEGER, confirmedRevision INTEGER, store TEXT NOT NULL, details TEXT NOT NULL, PRIMARY KEY(store,country))")
    db.execSQL("INSERT INTO checks_new SELECT country,lastAttempt,lastSuccess,complete,error,requestCount,retryAt,confirmedRevision,'epic','' FROM checks")
    db.execSQL("DROP TABLE checks")
    db.execSQL("ALTER TABLE checks_new RENAME TO checks")
    db.execSQL("CREATE TABLE notification_events_new (campaignId TEXT NOT NULL, delivered INTEGER NOT NULL, discarded INTEGER NOT NULL, type TEXT NOT NULL, PRIMARY KEY(campaignId,type))")
    db.execSQL("INSERT INTO notification_events_new SELECT campaignId,delivered,discarded,'new' FROM notification_events")
    db.execSQL("DROP TABLE notification_events")
    db.execSQL("ALTER TABLE notification_events_new RENAME TO notification_events")
    db.execSQL("CREATE TABLE claims (gameKey TEXT NOT NULL PRIMARY KEY, claimedAt INTEGER NOT NULL)")
}
