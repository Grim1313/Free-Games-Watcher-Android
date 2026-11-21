package bz.fgw.android.data

import org.junit.Assert.*
import org.junit.Test

class FilterPreferencesTest {
    private fun offer(store: String = "steam", id: String = "app:123") = StoredOffer(
        campaignId = "$store:$id:campaign", id = id, title = "A Test Game", url = "https://example.com",
        start = null, end = null, country = "US", lastConfirmed = 100, store = store,
    )

    @Test fun defaultsExcludeSupplementaryContentAndKeepPackages() {
        val preferences = FilterPreferences()
        for (type in listOf("dlc", "soundtrack", "demo", "tool")) assertFalse(preferences.accepts(offer().copy(contentType = type)))
        assertTrue(preferences.accepts(offer().copy(contentType = "package")))
        assertTrue(preferences.accepts(offer()))
    }

    @Test fun steamIdExclusionsDoNotHideOtherStores() {
        val preferences = FilterPreferences(blockedAppIds = setOf("123"))
        assertFalse(preferences.accepts(offer()))
        assertFalse(preferences.accepts(offer(id = "sub:123")))
        assertTrue(preferences.accepts(offer(store = "gog", id = "123")))
    }

    @Test fun ratingThresholdsApplyOnlyToRatedSteamGames() {
        val preferences = FilterPreferences(minReviews = 50, minRating = 80)
        assertTrue(preferences.accepts(offer()))
        assertFalse(preferences.copy(includeUnrated = false).accepts(offer()))
        assertFalse(preferences.accepts(offer().copy(reviewCount = 49, ratingPercent = 100)))
        assertFalse(preferences.accepts(offer().copy(reviewCount = 50, ratingPercent = 79)))
        assertTrue(preferences.accepts(offer().copy(reviewCount = 50, ratingPercent = 80)))
        assertTrue(preferences.copy(includeUnrated = false).accepts(offer(store = "gog")))
    }

    @Test fun genreAliasesMatchAndUnknownMetadataIsExcluded() {
        val preferences = FilterPreferences(genres = setOf("rpg"), platforms = setOf("windows"))
        assertTrue(preferences.accepts(offer(store = "gog").copy(genres = listOf("Role-playing"), platforms = listOf("windows"))))
        assertFalse(preferences.accepts(offer().copy(genres = listOf("RPG"))))
        assertFalse(preferences.accepts(offer().copy(platforms = listOf("windows"))))
    }

    @Test fun backupJsonNormalizesAndPreservesFilters() {
        val preferences = FilterPreferences.fromJson("""{"blockedAppIds":[123,"-1","bad"],"blockedKeywords":[" TEST "],"minRating":200,"minReviews":-1,"platforms":["windows","unknown"],"genres":["RPG"]}""")
        assertEquals(setOf("123"), preferences.blockedAppIds)
        assertEquals(100, preferences.minRating)
        assertEquals(0, preferences.minReviews)
        assertEquals(setOf("windows"), preferences.platforms)
        assertFalse(preferences.accepts(offer()))
        assertEquals(preferences, FilterPreferences.fromJson(preferences.toJson()))
        assertEquals(FilterPreferences(), FilterPreferences.fromJson("broken"))
    }

    @Test fun sortUsesDiscoveryTimeAndSteamRatingsWithOtherStoresByDeadline() {
        val old = offer().copy(firstSeen = 1, lastConfirmed = 900, ratingPercent = 70)
        val recent = offer(store = "gog").copy(firstSeen = 2, lastConfirmed = 200, ratingValue = 4.0)
        val unknown = offer(store = "epic").copy(firstSeen = 3)
        assertEquals(listOf(unknown, recent, old), sortOffers(listOf(old, recent, unknown), "newest"))
        val steamBest = offer(id = "app:456").copy(ratingPercent = 95)
        assertEquals(listOf(steamBest, old, offer()), sortOffers(listOf(offer(), old, steamBest), "rating"))
        val gogEnding = recent.copy(campaignId = "ending", end = 100, ratingValue = 2.0)
        assertEquals(listOf(gogEnding, recent), sortOffers(listOf(recent, gogEnding), "rating"))
        assertEquals(listOf(recent, old), sortOffers(listOf(old, recent.copy(end = 200)), "ending").map { it.copy(end = null) })
    }
}
