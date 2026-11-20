package bz.fgw.android.epic

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EpicFeedTest {
    private val now = Instant.parse("2026-11-19T12:00:00Z").toEpochMilli()
    private val feed = EpicFeed(clock = { now })

    @Test fun frozenExtensionExampleSeparatesCurrentAndFutureOffers() {
        val result = feed.parse(fixture("weekly.json"), "US")
        assertTrue(result.complete)
        assertEquals(2, result.catalogCount)
        assertEquals(listOf("epic:test-namespace:123"), result.offers.map { it.id })
        val offer = result.offers.single()
        assertEquals("https://store.epicgames.com/en-US/p/test-game", offer.url)
        assertEquals(now - 86_400_000, offer.start)
        assertEquals(now + 86_400_000, offer.end)
        assertEquals(1999L, offer.originalPrice)
        assertEquals("USD", offer.currency)
        assertEquals("${'$'}19.99", offer.priceText)
        assertEquals("epic:test-namespace:456", result.upcoming.single().id)
        assertEquals(now + 3_600_000, result.upcoming.single().start)
    }

    @Test fun permanentlyFreeNegativeExampleMatchesExtension() {
        val result = feed.parse(fixture("permanently-free.json"), "US")
        assertTrue(result.complete)
        assertTrue(result.offers.isEmpty())
    }

    @Test fun expiredWindowIsExcludedAtExactEnd() {
        assertTrue(feed.parse(fixture("weekly.json"), "US", now + 86_400_000).offers.isEmpty())
    }

    @Test fun windowStartsAtTheSameInstantInAnyOffset() {
        val json = mutated {
            getJSONObject("promotions").getJSONArray("promotionalOffers")
                .getJSONObject(0).getJSONArray("promotionalOffers").getJSONObject(0)
                .put("startDate", "2026-11-19T15:00:00+03:00")
        }
        assertTrue(feed.parse(json, "US", now - 1).offers.isEmpty())
        assertEquals(now, feed.parse(json, "US", now).offers.single().start)
    }

    @Test fun missingDeadlineDoesNotBecomeAnActiveGiveaway() {
        val json = mutated {
            getJSONObject("promotions").getJSONArray("promotionalOffers")
                .getJSONObject(0).getJSONArray("promotionalOffers").getJSONObject(0)
                .remove("endDate")
        }
        val result = feed.parse(json, "US")
        assertTrue(result.offers.isEmpty())
        assertFalse(result.complete)
    }

    @Test fun confirmedDemoIsTypedForUserContentFilters() {
        val json = mutated { put("offerType", "DEMO") }
        assertEquals("demo", feed.parse(json, "US").offers.single().contentType)
    }

    @Test fun metadataUsesOnlyRecognizedGenreAndPlatformTags() {
        val json = mutated { put("tags", org.json.JSONArray()
            .put(JSONObject().put("id", "1367")).put(JSONObject().put("id", "9547"))
            .put(JSONObject().put("id", "unknown")).put(JSONObject().put("id", "1367"))) }
        val offer = feed.parse(json, "US").offers.single()
        assertEquals(listOf("RPG"), offer.genres)
        assertEquals(listOf("windows"), offer.platforms)
    }

    @Test fun unconfirmedPriceProducesPartialResult() {
        val json = mutated { getJSONObject("price").getJSONObject("totalPrice").put("discountPrice", 99) }
        val result = feed.parse(json, "US")
        assertFalse(result.complete)
        assertTrue(result.offers.isEmpty())
    }

    @Test fun weeklyFeedRequiresItsSpecialZeroPercentageValue() {
        val json = mutated {
            getJSONObject("promotions").getJSONArray("promotionalOffers")
                .getJSONObject(0).getJSONArray("promotionalOffers").getJSONObject(0)
                .getJSONObject("discountSetting").put("discountPercentage", 100)
        }
        assertTrue(feed.parse(json, "US").offers.isEmpty())
    }

    @Test fun offerSpecificMappingWinsOverNamespaceMapping() {
        val json = mutated {
            put("offerMappings", org.json.JSONArray().put(JSONObject()
                .put("pageType", "offer").put("pageSlug", "specific-edition")))
        }
        assertEquals("https://store.epicgames.com/en-US/p/specific-edition",
            feed.parse(json, "US").offers.single().url)
    }

    @Test fun invalidEntryPreservesOtherConfirmedOffers() {
        val root = JSONObject(fixture("weekly.json"))
        val elements = root.getJSONObject("data").getJSONObject("Catalog")
            .getJSONObject("searchStore").getJSONArray("elements")
        elements.put("invalid item")
        val result = feed.parse(root.toString(), "US")
        assertFalse(result.complete)
        assertEquals(1, result.offers.size)
    }

    @Test fun unsafeProductMappingIsRejected() {
        val json = mutated {
            getJSONObject("catalogNs").getJSONArray("mappings")
                .getJSONObject(0).put("pageSlug", "../login")
        }
        val result = feed.parse(json, "US")
        assertFalse(result.complete)
        assertTrue(result.offers.isEmpty())
    }

    @Test fun countryRestrictionIsApplied() {
        val json = mutated { put("countriesBlacklist", org.json.JSONArray(listOf("RU"))) }
        assertEquals(1, feed.parse(json, "US").offers.size)
        assertTrue(feed.parse(json, "RU").offers.isEmpty())
    }

    @Test fun duplicateWindowsUseOneStableOffer() {
        val json = mutated {
            val windows = getJSONObject("promotions").getJSONArray("promotionalOffers")
                .getJSONObject(0).getJSONArray("promotionalOffers")
            windows.put(windows.getJSONObject(0))
        }
        assertEquals(1, feed.parse(json, "US").offers.size)
    }

    @Test fun fetchUsesOneAnonymousRegionalEndpointAndSharedParser() {
        val requests = mutableListOf<String>()
        val provider = EpicFeed({ now }, EpicTransport { url -> requests.add(url); fixture("weekly.json") })
        val result = provider.fetch("ru")
        assertEquals(1, requests.size)
        assertEquals("https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions" +
            "?locale=en-US&country=RU&allowCountries=RU", requests.single())
        assertEquals(feed.parse(fixture("weekly.json"), "RU"), result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun countryCannotInjectQueryParameters() {
        feed.fetch("US&country=RU")
    }

    @Test fun safeStoreLinksRejectRedirectAndEncodingTricks() {
        assertTrue(StoreLinks.isSafe("https://store.epicgames.com/en-US/p/test-game"))
        listOf(
            "http://store.epicgames.com/en-US/p/game",
            "https://store.epicgames.com.evil.example/en-US/p/game",
            "https://evil.example@store.epicgames.com/en-US/p/game",
            "https://store.epicgames.com/en-US/p/../login",
            "https://store.epicgames.com/en-US/p/%2e%2e/login",
            "https://store.epicgames.com/en-US/p/game?redirect=evil",
            "intent://game",
        ).forEach { assertFalse(it, StoreLinks.isSafe(it)) }
    }

    @Test(expected = org.json.JSONException::class)
    fun missingCatalogIsFailureRatherThanEmptyFeed() {
        feed.parse("{}", "US")
    }

    private fun mutated(block: JSONObject.() -> Unit): String {
        val root = JSONObject(fixture("weekly.json"))
        root.getJSONObject("data").getJSONObject("Catalog").getJSONObject("searchStore")
            .getJSONArray("elements").getJSONObject(0).apply(block)
        return root.toString()
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/epic/$name")).readText()
}
