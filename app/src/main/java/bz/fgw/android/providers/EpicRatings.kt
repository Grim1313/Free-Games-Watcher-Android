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

private fun fetchEpicScore(namespace: String, request: (String) -> String): Double? {
    val variables = JSONObject().put("sandboxId", namespace).put("locale", "en")
    val url = "https://$EPIC_RATINGS_HOST/graphql?operationName=getProductResult" +
        "&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}&extensions=$queryExtension"
    val root = JSONObject(request(url))
    if ((root.optJSONArray("errors")?.length() ?: 0) > 0) return null
    val result = root.optJSONObject("data")?.optJSONObject("RatingsPolls")?.optJSONObject("getProductResult")
    return epicPlayerScore(result?.opt("averageRating"))
}

internal fun epicPlayerScore(value: Any?): Double? =
    (value as? Number)?.toDouble()?.takeIf { it in 1.0..5.0 }

/** Cached by offer ID across current and upcoming campaigns. Poll answer totals are not review counts. */
internal fun enrichEpicRatings(
    offers: List<StoreOffer>, known: List<StoreOffer>, now: Long,
    withinBudget: () -> Boolean, request: (String) -> String,
): List<StoreOffer> {
    val cached = known.filter { it.contentType == "game" }.groupBy { it.id }
        .mapValues { (_, records) -> records.maxBy { it.epicRatingCheckedAt } }
    val games = offers.filter { it.contentType == "game" }.distinctBy { it.id }.associate { offer ->
        val old = cached[offer.id]
        offer.id to offer.copy(ratingValue = epicPlayerScore(old?.ratingValue), epicRatingCheckedAt = old?.epicRatingCheckedAt ?: 0)
    }.toMutableMap()
    val due = games.values.filter {
        val age = now - it.epicRatingCheckedAt
        it.epicRatingCheckedAt == 0L || age < 0 || age >= TimeUnit.HOURS.toMillis(if (it.ratingValue == null) 3 else 24)
    }.sortedBy { it.epicRatingCheckedAt }

    for (offer in due.take(6)) {
        if (!withinBudget()) break
        val (_, namespace, id) = offer.id.split(':')
        val epicScore = runCatching { fetchEpicScore(namespace, request) }.getOrNull()
        if (epicScore == null && !withinBudget()) break
        val rating = epicScore ?: runCatching {
            val polls = JSONObject(request("https://api.egdata.app/offers/$id/polls"))
            if (polls.opt("_id") == namespace) epicPlayerScore(polls.opt("averageRating")) else null
        }.getOrNull()
        games[offer.id] = offer.copy(ratingValue = rating ?: offer.ratingValue, epicRatingCheckedAt = now)
    }
    return offers.map { offer ->
        val game = games[offer.id]
        if (offer.contentType != "game" || game == null) offer else
            offer.copy(ratingValue = game.ratingValue, epicRatingCheckedAt = game.epicRatingCheckedAt)
    }
}
