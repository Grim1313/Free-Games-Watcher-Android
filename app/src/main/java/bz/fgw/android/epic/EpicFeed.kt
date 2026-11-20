// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.epic

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.time.Instant
import java.util.Locale

data class EpicOffer(
    val id: String,
    val title: String,
    val url: String,
    val start: Long,
    val end: Long,
    val imageUrl: String? = null,
)

data class FeedResult(
    val offers: List<EpicOffer>,
    val complete: Boolean,
    val warnings: List<String> = emptyList(),
    val catalogCount: Int = 0,
    val requestCount: Int = 1,
    val checkedAt: Long = System.currentTimeMillis(),
    val ended: Set<String> = emptySet(),
)

fun interface EpicTransport {
    fun get(url: String): String
}

class EpicHttpException(val status: Int, val retryAfterMillis: Long?) :
    IOException("Epic HTTP $status")

/** Compatibility adapter for the weekly parser; all sources share the bounded transport. */
class HttpEpicTransport : EpicTransport {
    override fun get(url: String): String = try {
        bz.fgw.android.providers.PublicStoreHttp().get(url)
    } catch (error: bz.fgw.android.providers.StoreHttpException) {
        throw EpicHttpException(error.status, error.retryAfterMillis)
    }
}
object StoreLinks {
    private val slugPattern = Regex("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")

    fun fromSlug(slug: String): String? =
        if (slugPattern.matches(slug)) "https://store.epicgames.com/en-US/p/$slug" else null

    fun isSafe(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "store.epicgames.com" &&
            uri.port == -1 && uri.rawUserInfo == null && uri.rawQuery == null &&
            uri.rawFragment == null && uri.rawPath.startsWith("/en-US/p/") &&
            slugPattern.matches(uri.rawPath.removePrefix("/en-US/p/"))
    }.getOrDefault(false)
}

/** Weekly-feed rules ported from Free-Games-Watcher 9e302be, epicProvider.js. */
class EpicFeed(
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: EpicTransport = HttpEpicTransport(),
) {
    fun fetch(country: String): FeedResult {
        val region = normalizedCountry(country)
        val url = "https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions" +
            "?locale=en-US&country=$region&allowCountries=$region"
        return parse(transport.get(url), region, clock())
    }

    fun parse(json: String, country: String, now: Long = clock()): FeedResult {
        val region = normalizedCountry(country)
        val root = JSONObject(json)
        require(root.optJSONArray("errors")?.length().let { it == null || it == 0 }) {
            "Epic API errors"
        }
        val elements = root.getJSONObject("data").getJSONObject("Catalog")
            .getJSONObject("searchStore").getJSONArray("elements")
        val offers = linkedMapOf<String, EpicOffer>()
        val warnings = mutableListOf<String>()
        val ended = mutableSetOf<String>()
        for (index in 0 until elements.length()) {
            try {
                val item = elements.getJSONObject(index)
                parseItem(item, region, now, ended)?.let { offers[it.id] = it }
            } catch (error: IllegalArgumentException) {
                warnings.add("Offer $index: ${error.message}")
            } catch (error: org.json.JSONException) {
                warnings.add("Offer $index: ${error.message}")
            }
        }
        return FeedResult(offers.values.toList(), warnings.isEmpty(), warnings,
            elements.length(), checkedAt = now, ended = ended - offers.keys)
    }

    private fun parseItem(item: JSONObject, region: String, now: Long, ended: MutableSet<String>): EpicOffer? {
        val categories = item.optJSONArray("categories").objects().map { it.optString("path") }
        val type = item.optString("offerType")
        if (type == "DEMO" || "games/demo" in categories) return null
        if (type !in setOf("BASE_GAME", "EDITION", "BUNDLE") &&
            categories.none { it in setOf("games/edition/base", "games/edition", "bundles/games") }) {
            return null
        }
        val blacklist = item.optJSONArray("countriesBlacklist").strings()
        val whitelist = item.optJSONArray("countriesWhitelist").strings()
        if (region in blacklist || (whitelist.isNotEmpty() && region !in whitelist)) return null
        if (item.optBoolean("isCodeRedemptionOnly") || item.optBoolean("prePurchase")) return null

        val price = item.optJSONObject("price")?.optJSONObject("totalPrice")
        val original = price?.opt("originalPrice")
        if (original is Number && original.toDouble() == 0.0) return null
        val groups = item.optJSONObject("promotions")?.optionalArray("promotionalOffers")
        var active: Pair<Long, Long>? = null
        var expired = false
        for (group in groups.objects()) {
            for (window in group.getJSONArray("promotionalOffers").objects()) {
                val discount = window.optJSONObject("discountSetting") ?: continue
                if (discount.optString("discountType") != "PERCENTAGE" ||
                    discount.opt("discountPercentage") != 0) continue
                val start = date(window.getString("startDate"))
                val end = date(window.getString("endDate"))
                require(end > start) { "Invalid promotion dates" }
                if (start <= now && end > now) active = start to end
                if (end <= now) expired = true
            }
        }
        if (active == null && !expired) return null
        require(original is Number && original.toDouble() > 0 &&
            original.toDouble() % 1.0 == 0.0 && original.toDouble() <= 9_007_199_254_740_991.0) {
            "Unknown original price"
        }
        val namespace = item.getString("namespace")
        val id = item.getString("id")
        require(identity.matches(namespace) && identity.matches(id)) { "Invalid offer identity" }
        if (active == null) {
            ended += "epic:$namespace:$id"
            return null
        }
        val final = price.opt("discountPrice")
        require(final is Number && final.toDouble() == 0.0) { "Unconfirmed free price" }
        val title = item.getString("title").trim()
        require(title.isNotEmpty()) { "Missing title" }
        val slug = item.optJSONArray("offerMappings").objects()
            .firstOrNull { it.optString("pageType") in setOf("productHome", "offer") }
            ?.optString("pageSlug").takeUnless { it.isNullOrEmpty() }
            ?: item.optJSONObject("catalogNs")?.optJSONArray("mappings").objects()
                .firstOrNull { it.optString("pageType") == "productHome" }
                ?.optString("pageSlug").takeUnless { it.isNullOrEmpty() }
            ?: item.optString("productSlug")
        val url = requireNotNull(StoreLinks.fromSlug(slug)) { "Missing safe product link" }
        val images = item.optJSONArray("keyImages").objects()
        val image = (images.firstOrNull { it.optString("type") == "OfferImageWide" }
            ?: images.firstOrNull { it.optString("type") == "Thumbnail" })?.optString("url")?.takeIf { it.isNotBlank() }
        return EpicOffer("epic:$namespace:$id", title.take(300), url, active.first, active.second, image)
    }

    private fun date(value: String): Long = try {
        Instant.parse(value).toEpochMilli()
    } catch (error: java.time.format.DateTimeParseException) {
        throw IllegalArgumentException("Invalid promotion date", error)
    }

    private fun normalizedCountry(country: String): String {
        val region = country.uppercase(Locale.ROOT)
        require(Regex("[A-Z]{2}").matches(region)) { "Country must be a two-letter code" }
        return region
    }

    private fun JSONObject.optionalArray(name: String): JSONArray? =
        if (!has(name) || isNull(name)) null else getJSONArray(name)

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else List(length()) { getJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else List(length()) { getString(it) }

    private companion object {
        val identity = Regex("[a-zA-Z0-9_-]{1,100}")
    }
}
