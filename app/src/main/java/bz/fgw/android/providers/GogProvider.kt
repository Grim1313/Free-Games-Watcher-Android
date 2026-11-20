// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.providers

import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.math.BigDecimal
import java.util.Currency

/** GOG claims are hosted on the homepage. A zero-price catalog row alone proves no giveaway. */
class GogProvider(private val http: StoreHttp = PublicStoreHttp()) : StoreProvider {
    override fun fetch(country: String, known: List<StoreOffer>, now: Long): StoreResult {
        val region = country.uppercase(Locale.ROOT)
        require(region.matches(Regex("[A-Z]{2}")))
        val query = "countryCode=$region&locale=en-US&currencyCode=USD"
        val warnings = mutableListOf<String>()
        val offers = linkedMapOf<String, StoreOffer>()
        val ended = mutableSetOf<String>()
        var requests = 0
        var retryAfter: Long? = null
        var pageValid = false
        var catalogValid = false
        var candidates = 0
        val deadline = System.nanoTime() + 60_000_000_000L
        fun request(url: String): JSONObject {
            check(System.nanoTime() < deadline) { "GOG request time budget reached" }
            requests++
            return try { JSONObject(http.get(url)) } catch (error: StoreHttpException) {
                retryAfter = maxOf(retryAfter ?: 0, error.retryAfterMillis ?: 0).takeIf { it > 0 }
                throw error
            }
        }
        try {
            val page = request("https://sections.gog.com/v1/pages/2f?$query")
            require(page.getJSONObject("config").getString("pageId").isNotBlank()) { "Missing GOG page ID" }
            val sections = page.getJSONArray("sections")
            pageValid = true
            var giveaways = 0
            for (index in 0 until sections.length()) {
                val section = sections.getJSONObject(index)
                if (section.optString("sectionType") != "GIVEAWAY_SECTION") continue
                giveaways++
                if (giveaways > 3) { warnings += "Giveaway section budget reached"; break }
                if (section.optString("visibilityControl") != "ALL" || section.optBoolean("isPersonalized")) {
                    warnings += "Conditional giveaway omitted"
                    continue
                }
                try {
                    val sectionId = section.getString("sectionId")
                    require(sectionId.matches(Regex("[a-zA-Z0-9-]{1,100}"))) { "Invalid section identifier" }
                    val result = parseGiveaway(request("https://sections.gog.com/v1/pages/2f/sections/$sectionId?$query"), now)
                    for (offer in result.offers) offers[offer.id] = offer
                    ended += result.ended
                } catch (error: Exception) { warnings += "section: ${error.message}" }
            }
        } catch (error: Exception) { warnings += "homepage: ${error.message}" }
        try {
            val catalog = request("https://catalog.gog.com/v1/catalog?limit=48&page=1&order=desc:trending&price=between:0,0&discounted=eq:true&$query")
            catalog.getJSONArray("products")
            val count = catalog.get("productCount")
            require(count is Number && count.toDouble() >= 0 && count.toDouble() % 1 == 0.0) { "Invalid catalog count" }
            candidates = count.toInt()
            catalogValid = true
            if (candidates > 0) warnings += "$candidates zero-price catalog entries require giveaway terms"
        } catch (error: Exception) { warnings += "catalog: ${error.message}" }
        val details = "homepage=${if (pageValid) "ok" else "failed"}, catalog=${if (catalogValid) candidates else "failed"}" +
            if (warnings.isEmpty()) "" else "; ${warnings.joinToString("; ")}"
        return StoreResult(offers.values.toList(), warnings.isEmpty(), ended - offers.keys,
            requests, details, retryAfter, pageValid || catalogValid)
    }

    fun parseGiveaway(root: JSONObject, now: Long): StoreResult {
        val properties = root.getJSONObject("properties")
        val product = properties.getJSONObject("product")
        val id = product.get("id").toString()
        val title = product.getString("title").trim()
        val slug = product.getString("slug")
        require(id.matches(Regex("[0-9]{1,20}")) && title.isNotEmpty() && slug.matches(Regex("[a-zA-Z0-9_-]+"))) { "Invalid GOG product" }
        val start = if (properties.isNull("startDate")) null else Instant.parse(properties.getString("startDate")).toEpochMilli()
        val end = Instant.parse(properties.getString("endDate")).toEpochMilli()
        require(start == null || end > start) { "Invalid giveaway dates" }
        val type = product.optString("productType")
        val tags = product.optJSONArray("tags")
        val demo = tags != null && (0 until tags.length()).any { tags.getJSONObject(it).optString("slug") == "demo" }
        if (type !in setOf("game", "pack", "dlc")) return StoreResult(emptyList())
        if (end <= now) return StoreResult(emptyList(), ended = setOf("gog:$id"))
        if (start != null && start > now) return StoreResult(emptyList())
        val price = product.optJSONObject("price")
        val money = price?.optJSONObject("baseMoney")
        val currency = money?.optString("currency")?.takeIf { it.matches(Regex("[A-Z]{3}")) }
        val amount = runCatching {
            BigDecimal(money?.optString("amount")).movePointRight(Currency.getInstance(currency).defaultFractionDigits)
                .longValueExact().takeIf { it > 0 }
        }.getOrNull()
        val genres = product.optJSONArray("genres")
        val platforms = product.optJSONArray("operatingSystems")
        return StoreResult(listOf(StoreOffer("gog:$id", title.take(300), "https://www.gog.com/en/", start, end,
            product.optString("coverHorizontal").takeIf { StoreUrls.isCover("gog", it) },
            originalPrice = amount, currency = currency,
            priceText = price?.optString("base")?.takeIf { amount != null && it.isNotBlank() },
            ratingValue = product.optDouble("reviewsRating", Double.NaN).takeIf { it > 0 && it <= 50 && product.optInt("reviewsCount") > 0 }?.div(10),
            reviewCount = product.optInt("reviewsCount", -1).takeIf { it >= 0 },
            genres = if (genres == null) emptyList() else (0 until genres.length()).mapNotNull {
                genres.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
            }.distinct(),
            platforms = if (platforms == null) emptyList() else (0 until platforms.length()).map {
                platforms.optString(it).let { platform -> if (platform == "osx") "mac" else platform }
            }.filter { it in setOf("windows", "mac", "linux") }.distinct(),
            contentType = when { demo -> "demo"; type == "pack" -> "package"; type == "dlc" -> "dlc"; else -> "game" },
        )))
    }
}
