// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.providers

import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import java.util.concurrent.TimeUnit

private val playerScore = Regex("""Epic Player Ratings\s+Captured from players in the Epic Games ecosystem\.\s+(\d+(?:\.\d+)?)(?=\s|$)""")

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
        val pageScore = if (offer.url.startsWith("https://store.epicgames.com/en-US/p/")) runCatching {
            val text = Jsoup.parse(request(offer.url)).body().nodeStream(TextNode::class.java)
                .iterator().asSequence().map { it.text().trim() }.filter { it.isNotEmpty() }.joinToString(" ")
            epicPlayerScore(playerScore.find(text)?.groupValues?.get(1)?.toDoubleOrNull())
        }.getOrNull() else null
        if (pageScore == null && !withinBudget()) break
        val rating = pageScore ?: runCatching {
            val (_, namespace, id) = offer.id.split(':')
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
