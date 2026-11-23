// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.providers

import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

internal const val EPIC_RATINGS_HOST = "website.graphql.epicgames.com"
private const val PRODUCT_RESULT_HASH = "4a58e53900301bcbfa5a2a576ece55ec1151c0e69cdddb66891ee18c466054ed"
private val queryExtension = URLEncoder.encode(
    """{"persistedQuery":{"version":1,"sha256Hash":"$PRODUCT_RESULT_HASH"}}""", "UTF-8",
)

private fun fetchEpicScore(namespace: String, log: ((String) -> Unit)?, request: (String) -> String): Double? {
    val variables = JSONObject().put("sandboxId", namespace).put("locale", "en")
    val url = "https://$EPIC_RATINGS_HOST/graphql?operationName=getProductResult" +
        "&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}&extensions=$queryExtension"
    val root = JSONObject(request(url))
    val errors = root.optJSONArray("errors")
    if (errors != null && errors.length() > 0) {
        log?.invoke("Epic GraphQL errors=${errors.length()} first=${errors.optJSONObject(0)?.optString("message")?.take(180)}")
        return null
    }
    val result = root.optJSONObject("data")?.optJSONObject("RatingsPolls")?.optJSONObject("getProductResult")
    log?.invoke("Epic GraphQL sandbox=$namespace result=${result != null} averageRating=${result?.opt("averageRating")}")
    return epicPlayerScore(result?.opt("averageRating"))
}

private fun ratingFailure(e: Exception): String =
    if (e is org.json.JSONException) "invalid JSON" else "${e.javaClass.simpleName}: ${e.message?.take(180)}"

internal fun epicPlayerScore(value: Any?): Double? =
    (value as? Number)?.toDouble()?.takeIf { it in 1.0..5.0 }

/** Cached by offer ID across current and upcoming campaigns. Poll answer totals are not review counts. */
internal fun enrichEpicRatings(
    offers: List<StoreOffer>, known: List<StoreOffer>, now: Long,
    withinBudget: () -> Boolean, log: ((String) -> Unit)? = null, retryMissing: Boolean = false, request: (String) -> String,
): List<StoreOffer> {
    val cached = known.filter { it.contentType == "game" }.groupBy { it.id }
        .mapValues { (_, records) -> records.maxBy { it.epicRatingCheckedAt } }
    val games = offers.filter { it.contentType == "game" }.distinctBy { it.id }.associate { offer ->
        val old = cached[offer.id]
        offer.id to offer.copy(ratingValue = epicPlayerScore(old?.ratingValue), epicRatingCheckedAt = old?.epicRatingCheckedAt ?: 0)
    }.toMutableMap()
    if (log != null) offers.filter { it.contentType != "game" }.forEach { log("Rating ${it.id} skipped: type=${it.contentType}") }
    val due = games.values.filter {
        val age = now - it.epicRatingCheckedAt
        val ttl = TimeUnit.HOURS.toMillis(if (it.ratingValue == null) 3 else 24)
        val dueNow = it.epicRatingCheckedAt == 0L || age < 0 || age >= ttl || (retryMissing && it.ratingValue == null)
        log?.invoke("Rating ${it.id} ${it.title} cached=${it.ratingValue} checkedAt=${it.epicRatingCheckedAt} decision=${if (dueNow) "lookup" else "cache"} nextEligibleMs=${it.epicRatingCheckedAt + ttl}")
        dueNow
    }.sortedBy { it.epicRatingCheckedAt }

    for ((index, offer) in due.withIndex()) {
        if (index >= 6 || !withinBudget()) {
            log?.invoke("Rating ${offer.id} skipped: ${if (index >= 6) "six-game limit" else "time budget"}")
            continue
        }
        val (_, namespace, id) = offer.id.split(':')
        log?.invoke("Rating lookup ${offer.id} ${offer.title}")
        val epicScore = try { fetchEpicScore(namespace, log, request) } catch (e: Exception) {
            log?.invoke("Epic rating ${offer.id} failed: ${ratingFailure(e)}")
            null
        }
        if (epicScore == null && !withinBudget()) {
            log?.invoke("Rating ${offer.id} fallback skipped: time budget")
            continue
        }
        val rating = epicScore ?: try {
            val polls = JSONObject(request("https://api.egdata.app/offers/$id/polls"))
            log?.invoke("EGData ${offer.id} namespace=${polls.opt("_id")} matches=${polls.opt("_id") == namespace} averageRating=${polls.opt("averageRating")}")
            if (polls.opt("_id") == namespace) epicPlayerScore(polls.opt("averageRating")) else null
        } catch (e: Exception) {
            log?.invoke("EGData rating ${offer.id} failed: ${ratingFailure(e)}")
            null
        }
        log?.invoke("Rating result ${offer.id} value=${rating ?: offer.ratingValue} source=${when { epicScore != null -> "Epic"; rating != null -> "EGData"; offer.ratingValue != null -> "cache"; else -> "none" }}")
        games[offer.id] = offer.copy(ratingValue = rating ?: offer.ratingValue, epicRatingCheckedAt = now)
    }
    return offers.map { offer ->
        val game = games[offer.id]
        if (offer.contentType != "game" || game == null) offer else
            offer.copy(ratingValue = game.ratingValue, epicRatingCheckedAt = game.epicRatingCheckedAt)
    }
}
