package bz.fgw.android.data

internal fun offerHistory(
    offers: List<StoredOffer>,
    country: String,
    hiddenCampaigns: Map<String, String> = emptyMap(),
): List<StoredOffer> =
    offers.asSequence().filter { !it.upcoming && it.inHistory }.groupBy { it.gameKey }.values.flatMap { game ->
        val campaigns = mutableListOf<MutableList<StoredOffer>>()
        for (offer in game.sortedWith(compareBy<StoredOffer> { it.start ?: it.firstSeen }.thenBy { it.campaignId })) {
            // A second campaign in the same country is a repeat giveaway. Requiring
            // overlap with every member prevents an undated regional cache from joining repeats.
            val campaign = campaigns.firstOrNull { entries ->
                entries.all { it.country != offer.country && sameHistoryPeriod(it, offer) }
            }
            if (campaign == null) campaigns += mutableListOf(offer) else campaign += offer
        }
        campaigns.map { entries ->
            val representative = entries.maxWith(
                compareBy<StoredOffer> { hiddenCampaigns[it.gameKey] == it.campaignId }
                    .thenBy { it.country == country }
                    .thenBy { it.lastConfirmed }.thenBy { it.campaignId },
            )
            representative.copy(firstSeen = entries.minOf { it.firstSeen }, isRead = entries.any { it.isRead })
        }
    }

private fun sameHistoryPeriod(first: StoredOffer, second: StoredOffer): Boolean {
    val firstStart = first.start ?: first.firstSeen
    val secondStart = second.start ?: second.firstSeen
    val firstEnd = first.end ?: if (first.ended) first.lastConfirmed else Long.MAX_VALUE
    val secondEnd = second.end ?: if (second.ended) second.lastConfirmed else Long.MAX_VALUE
    return firstStart < secondEnd && secondStart < firstEnd ||
        // An undated offer can have only one observed instant before it ends.
        firstStart == secondStart && first.end == null && second.end == null
}
