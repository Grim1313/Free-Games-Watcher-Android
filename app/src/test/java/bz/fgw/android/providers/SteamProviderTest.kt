package bz.fgw.android.providers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant

class SteamProviderTest {
    private val now = Instant.parse("2026-05-01T00:00:00Z").toEpochMilli()
    private val keep = JSONObject().put("is_free_to_keep", true)
    private val paid = JSONObject().put("final_price_in_cents", "1999")
    private val free = JSONObject().put("final_price_in_cents", "0")

    @Test fun searchResultsNeedPurchaseEvidenceAndUseRealDeadlines() {
        val http = Fake().apply {
            specials = page(resource("temporary.html"), 2)
            freePrice = page(resource("free-price.html"), 2)
            items = listOf(item(599140, keep), item(3027490, keep),
                item(3550490, JSONObject().put("is_free_to_keep", true).put("free_to_keep_ends", 1780333250)))
        }
        val result = SteamProvider(http).fetch("US", emptyList(), now)
        assertEquals(4, result.requests)
        assertTrue(result.complete)
        val offers = result.offers.associateBy { it.id }
        assertEquals(3, offers.size)
        assertEquals(1780333245000, offers.getValue("app:599140").end)
        assertNull(offers.getValue("app:3027490").end)
        assertEquals(1780333250000, offers.getValue("app:3550490").end)
        assertFalse(offers.containsKey("app:730"))
    }

    @Test fun trialsDemosAndUnconfirmedFreePricesDoNotNotify() {
        val http = Fake().apply {
            specials = page(resource("temporary.html"), 2)
            freePrice = page(resource("free-price.html"), 2)
            items = listOf(item(599140, free).put("is_free", true),
                item(3027490, keep).put("type", 8), item(3550490, free.put("is_free_weekend", true)))
        }
        assertTrue(SteamProvider(http).fetch("US", emptyList(), now).offers.isEmpty())
    }

    @Test fun freeWeekendSearchLabelIsExcludedEvenWhenPricesLookFree() {
        val html = resource("temporary.html").replace("Graveyard Keeper", "Free Weekend")
            .replace("LivingForest", "LivingForest Playtest")
        val http = Fake().apply { specials = page(html, 2) }
        assertTrue(SteamProvider(http).fetch("US", emptyList(), now).offers.isEmpty())
        assertTrue(http.urls.none { it.contains("GetItems") })
    }

    @Test fun failedSecondPageKeepsFirstPageAndCallsFeatured() {
        val http = Fake().apply {
            specials = page(resource("temporary.html"), 180)
            nextPage = { throw IOException("timeout") }
            items = listOf(item(599140, keep), item(3027490, keep))
        }
        val result = SteamProvider(http).fetch("RU", emptyList(), now)
        assertTrue(result.successful)
        assertFalse(result.complete)
        assertEquals(2, result.offers.size)
        assertTrue(http.urls.any { it.contains("featuredcategories/?cc=ru") })
        assertTrue(result.details.contains("timeout"))
    }

    @Test fun missingSearchResultNeedsExplicitStoreBrowseEvidenceToEnd() {
        val known = listOf(StoreOffer("app:42", "Gone", "https://store.steampowered.com/app/42/"),
            StoreOffer("sub:77", "Package", "https://store.steampowered.com/sub/77/"),
            StoreOffer("app:6", "Missing", "https://store.steampowered.com/app/6/"))
        val http = Fake().apply { items = listOf(item(42, paid), item(77, keep).put("item_type", 1)) }
        val result = SteamProvider(http).fetch("US", known, now)
        assertEquals(setOf("app:42"), result.ended)
        assertEquals(listOf("sub:77"), result.offers.map { it.id })
        assertFalse(result.complete)
    }

    @Test fun rateLimitStopsRequestsToSameHostButPreservesItsDelay() {
        val urls = mutableListOf<String>()
        val http = StoreHttp { url -> urls.add(url); throw StoreHttpException(429, 90_000) }
        val result = SteamProvider(http).fetch("RU", emptyList(), now)
        assertEquals(1, urls.size)
        assertEquals(90_000L, result.retryAfterMillis)
        assertFalse(result.successful)
    }

    @Test fun featuredDiscoversDiscountWhenSearchReturnsNothing() {
        val http = Fake().apply {
            featured = """{"specials":{"items":[{"id":42,"name":"Featured game","final_price":0,"original_price":99}]}}"""
            items = listOf(item(42, keep))
        }
        assertEquals("app:42", SteamProvider(http).fetch("US", emptyList(), now).offers.single().id)
    }

    @Test fun anonymousPurchaseNoticeSuppliesPacificDeadlineAcrossNewYear() {
        val http = Fake().apply {
            specials = page(resource("temporary.html").replace("1780333245", ""), 2)
            items = listOf(item(599140, keep), item(3027490, paid))
            productPage = "<p class='game_purchase_discount_quantity'>Free to keep when you get it before January 2 @ 10:00am.</p>"
        }
        val date = Instant.parse("2026-12-31T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2027-01-02T18:00:00Z").toEpochMilli(),
            SteamProvider(http).fetch("US", emptyList(), date).offers.single().end)
    }

    @Test fun storeLinksAreScopedToTheirStoreAndProduct() {
        assertTrue(StoreUrls.isSafe("gog", "https://www.gog.com/en/"))
        assertTrue(StoreUrls.isSafe("steam", "https://store.steampowered.com/sub/77/"))
        assertFalse(StoreUrls.isSafe("steam", "https://store.steampowered.com/login/"))
        assertFalse(StoreUrls.isSafe("steam", "https://store.steampowered.com.evil.test/app/77/"))
        assertFalse(StoreUrls.isSafe("epic", "https://store.steampowered.com/app/77/"))
        assertFalse(StoreUrls.isCover("gog", "https://images.gog.com.evil.test/test.jpg"))
        assertTrue(StoreUrls.isCover("epic", "https://cdn1.epicgames.com/test.jpg"))
    }

    private fun item(id: Long, purchase: JSONObject) = JSONObject().put("id", id)
        .put("item_type", 0).put("type", 0).put("best_purchase_option", purchase)

    private fun resource(name: String) = requireNotNull(javaClass.getResource("/steam/$name")).readText()
    private fun page(html: String, total: Int) = JSONObject().put("success", 1)
        .put("total_count", total).put("results_html", html).toString()

    private inner class Fake : StoreHttp {
        val urls = mutableListOf<String>()
        var specials = page("", 0)
        var freePrice = page("", 0)
        var featured = """{"specials":{"items":[]}}"""
        var productPage = "<html>Age check</html>"
        var items = emptyList<JSONObject>()
        var nextPage: () -> String = { error("Unexpected next page") }
        override fun get(url: String): String {
            urls += url
            return when {
                url.contains("start=100") -> nextPage()
                url.contains("specials=1") -> specials
                url.contains("category1=998") -> freePrice
                url.contains("featuredcategories") -> featured
                url.contains("GetItems") -> JSONObject().put("response", JSONObject()
                    .put("store_items", JSONArray(items))).toString()
                url.contains("/app/") || url.contains("/sub/") -> productPage
                else -> error("Unexpected request: $url")
            }
        }
    }
}
