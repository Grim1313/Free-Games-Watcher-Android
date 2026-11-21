package bz.fgw.android.providers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class EpicGogProviderTest {
    private val now = Instant.parse("2026-10-27T12:00:00Z").toEpochMilli()
    private val epic = EpicProvider()
    private fun fixture(name: String) = JSONObject(requireNotNull(javaClass.getResource("/providers/$name")).readText())
    private fun catalog(vararg items: JSONObject, total: Int = items.size) = JSONObject().put("data", JSONObject()
        .put("Catalog", JSONObject().put("searchStore", JSONObject().put("elements", JSONArray(items.toList()))
            .put("paging", JSONObject().put("total", total)))))
    private fun mirror(item: JSONObject) = JSONObject().put("offers", JSONArray().put(item)).put("total", 1).put("page", 1).put("limit", 40)
    private fun weekly() = requireNotNull(javaClass.getResource("/epic/weekly.json")).readText()

    @Test fun realCatalogAndMirrorHaveOneIdentityAndDeadline() {
        val direct = epic.parseCatalog(catalog(fixture("epic-mafia-hotel.json")), "US", now)
        val fallback = epic.parseEgdata(mirror(fixture("egdata-mafia-hotel.json")), "US", now)
        assertTrue(direct.complete)
        assertTrue(fallback.complete)
        assertEquals(direct.offers.single().id, fallback.offers.single().id)
        assertEquals(direct.offers.single().end, fallback.offers.single().end)
        assertEquals("https://store.epicgames.com/en-US/p/mafia-hotel-eaea4c", direct.offers.single().url)
        assertNull(direct.offers.single().start)
        assertNotNull(fallback.offers.single().start)
    }

    @Test fun catalogRejectsPersonalVoucherAndPermanentFreePrice() {
        val item = fixture("epic-mafia-hotel.json")
        item.getJSONObject("price").getJSONObject("totalPrice").put("voucherDiscount", 199)
        assertTrue(epic.parseCatalog(catalog(item), "US", now).offers.isEmpty())
        item.getJSONObject("price").getJSONObject("totalPrice").put("originalPrice", 0)
        assertTrue(epic.parseCatalog(catalog(item), "US", now).offers.isEmpty())
    }

    @Test fun malformedNeighborPreservesConfirmedCatalogRecord() {
        val broken = fixture("epic-mafia-hotel.json").put("id", "broken")
        broken.getJSONObject("price").put("lineOffers", JSONArray())
        val result = epic.parseCatalog(catalog(fixture("epic-mafia-hotel.json"), broken), "US", now)
        assertFalse(result.complete)
        assertEquals(1, result.offers.size)
    }

    @Test fun endedCatalogWindowExplicitlyConfirmsEnd() {
        val result = epic.parseCatalog(catalog(fixture("epic-mafia-hotel.json")), "US", now + 10 * 86_400_000L)
        assertTrue(result.offers.isEmpty())
        assertEquals(1, result.ended.size)
    }

    @Test fun futureCatalogRuleCannotValidateCurrentFreePrice() {
        val item = fixture("epic-mafia-hotel.json")
        item.getJSONObject("price").getJSONArray("lineOffers").getJSONObject(0)
            .getJSONArray("appliedRules").getJSONObject(0).put("startDate", "2026-10-28T00:00:00Z")
        val result = epic.parseCatalog(catalog(item), "US", now)
        assertTrue(result.offers.isEmpty())
        assertTrue(result.ended.isEmpty())
    }

    @Test fun expiredAndFutureRulesDoNotShortenCurrentWindow() {
        val item = fixture("epic-mafia-hotel.json")
        val rules = item.getJSONObject("price").getJSONArray("lineOffers").getJSONObject(0).getJSONArray("appliedRules")
        val current = rules.getJSONObject(0)
        rules.put(JSONObject(current.toString()).put("startDate", "2026-10-20T00:00:00Z").put("endDate", "2026-10-21T00:00:00Z"))
        rules.put(JSONObject(current.toString()).put("startDate", "2026-10-28T00:00:00Z").put("endDate", "2026-10-29T00:00:00Z"))
        val result = epic.parseCatalog(catalog(item), "US", now)
        assertEquals(Instant.parse("2026-11-01T17:02:00Z").toEpochMilli(), result.offers.single().end)
        assertNull(result.offers.single().start)
        assertTrue(result.ended.isEmpty())
    }

    @Test fun explicitCurrentCatalogStartIsPreservedButFutureProductIsExcluded() {
        val item = fixture("epic-mafia-hotel.json")
        item.getJSONObject("price").getJSONArray("lineOffers").getJSONObject(0)
            .getJSONArray("appliedRules").getJSONObject(0).put("startDate", "2026-10-26T00:00:00Z")
        assertEquals(Instant.parse("2026-10-26T00:00:00Z").toEpochMilli(),
            epic.parseCatalog(catalog(item), "US", now).offers.single().start)
        item.put("effectiveDate", "2026-10-28T00:00:00Z")
        assertTrue(epic.parseCatalog(catalog(item), "US", now).offers.isEmpty())
    }

    @Test fun weeklyExplicitEndIsForwardedAndActiveBrowseWins() {
        val endedAt = Instant.parse("2026-11-21T12:00:00Z").toEpochMilli()
        val emptyBrowse = EpicProvider(StoreHttp { if ("graphql" in it) catalog().toString() else weekly() })
            .fetch("US", emptyList(), endedAt)
        assertEquals(setOf("epic:test-namespace:123", "epic:test-namespace:456"), emptyBrowse.ended)
        val item = fixture("epic-mafia-hotel.json").put("namespace", "test-namespace").put("id", "123")
        item.getJSONObject("price").getJSONArray("lineOffers").getJSONObject(0)
            .getJSONArray("appliedRules").getJSONObject(0).put("endDate", "2026-11-22T12:00:00Z")
        val confirmed = EpicProvider(StoreHttp { if ("graphql" in it) catalog(item).toString() else weekly() })
            .fetch("US", emptyList(), endedAt)
        assertEquals(1, confirmed.offers.size)
        assertEquals(setOf("epic:test-namespace:456"), confirmed.ended)
    }

    @Test fun missingWeeklyEntryDoesNotConfirmEndAndWeeklyKeepsCatalogImage() {
        val weeklyNow = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
        val root = JSONObject(weekly())
        val elements = root.getJSONObject("data").getJSONObject("Catalog").getJSONObject("searchStore").getJSONArray("elements")
        elements.getJSONObject(0).remove("keyImages")
        val item = fixture("epic-mafia-hotel.json").put("namespace", "test-namespace").put("id", "123")
        item.getJSONObject("price").getJSONArray("lineOffers").getJSONObject(0)
            .getJSONArray("appliedRules").getJSONObject(0).put("endDate", "2026-11-20T12:00:00Z")
        val merged = EpicProvider(StoreHttp { if ("graphql" in it) catalog(item).toString() else root.toString() })
            .fetch("US", emptyList(), weeklyNow)
        assertNotNull(merged.offers.single().imageUrl)
        val empty = EpicProvider(StoreHttp { catalog().toString() }).fetch("US", merged.offers, weeklyNow)
        assertTrue(empty.ended.isEmpty())
    }

    @Test fun mirrorRejectsStaleRulesAndForeignCountry() {
        val item = fixture("egdata-mafia-hotel.json")
        assertTrue(epic.parseEgdata(mirror(item), "US", now + 20 * 86_400_000L).offers.isEmpty())
        item.put("countriesBlacklist", JSONArray().put("RU"))
        assertTrue(epic.parseEgdata(mirror(item), "RU", now).offers.isEmpty())
        assertEquals(1, epic.parseEgdata(mirror(item), "US", now).offers.size)
    }

    @Test fun failedBrowseUsesMirrorAndPreservesWeeklyOffer() {
        val http = object : StoreHttp {
            override fun get(url: String): String = when {
                "graphql" in url -> throw IOException("HTTP 503")
                "api.egdata.app" in url -> mirror(fixture("egdata-mafia-hotel.json")).toString()
                else -> weekly()
            }
        }
        val result = EpicProvider(http).fetch("US", emptyList(), now)
        assertTrue(result.complete)
        assertEquals(3, result.requests)
        assertEquals(1, result.offers.size)
        assertTrue(result.details.contains("egdata: ok"))
    }

    @Test fun failureOnSecondPagePreservesFirstPageOffers() {
        var catalogs = 0
        val http = object : StoreHttp {
            override fun get(url: String): String {
                if ("api.egdata.app" in url) throw StoreHttpException(429, 60_000)
                if ("graphql" !in url) return weekly()
                if (catalogs++ > 0) throw IOException("second page unavailable")
                return catalog(fixture("epic-mafia-hotel.json"), total = 2).toString()
            }
        }
        val result = EpicProvider(http).fetch("US", emptyList(), now)
        assertFalse(result.complete)
        assertTrue(result.successful)
        assertEquals(1, result.offers.size)
        assertEquals(60_000L, result.retryAfterMillis)
        assertEquals(4, result.requests)
    }

    @Test fun allSourceFailuresAreNotEmptySuccess() {
        val result = EpicProvider(StoreHttp { throw IOException("offline") }).fetch("US", emptyList(), now)
        assertFalse(result.successful)
        assertFalse(result.complete)
        assertTrue(result.offers.isEmpty())
    }

    @Test fun catalogAndWeeklyDeduplicateUsingWeeklyStart() {
        val weeklyNow = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
        val item = fixture("epic-mafia-hotel.json").put("id", "123").put("namespace", "test-namespace")
        item.getJSONObject("price").getJSONArray("lineOffers").getJSONObject(0)
            .getJSONArray("appliedRules").getJSONObject(0).put("endDate", "2026-11-20T12:00:00Z")
        val result = EpicProvider(StoreHttp { if ("graphql" in it) catalog(item).toString() else weekly() })
            .fetch("US", emptyList(), weeklyNow)
        assertEquals(1, result.offers.size)
        assertEquals(weeklyNow - 86_400_000, result.offers.single().start)
        assertEquals(2, result.requests)
    }

    @Test fun gogHomepageWinsEvenWhenProductPriceIsNotZero() {
        val root = fixture("gog-a-new-beginning.json")
        val result = GogProvider().parseGiveaway(root.getJSONObject("section"), Instant.parse("2026-10-31T12:00:00Z").toEpochMilli())
        assertEquals("gog:1207659150", result.offers.single().id)
        assertEquals("https://www.gog.com/en/", result.offers.single().url)
        assertEquals(Instant.parse("2026-11-02T14:00:59Z").toEpochMilli(), result.offers.single().end)
        assertEquals(999L, result.offers.single().originalPrice)
        assertEquals("USD", result.offers.single().currency)
        assertEquals("${'$'}9.99", result.offers.single().priceText)
        assertEquals(3.6, result.offers.single().ratingValue!!, 0.0001)
        assertEquals(649, result.offers.single().reviewCount)
        assertEquals(listOf("windows", "mac"), result.offers.single().platforms)
    }

    @Test fun gogCatalogZeroPriceDoesNotInventGiveaway() {
        val http = StoreHttp { url ->
            if ("catalog.gog.com" in url) "{\"products\":[{\"id\":\"123\",\"price\":0}],\"productCount\":1}"
            else "{\"config\":{\"pageId\":\"fixture\"},\"sections\":[]}"
        }
        val result = GogProvider(http).fetch("US", emptyList(), now)
        assertTrue(result.offers.isEmpty())
        assertFalse(result.complete)
        assertTrue(result.successful)
    }

    @Test fun optionalScreenshotsUseStoreHostsAndRetainCoverFallback() {
        val item = fixture("epic-mafia-hotel.json")
        val originalEpic = epic.parseCatalog(catalog(item), "US", now).offers.single().imageUrl
        val epicScreenshot = "https://cdn1.epicgames.com/game/screenshot.jpg"
        val images = item.getJSONArray("keyImages")
        images.put(JSONObject().put("type", "Screenshot").put("url", "https://example.com/untrusted.jpg"))
        assertEquals(originalEpic, epic.parseCatalog(catalog(item), "US", now).offers.single().imageUrl)
        images.put(JSONObject().put("type", "Screenshot").put("url", epicScreenshot))
        assertEquals(epicScreenshot, epic.parseCatalog(catalog(item), "US", now).offers.single().imageUrl)

        val section = fixture("gog-a-new-beginning.json").getJSONObject("section")
        val product = section.getJSONObject("properties").getJSONObject("product")
        val gogScreenshot = "https://images.gog-statics.com/screenshot.jpg"
        product.put("screenshots", JSONArray().put("https://example.com/untrusted.jpg"))
        assertEquals(product.getString("coverHorizontal"), GogProvider().parseGiveaway(section, now).offers.single().imageUrl)
        product.getJSONArray("screenshots").put(JSONObject().put("thumbnail", gogScreenshot))
        assertEquals(gogScreenshot, GogProvider().parseGiveaway(section, now).offers.single().imageUrl)
    }

    @Test fun conditionalGogSectionIsSkippedWithoutLoadingIt() {
        val root = fixture("gog-a-new-beginning.json")
        root.getJSONObject("page").getJSONArray("sections").getJSONObject(0).put("isPersonalized", true)
        var calls = 0
        val result = GogProvider(StoreHttp {
            calls++
            if ("catalog.gog.com" in it) "{\"products\":[],\"productCount\":0}" else root.getJSONObject("page").toString()
        }).fetch("US", emptyList(), now)
        assertEquals(2, calls)
        assertFalse(result.complete)
        assertTrue(result.offers.isEmpty())
    }

    @Test fun gogUpcomingAndExpiredAreNeverActive() {
        val root = fixture("gog-a-new-beginning.json").getJSONObject("section")
        val expired = GogProvider().parseGiveaway(root, now + 20 * 86_400_000L)
        assertEquals(setOf("gog:1207659150"), expired.ended)
        root.getJSONObject("properties").put("startDate", "2026-11-01T00:00:00Z")
        val upcoming = GogProvider().parseGiveaway(root, now)
        assertTrue(upcoming.offers.isEmpty())
        assertEquals("gog:1207659150", upcoming.upcoming.single().id)
        assertEquals(Instant.parse("2026-11-01T00:00:00Z").toEpochMilli(), upcoming.upcoming.single().start)
    }
}
