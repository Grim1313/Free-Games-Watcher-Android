package bz.fgw.android.data

import org.junit.Assert.*
import org.junit.Test

class OfferHistoryTest {
    private fun offer(country: String, start: Long? = 100, end: Long? = 200, seen: Long = 150) = StoredOffer(
        campaignId = "$country:$start:$seen", id = "game", title = "Game", url = "https://example.com/game",
        start = start, end = end, country = country, lastConfirmed = seen,
    )

    @Test fun regionalCopiesUseCurrentCountryAndPreserveFirstSeenAndReadState() {
        val us = offer("US", seen = 120).copy(isRead = true)
        val kz = offer("KZ", seen = 130)
        val ru = offer("RU", seen = 140)
        val history = offerHistory(listOf(us, kz, ru), "RU")
        assertEquals(1, history.size)
        assertEquals(ru.campaignId, history.single().campaignId)
        assertEquals(120L, history.single().firstSeen)
        assertTrue(history.single().isRead)
        assertEquals(ru.campaignId, offerHistory(listOf(kz, ru, us), "DE").single().campaignId)
    }

    @Test fun endedCopiesAndExtendedDeadlinesAreCombined() {
        val us = offer("US").copy(ended = true)
        val kz = offer("KZ", end = 250).copy(ended = true)
        assertEquals(1, offerHistory(listOf(us, kz), "US").size)
    }

    @Test fun hiddenRegionalCopyRemainsAvailableUntilItsMarkIsRestored() {
        val us = offer("US", seen = 120)
        val ru = offer("RU", seen = 140)
        val mark = PersonalMark(us.gameKey, us.title, us.url, hiddenCampaign = us.campaignId)
        val history = offerHistory(listOf(us, ru), "RU", mapOf(mark.gameKey to mark.hiddenCampaign))
        assertEquals(us.campaignId, history.single().campaignId)
        assertTrue(mark.suppresses(history.single(), 150))
        assertEquals(1, history.count { it.campaignId == mark.hiddenCampaign })
        assertEquals(ru.campaignId, offerHistory(listOf(us, ru), "RU").single().campaignId)
    }

    @Test fun repeatedGiveawaysAndDifferentStoresRemainSeparate() {
        val first = offer("US")
        val repeat = offer("KZ", start = 200, end = 300, seen = 220)
        val differentStore = first.copy(campaignId = "steam", store = "steam")
        assertEquals(3, offerHistory(listOf(first, repeat, differentStore), "US").size)
        assertEquals(2, offerHistory(listOf(first, first.copy(campaignId = "repeat", end = 250)), "US").size)
    }

    @Test fun missingDatesUseObservedCampaignLifetime() {
        val us = offer("US", null, null, 120)
        val kz = offer("KZ", null, null, 130)
        assertEquals(1, offerHistory(listOf(us, kz), "KZ").size)
        val ended = us.copy(ended = true, lastConfirmed = 125)
        assertEquals(2, offerHistory(listOf(ended, kz), "KZ").size)
    }

    @Test fun staleUndatedCountryCannotBridgeRepeatedGiveaways() {
        val first = offer("US")
        val stale = offer("KZ", null, null, 150)
        val repeat = offer("RU", start = 300, end = 400, seen = 350)
        assertEquals(2, offerHistory(listOf(stale, repeat, first), "RU").size)
    }

    @Test fun clearedAndUpcomingRecordsStayOutOfHistory() {
        val old = offer("US").copy(inHistory = false)
        val upcoming = offer("KZ").copy(upcoming = true)
        assertTrue(offerHistory(listOf(old, upcoming), "US").isEmpty())
    }
}
