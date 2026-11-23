// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.providers

import bz.fgw.android.epic.EpicFeed
import bz.fgw.android.epic.EpicOffer
import bz.fgw.android.epic.EpicMetadata
import bz.fgw.android.epic.StoreLinks
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.util.Locale

/** Regional weekly feed, Browse catalog, then EGData when Browse is incomplete. */
class EpicProvider(
    private val http: StoreHttp = PublicStoreHttp(),
    private val log: ((String) -> Unit)? = null,
    private val retryMissingRatings: Boolean = false,
) : StoreProvider {
    override fun fetch(country: String, known: List<StoreOffer>, now: Long): StoreResult {
        val region = country.uppercase(Locale.ROOT)
        require(region.matches(Regex("[A-Z]{2}")))
        var requests = 0
        var retryAfter: Long? = null
        val diagnostics = mutableListOf<String>()
        val deadline = System.nanoTime() + 90_000_000_000L
        fun request(url: String): String {
            check(System.nanoTime() < deadline) { "Epic request time budget reached" }
            requests++
            return try {
                http.get(url)
            } catch (error: StoreHttpException) {
                retryAfter = maxOf(retryAfter ?: 0, error.retryAfterMillis ?: 0).takeIf { it > 0 }
                throw error
            }
        }
        val weekly = runCatching {
            val raw = request("https://store-site-backend-static.ak.epicgames.com/freeGamesPromotions?locale=en-US&country=$region&allowCountries=$region")
            val result = EpicFeed().parse(raw, region, now)
            StoreResult(result.offers.map(::weeklyOffer),
                result.complete, ended = result.ended, details = result.warnings.joinToString("; "),
                upcoming = result.upcoming.map(::weeklyOffer))
        }.getOrElse { StoreResult(emptyList(), complete = false, successful = false, details = it.message.orEmpty()) }
        diagnostics += "weekly: ${if (weekly.complete) "ok" else weekly.details}"

        fun catalog(mirror: Boolean): StoreResult {
            val offers = linkedMapOf<String, StoreOffer>()
            val ended = mutableSetOf<String>()
            val seen = mutableSetOf<String>()
            val warnings = mutableListOf<String>()
            var read = 0
            var success = false
            var finished = false
            for (page in 1..5) {
                try {
                    // EGData canonical GET observed 2026-11-19; POST now responds with 303.
                    val raw = if (mirror) request(
                        "https://api.egdata.app/search/v2/search?country=$region&locale=en-US&onSale=true&sortBy=price&sortDir=asc&page=$page&limit=40",
                    ) else request(catalogUrl(region, read, now))
                    val root = JSONObject(raw)
                    val parsed = if (mirror) parseEgdata(root, region, now) else parseCatalog(root, region, now)
                    val elements = if (mirror) root.getJSONArray("offers") else elements(root)
                    if (mirror) require(root.getInt("page") == page && root.getInt("limit") == 40) { "Invalid EGData page" }
                    success = true
                    for (offer in parsed.offers) offers[offer.id] = offer
                    ended += parsed.ended
                    if (!parsed.complete) warnings += parsed.details
                    for (item in elements.objects()) {
                        require(seen.add("${item.getString("namespace")}:${item.getString("id")}")) { "Repeated catalog page" }
                    }
                    read += elements.length()
                    val total = if (mirror) root.getInt("total") else root.getJSONObject("data").getJSONObject("Catalog")
                        .getJSONObject("searchStore").getJSONObject("paging").getInt("total")
                    val pastFree = mirror && elements.objects().any {
                        (it.optJSONObject("price")?.optJSONObject("price")?.opt("discountPrice") as? Number)?.toDouble()?.let { price -> price > 0 } == true
                    }
                    if (read >= total || pastFree) { finished = true; break }
                    require(elements.length() > 0) { "Empty catalog page before reported total" }
                } catch (error: Exception) {
                    warnings += error.message.orEmpty()
                    break
                }
            }
            if (!finished && warnings.isEmpty()) warnings += "Catalog page budget reached"
            return StoreResult(offers.values.toList(), finished && warnings.isEmpty(), ended,
                details = warnings.joinToString("; "), successful = success)
        }
        val direct = catalog(false)
        diagnostics += "browse: ${if (direct.complete) "ok" else direct.details}"
        val mirror = if (!direct.complete) catalog(true) else null
        if (mirror != null) diagnostics += "egdata: ${if (mirror.complete) "ok" else mirror.details}"
        val combined = linkedMapOf<String, StoreOffer>()
        for (source in listOfNotNull(mirror, direct, weekly)) {
            for (offer in source.offers) {
                val previous = combined[offer.id]
                combined[offer.id] = offer.copy(start = offer.start ?: previous?.start,
                    imageUrl = offer.imageUrl ?: previous?.imageUrl,
                    originalPrice = offer.originalPrice ?: previous?.originalPrice,
                    currency = offer.currency ?: previous?.currency,
                    priceText = offer.priceText ?: previous?.priceText,
                    genres = offer.genres.ifEmpty { previous?.genres.orEmpty() },
                    platforms = offer.platforms.ifEmpty { previous?.platforms.orEmpty() })
            }
        }
        val ended = (weekly.ended + direct.ended + mirror?.ended.orEmpty()) - combined.keys
        val upcoming = weekly.upcoming.filter { it.id !in combined }
        val enriched = enrichEpicRatings(combined.values + upcoming, known, now, { System.nanoTime() < deadline }, log, retryMissingRatings) { url ->
            requests++
            http.get(url)
        }
        return StoreResult(enriched.take(combined.size), weekly.complete && (direct.complete || mirror?.complete == true),
            ended, requests, diagnostics.joinToString(" · "), retryAfter,
            weekly.successful || direct.successful || mirror?.successful == true,
            upcoming = enriched.drop(combined.size))
    }

    fun parseCatalog(root: JSONObject, country: String, now: Long): StoreResult {
        val elements = elements(root)
        val total = root.getJSONObject("data").getJSONObject("Catalog").getJSONObject("searchStore")
            .getJSONObject("paging").get("total")
        require(total is Number && total.toDouble() >= elements.length() && total.toDouble() % 1 == 0.0) { "Invalid Epic paging" }
        val offers = linkedMapOf<String, StoreOffer>()
        val ended = mutableSetOf<String>()
        val warnings = mutableListOf<String>()
        for (index in 0 until elements.length()) {
            try {
                val item = elements.getJSONObject(index)
                if (!eligible(item, country)) continue
                if (listOf("effectiveDate", "viewableDate").any { !item.isNull(it) && date(item.getString(it)) > now }) continue
                val price = item.getJSONObject("price").getJSONObject("totalPrice")
                val original = price.get("originalPrice")
                val final = price.get("discountPrice")
                require(isMoney(original) && isMoney(final)) { "Invalid catalog price" }
                if ((original as Number).toDouble() <= 0 || (final as Number).toDouble() != 0.0) continue
                if ((price.opt("discount") as? Number)?.toDouble() != original.toDouble() ||
                    (price.opt("voucherDiscount") as? Number)?.toDouble()?.let { it > 0 } == true) continue
                val id = identity(item)
                val windows = item.getJSONObject("price").getJSONArray("lineOffers").objects()
                    .flatMap { it.getJSONArray("appliedRules").objects() }
                    .filter { it.optJSONObject("discountSetting")?.optString("discountType") == "PERCENTAGE" }
                    .map {
                        val start = if (it.isNull("startDate")) null else date(it.getString("startDate"))
                        val end = date(it.getString("endDate"))
                        require(start == null || end > start) { "Invalid discount dates" }
                        start to end
                    }
                require(windows.isNotEmpty()) { "Missing discount deadline" }
                val activeWindows = windows.filter { (it.first == null || it.first!! <= now) && it.second > now }
                if (activeWindows.isEmpty()) {
                    if (windows.any { it.second <= now }) ended += id
                    continue
                }
                // Applied rules without a start are current price evidence. Explicit future rules
                // cannot validate a free offer, and expired rules cannot shorten a live window.
                val end = activeWindows.minOf { it.second }
                val start = activeWindows.mapNotNull { it.first }.maxOrNull()
                val title = item.getString("title").trim()
                require(title.isNotEmpty()) { "Missing title" }
                val image = EpicMetadata.image(item)
                offers[id] = StoreOffer(id, title.take(300), productUrl(item), start, end, image,
                    originalPrice = original.toLong(),
                    currency = price.optString("currencyCode").takeIf { it.matches(Regex("[A-Z]{3}")) },
                    priceText = EpicMetadata.priceText(price), genres = EpicMetadata.genres(item),
                    platforms = EpicMetadata.platforms(item), contentType = EpicMetadata.contentType(item))
            } catch (error: Exception) { warnings += "record $index: ${error.message}" }
        }
        return StoreResult(offers.values.toList(), warnings.isEmpty(), ended - offers.keys, details = warnings.joinToString("; "))
    }

    fun parseEgdata(root: JSONObject, country: String, now: Long): StoreResult {
        val rawOffers = root.getJSONArray("offers")
        val total = root.get("total")
        require(total is Number && total.toDouble() >= rawOffers.length() && total.toDouble() % 1 == 0.0 &&
            root.optJSONObject("meta")?.optBoolean("timed_out") != true) { "Invalid EGData paging" }
        val elements = JSONArray()
        val starts = mutableMapOf<String, Long>()
        val warnings = mutableListOf<String>()
        for (index in 0 until rawOffers.length()) {
            try {
                val item = rawOffers.getJSONObject(index)
                val categories = item.getJSONArray("categories").strings()
                if (categories.none { it in gameCategories }) continue
                if (item.optJSONArray("tags").objects().none { it.optString("id") in setOf("9547", "10719") }) continue
                if (date(item.getString("effectiveDate")) > now ||
                    (!item.isNull("viewableDate") && date(item.getString("viewableDate")) > now)) continue
                if (!allowedCountry(item, country)) continue
                val record = item.getJSONObject("price")
                val price = record.getJSONObject("price")
                if ((price.opt("discountPrice") as? Number)?.toDouble() != 0.0 ||
                    (price.opt("originalPrice") as? Number)?.toDouble()?.let { it > 0 } != true) continue
                require(record.getString("offerId") == item.getString("id") && record.getString("namespace") == item.getString("namespace") &&
                    record.getString("region").isNotEmpty()) { "Mismatched price record" }
                val rules = record.getJSONArray("appliedRules").objects().filter { rule ->
                    rule.optString("promotionStatus") == "ACTIVE" &&
                        rule.optJSONObject("discountSetting")?.optString("discountType") == "PERCENTAGE" &&
                        rule.optJSONObject("discountSetting")?.opt("discountPercentage") == 0 &&
                        rule.optJSONObject("promotionSetting")?.optString("promotionType") == "SIMPLE" &&
                        rule.optString("namespace") == item.getString("namespace") &&
                        record.getString("region") in rule.optJSONArray("regionIds").strings() &&
                        rule.optJSONObject("promotionSetting")?.optJSONArray("discountOffers").objects().any { it.optString("offerId") == item.getString("id") } &&
                        date(rule.getString("startDate")) <= now && date(rule.getString("endDate")) > now
                }
                if (rules.isEmpty()) continue
                require(price.getString("currencyCode").matches(Regex("[A-Z]{3}"))) { "Invalid currency" }
                val converted = JSONObject(item.toString())
                    .put("categories", JSONArray(categories.map { JSONObject().put("path", it) }))
                    .put("price", JSONObject().put("totalPrice", price)
                        .put("lineOffers", JSONArray().put(JSONObject().put("appliedRules", JSONArray(rules)))))
                elements.put(converted)
                starts[identity(item)] = rules.maxOf { date(it.getString("startDate")) }
            } catch (error: Exception) { warnings += "record $index: ${error.message}" }
        }
        val parsed = parseCatalog(JSONObject().put("data", JSONObject().put("Catalog", JSONObject()
            .put("searchStore", JSONObject().put("elements", elements).put("paging", JSONObject().put("total", total))))), country, now)
        return parsed.copy(offers = parsed.offers.map { it.copy(start = starts[it.id]) },
            complete = parsed.complete && warnings.isEmpty(), details = (listOf(parsed.details) + warnings).filter { it.isNotEmpty() }.joinToString("; "))
    }

    fun catalogUrl(country: String, start: Int, now: Long): String {
        val variables = JSONObject().put("allowCountries", country).put("country", country).put("locale", "en-US")
            .put("category", "games/edition/base|bundles/games|games/edition|addons|games/demo")
            .put("count", 40).put("start", start).put("onSale", true).put("priceRange", "[0,0]")
            .put("effectiveDate", "[,${Instant.ofEpochMilli(now)}]").put("sortBy", "currentPrice").put("sortDir", "ASC").put("withPrice", true)
        val extension = JSONObject().put("persistedQuery", JSONObject().put("version", 1)
            .put("sha256Hash", "7d58e12d9dd8cb14c84a3ff18d360bf9f0caa96bf218f2c5fda68ba88d68a437"))
        return "https://store.epicgames.com/graphql?operationName=searchStoreQuery&variables=${encode(variables.toString())}&extensions=${encode(extension.toString())}"
    }

    private fun eligible(item: JSONObject, country: String): Boolean {
        val type = item.optString("offerType")
        val categories = item.optJSONArray("categories").objects().map { it.optString("path") }
        return (type in setOf("BASE_GAME", "EDITION", "BUNDLE", "DLC", "ADD_ON", "DEMO") || categories.any { it in gameCategories }) &&
            !item.optBoolean("isCodeRedemptionOnly") && !item.optBoolean("prePurchase") && allowedCountry(item, country)
    }

    private fun allowedCountry(item: JSONObject, country: String): Boolean {
        val white = item.optJSONArray("countriesWhitelist").strings()
        return country !in item.optJSONArray("countriesBlacklist").strings() && (white.isEmpty() || country in white)
    }

    private fun identity(item: JSONObject): String {
        val namespace = item.getString("namespace")
        val id = item.getString("id")
        require(namespace.matches(Regex("[a-zA-Z0-9_-]{1,100}")) && id.matches(Regex("[a-zA-Z0-9_-]{1,100}"))) { "Invalid Epic identity" }
        return "epic:$namespace:$id"
    }

    private fun productUrl(item: JSONObject): String {
        val slug = item.optJSONArray("offerMappings").objects().firstOrNull { it.optString("pageType") in setOf("offer", "productHome") }
            ?.optString("pageSlug").takeUnless { it.isNullOrEmpty() }
            ?: item.optJSONObject("catalogNs")?.optJSONArray("mappings").objects().firstOrNull { it.optString("pageType") == "productHome" }
                ?.optString("pageSlug").takeUnless { it.isNullOrEmpty() }
            ?: item.optString("productSlug")
        return requireNotNull(StoreLinks.fromSlug(slug)) { "Missing safe product link" }
    }

    private fun elements(root: JSONObject): JSONArray {
        require(root.optJSONArray("errors")?.length().let { it == null || it == 0 }) { "Epic API errors" }
        return root.getJSONObject("data").getJSONObject("Catalog").getJSONObject("searchStore").getJSONArray("elements")
    }

    private fun isMoney(value: Any): Boolean = value is Number && value.toDouble() >= 0 &&
        value.toDouble() <= 9_007_199_254_740_991.0 && value.toDouble() % 1 == 0.0
    private fun date(value: String): Long = Instant.parse(value).toEpochMilli()
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else List(length()) { getJSONObject(it) }
    private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else List(length()) { getString(it) }
    private fun weeklyOffer(offer: EpicOffer) = StoreOffer(offer.id, offer.title, offer.url, offer.start, offer.end, offer.imageUrl,
        originalPrice = offer.originalPrice, currency = offer.currency, priceText = offer.priceText,
        genres = offer.genres, platforms = offer.platforms, contentType = offer.contentType)
    private companion object {
        val gameCategories = setOf("games/edition/base", "games/edition", "bundles/games", "addons", "games/demo")
    }
}
