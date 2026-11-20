// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.providers

import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import java.util.Locale

/** Search discovers candidates. Anonymous regional purchase options verify what the player can keep. */
class SteamProvider(private val http: StoreHttp = PublicStoreHttp()) : StoreProvider {
    override fun fetch(country: String, known: List<StoreOffer>, now: Long): StoreResult {
        require(country.matches(Regex("[A-Z]{2}")))
        return Scan(http, country, now).run(known)
    }

    private class Scan(
        private val http: StoreHttp,
        private val country: String,
        private val now: Long,
    ) {
        private val candidates = linkedMapOf<String, StoreOffer>()
        private val active = linkedMapOf<String, StoreOffer>()
        private val ended = mutableSetOf<String>()
        private val diagnostics = mutableListOf<String>()
        private var requests = 0
        private var successful = false
        private var productPages = 0
        private var complete = true
        private var retryAfter: Long? = null
        private val blockedHosts = mutableSetOf<String>()
        private val region = country.lowercase(Locale.ROOT)
        private val deadline = System.nanoTime() + 90_000_000_000L

        fun run(known: List<StoreOffer>): StoreResult {
            source("Steam specials", STORE_HOST) { search(true) }
            source("Steam free-price", STORE_HOST) { search(false) }
            if (candidates.isEmpty() || !complete) source("Steam featured", STORE_HOST) { featured() }

            val entries = candidates.toMutableMap()
            for (offer in known) if (offer.id.matches(STEAM_ID)) {
                val found = entries[offer.id]
                entries[offer.id] = found?.copy(
                    originalPrice = found.originalPrice ?: offer.originalPrice,
                    currency = found.currency ?: offer.currency,
                    priceText = found.priceText ?: offer.priceText,
                    ratingPercent = found.ratingPercent ?: offer.ratingPercent,
                    reviewCount = found.reviewCount ?: offer.reviewCount,
                    genres = found.genres.ifEmpty { offer.genres },
                    platforms = found.platforms.ifEmpty { offer.platforms },
                    contentType = offer.contentType,
                ) ?: offer
            }
            // Keep the URL below common proxy limits, and bound traffic even with a very large cache.
            if (entries.size > VERIFY_LIMIT) {
                complete = false
                diagnostics += "StoreBrowse: verification limit"
            }
            entries.values.take(VERIFY_LIMIT).chunked(50).forEach { batch ->
                source("Steam StoreBrowse", API_HOST) { verify(batch) }
            }
            for (offer in active.values.toList().take(20)) enrich(offer)
            return StoreResult(active.values.toList(), complete, ended, requests,
                diagnostics.joinToString(" · "), retryAfter, successful)
        }

        private fun source(name: String, host: String, block: () -> Unit) {
            if (host in blockedHosts) return
            val before = requests
            try {
                block()
                diagnostics += "$name: ${requests - before}"
            } catch (failure: Exception) {
                complete = false
                diagnostics += "$name: ${failure.message?.take(120) ?: "unavailable"}"
                if (failure is StoreHttpException && (failure.status == 429 || failure.retryAfterMillis != null)) {
                    blockedHosts += host
                    retryAfter = maxOf(retryAfter ?: 0L, failure.retryAfterMillis ?: 60_000L)
                }
            }
        }

        private fun request(url: String): String {
            check(System.nanoTime() < deadline) { "Steam request time budget reached" }
            requests++
            return http.get(url)
        }

        private fun json(url: String) = JSONObject(request(url))

        private fun search(specials: Boolean) {
            val filter = if (specials) "sort_by=_ASC&maxprice=free&specials=1" else
                "sort_by=Price_ASC&maxprice=free&category1=998"
            val seenPages = mutableSetOf<String>()
            for (page in 0 until PAGE_LIMIT) {
                val response = json("https://$STORE_HOST/search/results/?query&start=${page * PAGE_SIZE}" +
                    "&count=$PAGE_SIZE&dynamic_data=&$filter&cc=$region&l=english&ndl=1&infinite=1")
                require(!response.has("success") || response.optString("success") in setOf("1", "true")) {
                    "Invalid search response"
                }
                val total = response.getInt("total_count")
                require(total >= 0)
                val rows = Jsoup.parseBodyFragment(response.getString("results_html")).select("a.search_result_row")
                require(rows.isNotEmpty() || total == 0) { "Missing search rows" }
                val signature = rows.joinToString("|") { it.attr("href").substringBefore('?') }
                require(seenPages.add(signature)) { "Repeated search page" }
                for (row in rows) {
                    val title = row.selectFirst(".title")?.text()?.trim().orEmpty()
                    val priceText = row.selectFirst(".discount_final_price, .search_price")?.text().orEmpty()
                    if (title.isEmpty() || EXCLUDED.containsMatchIn("$title $priceText")) continue
                    val final = row.selectFirst("[data-price-final]")?.attr("data-price-final")?.toLongOrNull()
                    val discount = row.selectFirst("[data-discount]")?.attr("data-discount")?.toIntOrNull()
                    val original = row.selectFirst(".discount_original_price")?.text().orEmpty()
                    val zero = final == 0L || (final == null && ZERO_PRICE.matches(priceText))
                    val priceCut = zero && (discount == 100 || original.any { it in '1'..'9' })
                    if (!priceCut && !(specials && priceText.equals("Free", ignoreCase = true))) continue
                    val link = runCatching { URI(row.attr("href")) }.getOrNull() ?: continue
                    if (link.host != STORE_HOST || link.scheme != "https") continue
                    val match = LINK_ID.find(link.rawPath) ?: continue
                    val type = match.groupValues[1]
                    val number = match.groupValues[2]
                    val deadline = row.selectFirst("[data-discount_expiration], [data-discount-end-date]")
                    val time = deadline?.let { it.attr("data-discount_expiration").ifEmpty { it.attr("data-discount-end-date") } }
                    val offer = StoreOffer("$type:$number", title.take(300), "https://$STORE_HOST/$type/$number/",
                        end = timestamp(time?.toLongOrNull()),
                        imageUrl = row.selectFirst(".search_capsule img")?.attr("src")?.takeIf { StoreUrls.isCover("steam", it) },
                        priceText = original.takeIf { it.isNotBlank() },
                        platforms = row.select(".platform_img").flatMap { it.classNames() }
                            .mapNotNull { when (it) { "win" -> "windows"; "mac" -> "mac"; "linux" -> "linux"; else -> null } }.distinct(),
                        contentType = if (type == "sub") "package" else "game")
                    candidates.putIfAbsent(offer.id, offer)
                }
                successful = true
                if ((page + 1) * PAGE_SIZE >= total) return
            }
            // The free-price index includes the entire permanent F2P catalog. This is normal coverage.
            diagnostics += "Steam search: first ${PAGE_LIMIT * PAGE_SIZE} rows"
        }

        private fun featured() {
            val rows = json("https://$STORE_HOST/api/featuredcategories/?cc=$region&l=english")
                .getJSONObject("specials").getJSONArray("items")
            for (i in 0 until rows.length()) {
                val item = rows.getJSONObject(i)
                if (item.optInt("final_price", -1) != 0 || item.optLong("original_price", 0) <= 0) continue
                val number = item.optLong("id")
                val title = item.optString("name").trim()
                if (number <= 0 || title.isEmpty() || EXCLUDED.containsMatchIn(title)) continue
                val id = "app:$number"
                candidates.putIfAbsent(id, StoreOffer(id, title.take(300), "https://$STORE_HOST/app/$number/",
                    end = timestamp(item.optLong("discount_expiration")),
                    imageUrl = item.optString("header_image").takeIf { StoreUrls.isCover("steam", it) }))
            }
            successful = true
        }

        private fun verify(batch: List<StoreOffer>) {
            val ids = JSONArray()
            for (offer in batch) {
                val key = if (offer.id.startsWith("app:")) "appid" else "packageid"
                ids.put(JSONObject().put(key, offer.id.substringAfter(':').toLong()))
            }
            val input = JSONObject().put("ids", ids)
                .put("context", JSONObject().put("country_code", country).put("language", "english"))
                .put("data_request", JSONObject().put("include_purchase_options", true)
                    .put("include_reviews", true).put("include_platforms", true))
            val url = "https://$API_HOST/IStoreBrowseService/GetItems/v1/?input_json=" +
                URLEncoder.encode(input.toString(), "UTF-8")
            val rows = json(url).getJSONObject("response").getJSONArray("store_items")
            val byId = batch.associateBy { it.id }
            val checked = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val type = when (row.optInt("item_type", -1)) { 0 -> "app"; 1 -> "sub"; else -> continue }
                val id = "$type:${row.optLong("id")}"
                val candidate = byId[id] ?: continue
                if (row.optInt("success", 1) != 1 || !row.optBoolean("visible", true)) continue
                checked += id
                if (row.optBoolean("unvailable_for_country_restriction") ||
                    row.optBoolean("unavailable_for_country_restriction") ||
                    (type == "app" && row.optInt("type", 0) !in setOf(0, 1, 2, 4, 6, 11, 13))) continue
                val options = buildList {
                    row.optJSONObject("best_purchase_option")?.let(::add)
                    val other = row.optJSONArray("purchase_options") ?: return@buildList
                    for (j in 0 until other.length()) other.optJSONObject(j)?.let(::add)
                }
                val keep = options.firstOrNull { it.optBoolean("is_free_to_keep") }
                val confirmedDiscount = options.firstOrNull {
                    id in candidates && !row.optBoolean("is_free") &&
                        it.optLong("final_price_in_cents", -1) == 0L && it.optLong("original_price_in_cents") > 0 &&
                        it.optInt("discount_pct") == 100 && !it.optBoolean("is_free_weekend") &&
                        !EXCLUDED.containsMatchIn(it.optString("purchase_option_name"))
                }
                val purchase = keep ?: confirmedDiscount
                if (purchase != null) {
                    val end = timestamp(purchase.optLong("free_to_keep_ends")) ?: candidate.end ?: productDeadline(candidate)
                    val reviews = row.optJSONObject("reviews")?.optJSONObject("summary_filtered")
                    val reviewCount = reviews?.optInt("review_count", -1)?.takeIf { it > 0 }
                    val ratingPercent = reviews?.optInt("percent_positive", -1)?.takeIf { it in 0..100 && reviewCount != null }
                    val platforms = row.optJSONObject("platforms")
                    if (end == null || end > now) active[id] = candidate.copy(end = end,
                        originalPrice = purchase.optLong("original_price_in_cents").takeIf { it > 0 } ?: candidate.originalPrice,
                        priceText = purchase.optString("formatted_original_price").takeIf { it.isNotBlank() } ?: candidate.priceText,
                        ratingPercent = ratingPercent ?: candidate.ratingPercent,
                        reviewCount = if (ratingPercent != null) reviewCount else candidate.reviewCount,
                        platforms = if (platforms == null) candidate.platforms else listOf("windows", "mac", "linux").filter {
                            platforms.optBoolean(if (it == "linux") "steamos_linux" else it)
                        },
                        contentType = if (type == "sub") "package" else when (row.optInt("type", 0)) {
                            6, 13 -> "tool"; 1 -> "demo"; 4 -> "dlc"; 11 -> "soundtrack"; else -> "game"
                        })
                    else ended += id
                } else if (options.isNotEmpty() && options.all { it.optLong("final_price_in_cents", -1) > 0 }) {
                    ended += id
                }
            }
            if (!checked.containsAll(byId.keys)) {
                complete = false
                diagnostics += "StoreBrowse: ${(byId.keys - checked).size} unverified"
            }
            successful = true
        }

        private fun enrich(offer: StoreOffer) {
            if (!offer.id.startsWith("app:") || STORE_HOST in blockedHosts) return
            val id = offer.id.substringAfter(':')
            var enriched = offer
            try {
                val payload = json("https://$STORE_HOST/api/appdetails?appids=$id&cc=$region&l=english&filters=basic,price_overview,genres,platforms").optJSONObject(id)
                val data = payload?.takeIf { it.optBoolean("success") }?.optJSONObject("data")
                if (data != null) {
                    val price = data.optJSONObject("price_overview")
                    val genres = data.optJSONArray("genres")
                    val platforms = data.optJSONObject("platforms")
                    val type = when (data.optString("type")) {
                        "dlc" -> "dlc"; "demo" -> "demo"; "music" -> "soundtrack"
                        "application", "tool", "software" -> "tool"; else -> enriched.contentType
                    }
                    enriched = enriched.copy(
                        originalPrice = price?.optLong("initial")?.takeIf { it > 0 } ?: enriched.originalPrice,
                        currency = price?.optString("currency")?.takeIf { it.matches(Regex("[A-Z]{3}")) } ?: enriched.currency,
                        priceText = price?.optString("initial_formatted")?.takeIf { it.isNotBlank() } ?: enriched.priceText,
                        imageUrl = data.optString("header_image").takeIf { StoreUrls.isCover("steam", it) } ?: enriched.imageUrl,
                        genres = if (genres == null) enriched.genres else (0 until genres.length())
                            .mapNotNull { genres.optJSONObject(it)?.optString("description")?.takeIf(String::isNotBlank) }.distinct(),
                        platforms = if (platforms == null) enriched.platforms else listOf("windows", "mac", "linux").filter { platforms.optBoolean(it) },
                        contentType = if (Regex("\\b(?:soundtrack|ost)\\b", RegexOption.IGNORE_CASE).containsMatchIn(enriched.title)) "soundtrack" else type,
                    )
                }
            } catch (failure: Exception) { metadataFailure(failure) }
            if (STORE_HOST !in blockedHosts && enriched.ratingPercent == null) try {
                val response = json("https://$STORE_HOST/appreviews/$id?json=1&language=all&review_type=all&purchase_type=steam&num_per_page=1&filter=recent&cursor=*")
                val summary = response.takeIf { it.optInt("success") == 1 }?.optJSONObject("query_summary")
                val total = summary?.optInt("total_reviews", -1) ?: -1
                val positive = summary?.optInt("total_positive", -1) ?: -1
                if (total > 0 && positive in 0..total) enriched = enriched.copy(
                    ratingPercent = kotlin.math.round(positive.toDouble() * 100 / total).toInt(), reviewCount = total)
            } catch (failure: Exception) { metadataFailure(failure) }
            active[offer.id] = enriched
        }

        private fun metadataFailure(failure: Exception) {
            diagnostics += "Steam metadata: ${failure.message?.take(80) ?: "unavailable"}"
            if (failure is StoreHttpException && (failure.status == 429 || failure.retryAfterMillis != null)) {
                blockedHosts += STORE_HOST
                retryAfter = maxOf(retryAfter ?: 0L, failure.retryAfterMillis ?: 60_000L)
            }
        }

        private fun productDeadline(offer: StoreOffer): Long? {
            if (++productPages > 3 || STORE_HOST in blockedHosts || !StoreUrls.isSafe("steam", offer.url)) return null
            return try {
                val page = Jsoup.parse(request("${offer.url}?cc=$region&l=english"))
                val note = page.select(".game_purchase_discount_quantity").firstOrNull {
                    it.text().contains("free to keep", ignoreCase = true)
                } ?: return null
                // The English anonymous page's default 10am Steam time is Pacific. Other
                // timezone-less times depend on the browser and cannot set a reliable reminder.
                val date = STEAM_DEADLINE.find(note.text()) ?: return null
                val month = Month.values().firstOrNull {
                    it.name.take(3).equals(date.groupValues[1].take(3), ignoreCase = true)
                } ?: return null
                val pacific = ZoneId.of("America/Los_Angeles")
                val today = Instant.ofEpochMilli(now).atZone(pacific).toLocalDate()
                val explicitYear = date.groupValues[3].toIntOrNull()
                var day = LocalDate.of(explicitYear ?: today.year, month, date.groupValues[2].toInt())
                if (explicitYear == null && today.month == Month.DECEMBER && month == Month.JANUARY) day = day.plusYears(1)
                day.atTime(10, 0).atZone(pacific).toInstant().toEpochMilli()
            } catch (failure: Exception) {
                // An anonymous age gate may redirect. The purchase option still confirms ownership.
                if (failure is StoreHttpException && failure.status == 429) {
                    blockedHosts += STORE_HOST
                    retryAfter = maxOf(retryAfter ?: 0L, failure.retryAfterMillis ?: 60_000L)
                }
                diagnostics += "Steam deadline: ${failure.message?.take(80) ?: "unavailable"}"
                null
            }
        }
    }

    private companion object {
        const val STORE_HOST = "store.steampowered.com"
        const val API_HOST = "api.steampowered.com"
        const val PAGE_SIZE = 100
        const val PAGE_LIMIT = 6
        const val VERIFY_LIMIT = 150
        val STEAM_ID = Regex("(?:app|sub):[1-9][0-9]{0,9}")
        val LINK_ID = Regex("^/(app|sub)/([1-9][0-9]{0,9})(?:/|$)")
        val EXCLUDED = Regex("""\b(?:free\s+(?:weekend|week|trial|to\s+play)|play\s+for\s+free|playtest)\b""", RegexOption.IGNORE_CASE)
        val ZERO_PRICE = Regex("""[^0-9]*0(?:[.,]00)?[^0-9]*""")
        val STEAM_DEADLINE = Regex("""\bbefore\s+([a-z]{3,9})\s+(\d{1,2})(?:,\s*(\d{4}))?\s*@\s*10(?::00)?\s*am\b""", RegexOption.IGNORE_CASE)

        fun timestamp(raw: Long?): Long? = when (val value = raw ?: 0L) {
            in 1L..9_999_999_999L -> value * 1_000L
            in 1_000_000_000_000L..9_999_999_999_999L -> raw
            else -> null
        }
    }
}
