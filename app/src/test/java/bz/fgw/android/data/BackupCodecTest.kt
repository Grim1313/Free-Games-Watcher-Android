package bz.fgw.android.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    @Test fun epicScoresRoundTripInBrowserFormatWithoutReviewCounts() {
        val epic = offer.copy(id = "epic:ns:game", url = "https://store.epicgames.com/en-US/p/game", store = "epic",
            ratingPercent = null, reviewCount = null, ratingValue = 3.64, epicRatingCheckedAt = 1499)
        val root = JSONObject(BackupCodec.encode(WatchSettings(), emptyList(), listOf(epic, offer), true))
        val entry = root.getJSONArray("history").getJSONObject(0)
        assertEquals(3.64, entry.getDouble("epicRating"), 0.0)
        assertEquals(0, entry.getInt("gogRating"))
        assertFalse(root.getJSONArray("history").getJSONObject(1).has("epicRating"))
        entry.put("reviewTotal", 92)
        val imported = BackupCodec.decode(root.toString()).history.first()
        assertEquals(epic.ratingValue, imported.ratingValue)
        assertEquals(1499L, imported.epicRatingCheckedAt)
        assertNull(imported.reviewCount)
        entry.put("epicRating", "3.64")
        assertNull(BackupCodec.decode(root.toString()).history.first().ratingValue)
    }

    private val offer = StoredOffer(
        "US:steam:app:123:campaign", "app:123", "Test game", "https://store.steampowered.com/app/123/",
        1000, 2000, "US", 1500, store = "steam", ratingPercent = 92, reviewCount = 120,
        originalPrice = 1999, currency = "USD", priceText = "$19.99", genres = listOf("RPG"), platforms = listOf("windows"),
    )

    @Test fun roundTripPreservesSettingsMarksAndRepeatedCampaigns() {
        val settings = WatchSettings(country = "DE", stores = "steam,gog", language = "en", intervalMinutes = 30,
            compactView = true, reminderHours = 5, theme = "dark", filtersJson = FilterPreferences(minRating = 85, blockedAppIds = setOf("456")).toJson())
        val mark = PersonalMark(offer.gameKey, offer.title, offer.url, claimed = true, hiddenCampaign = offer.campaignId, snoozedUntil = 9000, updatedAt = 2000)
        val second = offer.copy(campaignId = "second", start = 3000, end = 4000)
        val encoded = BackupCodec.encode(settings, listOf(mark), listOf(offer, second), true)
        val parsed = BackupCodec.decode(encoded)
        assertEquals(30, parsed.settings.intervalMinutes)
        assertEquals("DE", parsed.settings.country)
        assertEquals("dark", parsed.settings.theme)
        assertEquals(setOf("456"), FilterPreferences.fromJson(parsed.settings.filtersJson).blockedAppIds)
        assertEquals(listOf(mark), parsed.marks)
        assertEquals(2, parsed.history.size)
        assertEquals(1, JSONObject(encoded).getJSONArray("history").length())
        assertTrue(parsed.history.all { it.imported && it.isRead && it.confirmedRevision == -1L })
        assertEquals(1999L, parsed.history.first().originalPrice)
        assertEquals(92, parsed.history.first().ratingPercent)
    }

    @Test fun readsBrowserBackupWithoutAndroidFieldsAndSanitizesUrls() {
        val source = JSONObject(BackupCodec.encode(WatchSettings(), emptyList(), listOf(offer), true))
        source.remove("androidHistory")
        val history = source.getJSONArray("history").getJSONObject(0)
        history.put("url", "https://attacker.example/steal").put("claimUrl", "javascript:alert(1)")
        history.put("headerImage", "https://attacker.example/image.png")
        val parsed = BackupCodec.decode(source.toString()).history.single()
        assertEquals(offer.url, parsed.url)
        assertNull(parsed.imageUrl)
        assertFalse(parsed.activeAt(1500))
    }

    @Test fun importsFixtureProducedByBrowserExportBackup() {
        val json = requireNotNull(javaClass.getResourceAsStream("/backup/browser-v3.json")).bufferedReader().use { it.readText() }
        val parsed = BackupCodec.decode(json)
        assertEquals("DE", parsed.settings.country)
        assertEquals("system", parsed.settings.theme)
        assertEquals(30, parsed.settings.intervalMinutes)
        assertEquals("steam:app:123", parsed.marks.single().gameKey)
        assertTrue(parsed.marks.single().claimed)
        assertEquals("19,99 €", parsed.history.single().priceText)
        assertEquals(92, parsed.history.single().ratingPercent)
        assertEquals(120, parsed.history.single().reviewCount)
    }

    @Test fun browserScreenshotsTakePriorityAndUnsafeImagesFallBack() {
        val root = JSONObject(BackupCodec.encode(WatchSettings(), emptyList(), listOf(offer), true))
        val entry = root.getJSONArray("history").getJSONObject(0)
        val header = "https://shared.akamai.steamstatic.com/steam/apps/123/header.jpg"
        val screenshot = "https://shared.akamai.steamstatic.com/steam/apps/123/screenshot.jpg"
        entry.put("headerImage", header).put("screenshotThumbnail", screenshot)
        assertEquals(screenshot, BackupCodec.decode(root.toString()).history.single().imageUrl)
        entry.put("screenshotThumbnail", "https://example.com/untrusted.jpg")
        assertEquals(header, BackupCodec.decode(root.toString()).history.single().imageUrl)
    }

    @Test fun disabledHistoryIsExcludedFromExport() {
        val encoded = BackupCodec.encode(WatchSettings(historyEnabled = false), emptyList(), listOf(offer), true)
        assertFalse(BackupCodec.decode(encoded).hasHistory)
        val omitted = BackupCodec.encode(WatchSettings(), emptyList(), listOf(offer.copy(inHistory = false)), true)
        assertTrue(BackupCodec.decode(omitted).history.isEmpty())
    }

    @Test fun settingsOnlyAndLegacyBackupsHaveNoHistory() {
        for (version in 1..3) {
            val root = JSONObject(BackupCodec.encode(WatchSettings(stores = ""), emptyList(), listOf(offer), false)).put("version", version)
            val parsed = BackupCodec.decode(root.toString())
            assertFalse(parsed.hasHistory)
            assertTrue(parsed.history.isEmpty())
            assertTrue(parsed.settings.selectedStores().isEmpty())
        }
    }

    @Test fun rejectsForeignFormatInvalidIdsAndOversizedHistory() {
        val base = BackupCodec.encode(WatchSettings(), emptyList(), emptyList(), false)
        assertRejected(JSONObject(base).put("format", "unrelated"))
        assertRejected(JSONObject(base).put("userActions", JSONObject().put("steam:app:123", JSONObject())))
        assertRejected(JSONObject(base).put("history", JSONArray(List(401) { JSONObject() })))
        assertRejected(JSONObject(base).put("history", JSONArray().put(JSONObject().put("stableId", "app:123"))))
    }

    @Test fun rejectsInvalidTimestampsAndMalformedNativeHistory() {
        val base = JSONObject(BackupCodec.encode(WatchSettings(), emptyList(), listOf(offer), true))
        base.getJSONArray("history").getJSONObject(0).put("startsAt", -1)
        assertRejected(base)
        val malformed = JSONObject(BackupCodec.encode(WatchSettings(), emptyList(), listOf(offer), true)).put("androidHistory", "bad")
        assertRejected(malformed)
    }

    @Test fun disabledTrackingRemainsPausedAfterTransfer() {
        val root = JSONObject(BackupCodec.encode(WatchSettings(), emptyList(), emptyList(), false))
        root.getJSONObject("settings").put("trackFreeToKeep", false)
        assertFalse(BackupCodec.decode(root.toString()).settings.trackFreeToKeep)
        assertEquals(3, BackupCodec.decode(root.toString()).settings.selectedStores().size)
    }

    private fun assertRejected(root: JSONObject) {
        assertTrue(runCatching { BackupCodec.decode(root.toString()) }.isFailure)
    }
}
