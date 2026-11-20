package bz.fgw.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

suspend fun AppRepository.exportBackup(includeHistory: Boolean): String = withContext(Dispatchers.IO) {
    transaction {
        dao.initialize(WatchSettings())
        BackupCodec.encode(dao.settings(), dao.allMarks(), if (includeHistory) dao.allOffers() else emptyList(), includeHistory)
    }
}

suspend fun AppRepository.importBackup(data: BackupData, includeHistory: Boolean) = withContext(Dispatchers.IO) {
    withBackupSettingsLock {
        transaction {
            dao.initialize(WatchSettings())
            val current = dao.settings()
            val now = System.currentTimeMillis()
            val local = dao.allOffers()
            val imported = mutableListOf<StoredOffer>()
            val campaigns = mutableMapOf<Pair<String, String>, String>()
            if (includeHistory && data.hasHistory) {
                for (offer in data.history) {
                    val existing = (local + imported).firstOrNull { sameCampaign(it, offer) }
                    if (existing != null) {
                        campaigns[offer.gameKey to offer.campaignId] = existing.campaignId
                        continue
                    }
                    val uniqueId = if ((local + imported).none { it.campaignId == offer.campaignId }) offer.campaignId
                        else "backup:${UUID.randomUUID()}"
                    campaigns[offer.gameKey to offer.campaignId] = uniqueId
                    imported += offer.copy(
                        campaignId = uniqueId, imported = true, isRead = true, confirmedRevision = -1,
                        ended = offer.ended || (offer.end != null && offer.end <= now),
                    )
                }
                // Preserve every live local offer and its delivery state while limiting archived records.
                val all = local + imported
                val activeLocal = local.filter { it.activeAt(now) || it.upcoming }.map { it.campaignId }.toSet()
                val keep = all.filter { it.campaignId !in activeLocal }.sortedByDescending { it.lastConfirmed }
                    .take((BackupCodec.MAX_HISTORY - activeLocal.size).coerceAtLeast(0)).map { it.campaignId }.toSet() + activeLocal
                local.filter { it.campaignId !in keep }.map { it.campaignId }.chunked(500).forEach { dao.deleteOffers(it) }
                val saved = imported.filter { it.campaignId in keep }
                dao.saveOffers(saved)
                dao.enqueue(saved.flatMap {
                    listOf(NotificationEvent(it.campaignId, delivered = true, discarded = true, deliveredAt = now),
                        NotificationEvent(it.campaignId, delivered = true, discarded = true, type = "reminder", deliveredAt = now))
                })
                dao.pruneOrphanEvents()
            }
            dao.clearMarks()
            dao.putMarks(data.marks.map { mark ->
                val datedMatch = local.firstOrNull { entry ->
                    mark.hiddenCampaign.isNotEmpty() && entry.gameKey == mark.gameKey && entry.country == data.settings.country &&
                        entry.activeAt(now) && entry.start != null && entry.end != null &&
                        mark.updatedAt >= entry.start && mark.updatedAt < entry.end
                }
                mark.copy(hiddenCampaign = campaigns[mark.gameKey to mark.hiddenCampaign] ?: datedMatch?.campaignId ?: mark.hiddenCampaign,
                    snoozePending = mark.snoozePending && imported.none { it.gameKey == mark.gameKey })
            })
            dao.saveSettings(data.settings.copy(revision = current.revision + 1))
        }
    }
}

private fun sameCampaign(local: StoredOffer, incoming: StoredOffer): Boolean {
    if (local.gameKey != incoming.gameKey || local.country != incoming.country) return false
    if (local.campaignId == incoming.campaignId) return true
    if (local.start == incoming.start && local.end == incoming.end &&
        (local.start != null || local.end != null || local.firstSeen == incoming.firstSeen)) return true
    if (local.start != null && incoming.start != null && local.end != null && incoming.end != null) {
        return local.start < incoming.end && incoming.start < local.end
    }
    val now = System.currentTimeMillis()
    return !local.ended && !incoming.ended && (local.end == null || local.end > now) &&
        (incoming.end == null || incoming.end > now) && (local.start == null || local.start <= now) &&
        (incoming.start == null || incoming.start <= now)
}
