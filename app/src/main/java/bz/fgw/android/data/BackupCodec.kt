package bz.fgw.android.data

import bz.fgw.android.providers.StoreUrls
import bz.fgw.android.providers.epicPlayerScore
import java.net.URI
import java.time.Instant
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

data class BackupData(
    val settings: WatchSettings,
    val marks: List<PersonalMark>,
    val history: List<StoredOffer>,
    val exportedAt: String,
    val hasHistory: Boolean,
)

/** Compatible with Free Games Watcher browser backups, including versions 1 and 2. */
object BackupCodec {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_HISTORY = 400
    private const val MAX_TIME = 8_640_000_000_000_000L
    private val stableIdPattern = Regex("(?:app|sub|gog):[1-9][0-9]*|epic:[a-zA-Z0-9_-]{1,100}:[a-zA-Z0-9_-]{1,100}")

    fun decode(text: String): BackupData {
        require(text.length <= MAX_BYTES && text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val root = JSONObject(text)
        require(root.optString("format") == "steam-promo-watch" && root.optInt("version") in 1..3)
        val settings = readSettings(root.getJSONObject("settings"))
        val rawMarks = root.getJSONObject("userActions")
        require(rawMarks.length() <= 2000)
        val marks = rawMarks.keys().asSequence().map { id ->
            require(stableIdPattern.matches(id))
            val item = rawMarks.getJSONObject(id)
            PersonalMark(
                gameKey = "${storeOf(id)}:$id", title = item.optString("title", id).take(300),
                url = safeUrl(id, item.optString("url")), claimed = item.optBoolean("claimed"),
                ignored = item.optBoolean("ignored"), hiddenCampaign = item.optString("hiddenCampaign").take(200),
                snoozedUntil = time(item, "snoozedUntil"), updatedAt = time(item, "updatedAt"),
                snoozePending = item.optBoolean("snoozePending"),
            )
        }.toList()
        val hasHistory = root.getInt("version") == 3 && root.has("history")
        val history = if (hasHistory) {
            fun readHistory(array: JSONArray): List<StoredOffer> {
                require(array.length() <= MAX_HISTORY)
                return (0 until array.length()).map { readOffer(array.getJSONObject(it), settings.country) }
            }
            val standardHistory = readHistory(root.getJSONArray("history"))
            (if (root.has("androidHistory")) readHistory(root.getJSONArray("androidHistory")) else standardHistory)
                .sortedByDescending { it.lastConfirmed }.distinctBy { Triple(it.gameKey, it.country, it.campaignId) }
        } else emptyList()
        val date = root.optString("exportedAt").takeIf { runCatching { Instant.parse(it) }.isSuccess }.orEmpty()
        return BackupData(settings, marks, history, date, hasHistory)
    }

    fun encode(settings: WatchSettings, marks: List<PersonalMark>, offers: List<StoredOffer>, includeHistory: Boolean): String {
        val actions = JSONObject()
        for (mark in marks.sortedByDescending { it.updatedAt }.take(2000)) {
            val id = mark.gameKey.substringAfter(':')
            if (!stableIdPattern.matches(id)) continue
            actions.put(id, JSONObject().put("title", mark.title).put("url", safeUrl(id, mark.url))
                .put("claimed", mark.claimed).put("ignored", mark.ignored).put("hiddenCampaign", mark.hiddenCampaign)
                .put("snoozedUntil", mark.snoozedUntil).put("snoozePending", mark.snoozePending).put("updatedAt", mark.updatedAt))
        }
        val root = JSONObject().put("format", "steam-promo-watch").put("version", 3)
            .put("exportedAt", Instant.now().toString()).put("settings", writeSettings(settings)).put("userActions", actions)
        if (includeHistory && settings.historyEnabled) {
            // The browser stores one current/history record per game and promotion type.
            val history = offers.filter { stableIdPattern.matches(it.id) && it.inHistory && !it.upcoming }
                .sortedWith(compareByDescending<StoredOffer> { it.country == settings.country }.thenByDescending { it.lastConfirmed })
                .distinctBy { it.gameKey }.sortedByDescending { it.lastConfirmed }.take(MAX_HISTORY)
            root.put("history", JSONArray(history.map(::writeOffer)))
            val nativeHistory = offers.filter { stableIdPattern.matches(it.id) && it.inHistory && !it.upcoming }
                .sortedByDescending { it.lastConfirmed }.take(MAX_HISTORY)
            if (nativeHistory != history) root.put("androidHistory", JSONArray(nativeHistory.map(::writeOffer)))
        }
        return root.toString(2).also { decode(it) }
    }

    private fun readSettings(source: JSONObject): WatchSettings {
        val stores = source.optJSONArray("enabledStores")?.let(::strings)?.filter { it in SUPPORTED_STORES }
            ?: SUPPORTED_STORES.toList()
        val country = source.optString("region", "US").uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() } ?: "US"
        val interval = source.optInt("checkIntervalMinutes", 180).takeIf { it in setOf(15, 30, 60, 180, 360, 720, 1440) } ?: 180
        return WatchSettings(
            country = country, notificationsEnabled = source.optBoolean("notificationsEnabled", true),
            stores = stores.joinToString(","), trackFreeToKeep = source.optBoolean("trackFreeToKeep", true),
            language = source.optString("language", "auto").takeIf { it in setOf("auto", "ru", "en") } ?: "auto",
            intervalMinutes = interval, intervalHours = (interval / 60).coerceAtLeast(1),
            remindersEnabled = source.optBoolean("remindersEnabled", true), reminderHours = source.optInt("reminderHours", 3).coerceIn(1, 72),
            quietHoursEnabled = source.optBoolean("quietHoursEnabled"), quietStartMinute = minute(source.optString("quietHoursStart"), 23 * 60),
            quietEndMinute = minute(source.optString("quietHoursEnd"), 8 * 60),
            sortBy = source.optString("sortBy", "ending").takeIf { it in setOf("ending", "newest", "rating", "title") } ?: "ending",
            historyEnabled = source.optBoolean("historyEnabled", true), historyRetentionDays = source.optInt("historyRetentionDays", 30).coerceIn(1, 365),
            badgeEnabled = source.optBoolean("badgeEnabled", true),
            renotifyAfterRetention = source.optBoolean("renotifyAfterRetention", true),
            notifiedRetentionDays = source.optInt("notifiedRetentionDays", 14).coerceIn(1, 365),
            filtersJson = FilterPreferences.fromJson(source.optJSONObject("filters")?.toString() ?: "{}").toJson(),
            theme = source.optString("androidTheme", "system").takeIf { it in setOf("system", "light", "dark") } ?: "system",
        )
    }

    private fun writeSettings(settings: WatchSettings): JSONObject = JSONObject()
        .put("region", settings.country).put("enabledStores", JSONArray(settings.selectedStores().toList()))
        .put("language", settings.language).put("notificationsEnabled", settings.notificationsEnabled)
        .put("checkIntervalMinutes", settings.intervalMinutes).put("remindersEnabled", settings.remindersEnabled)
        .put("reminderHours", settings.reminderHours).put("quietHoursEnabled", settings.quietHoursEnabled)
        .put("quietHoursStart", clockText(settings.quietStartMinute)).put("quietHoursEnd", clockText(settings.quietEndMinute))
        .put("sortBy", settings.sortBy)
        .put("historyEnabled", settings.historyEnabled).put("historyRetentionDays", settings.historyRetentionDays)
        .put("trackFreeToKeep", settings.trackFreeToKeep).put("badgeEnabled", settings.badgeEnabled)
        .put("notifiedRetentionDays", settings.notifiedRetentionDays).put("renotifyAfterRetention", settings.renotifyAfterRetention)
        .put("filters", JSONObject(settings.filtersJson))
        .put("androidTheme", settings.theme)

    private fun readOffer(item: JSONObject, fallbackCountry: String): StoredOffer {
        val id = item.getString("stableId")
        require(stableIdPattern.matches(id) && item.getString("promoType") == "free-to-keep")
        val title = item.getString("title")
        require(title.isNotBlank() && title.length <= 300)
        require(item.getString("status") in setOf("active", "expired", "dismissed"))
        for (key in listOf("startsAt", "endsAt", "firstSeenAt", "lastSeenAt", "lastCheckedAt", "lastNotifiedAt", "lastStateChangeAt")) time(item, key)
        for (key in listOf("campaignId", "fingerprint")) {
            if (item.has(key)) require(item.get(key) is String && item.getString(key).length <= 200)
        }
        val store = storeOf(id)
        val country = item.optString("region", fallbackCountry).uppercase(Locale.ROOT).takeIf { it in Locale.getISOCountries() } ?: fallbackCountry
        val image = listOf("screenshotThumbnail", "screenshotFull", "headerImage", "capsuleImage")
            .firstNotNullOfOrNull { key -> item.optString(key).takeIf { StoreUrls.isCover(store, it) } }
        val percent = item.optInt("reviewPercent", -1).takeIf { store == "steam" && it in 0..100 }
        val count = item.optInt(if (store == "gog") "gogRatingCount" else "reviewTotal", 0).takeIf { store != "epic" && it > 0 }
        val epicGame = store == "epic" && item.optString("contentType", "game") == "game"
        val rating = when {
            epicGame -> epicPlayerScore(item.opt("epicRating"))
            store == "gog" && count != null -> item.optDouble("gogRating").takeIf { it > 0 && it <= 5 }
            else -> null
        }
        return StoredOffer(
            campaignId = item.optString("campaignId").ifEmpty { item.optString("fingerprint") }.ifEmpty { "$id|free-to-keep" },
            id = id, title = title, url = safeUrl(id, item.optString("claimUrl").ifEmpty { item.optString("url") }),
            start = time(item, "startsAt").takeIf { it > 0 }, end = time(item, "endsAt").takeIf { it > 0 },
            country = country, lastConfirmed = maxOf(time(item, "lastSeenAt"), time(item, "firstSeenAt")), confirmedRevision = -1,
            store = store, ended = item.optBoolean("confirmedEnded") || item.optString("status") == "expired",
            imageUrl = image, priceText = item.optString("basePriceFormatted").take(64).ifBlank { null },
            originalPrice = item.optLong("androidOriginalPrice", -1).takeIf { it >= 0 },
            currency = item.optString("androidCurrency").takeIf { Regex("[A-Z]{3}").matches(it) },
            ratingPercent = percent.takeIf { count != null }, reviewCount = count, ratingValue = rating,
            epicRatingCheckedAt = if (epicGame) time(item, "epicRatingCheckedAt") else 0,
            contentType = item.optString("contentType", "unknown").takeIf { it in setOf("game", "dlc", "soundtrack", "demo", "tool", "package", "unknown") } ?: "unknown",
            genres = strings(item.optJSONArray("genres") ?: JSONArray()).take(50).map { it.take(100) },
            platforms = strings(item.optJSONArray("platforms") ?: JSONArray()).filter { it in setOf("windows", "mac", "linux") },
            firstSeen = time(item, "firstSeenAt"), isRead = true, imported = true,
        )
    }

    private fun writeOffer(offer: StoredOffer): JSONObject = JSONObject()
        .put("id", "${offer.id}|free-to-keep").put("stableId", offer.id).put("store", offer.store)
        .put("campaignId", offer.campaignId).put("fingerprint", offer.campaignId).put("promoType", "free-to-keep")
        .put("title", offer.title).put("url", safeUrl(offer.id, offer.url)).put("claimUrl", safeUrl(offer.id, offer.url))
        .put("region", offer.country).put("startsAt", offer.start ?: 0).put("endsAt", offer.end ?: 0)
        .put("firstSeenAt", offer.firstSeen).put("lastSeenAt", offer.lastConfirmed).put("lastCheckedAt", offer.lastConfirmed)
        .put("lastStateChangeAt", offer.firstSeen).put("lastNotifiedAt", 0).put("confirmedEnded", offer.ended)
        .put("status", if (offer.ended || (offer.end != null && offer.end <= System.currentTimeMillis())) "expired" else "active")
        .put("unread", !offer.isRead).put("stale", offer.imported).put("headerImage", offer.imageUrl.orEmpty())
        .put("basePriceFormatted", offer.priceText.orEmpty()).put("androidOriginalPrice", offer.originalPrice ?: JSONObject.NULL)
        .put("androidCurrency", offer.currency.orEmpty()).put("contentType", offer.contentType)
        .put("reviewPercent", offer.ratingPercent ?: 0).put("reviewTotal", if (offer.store == "steam") offer.reviewCount ?: 0 else 0)
        .put("gogRating", if (offer.store == "gog") offer.ratingValue ?: 0 else 0).put("gogRatingCount", if (offer.store == "gog") offer.reviewCount ?: 0 else 0)
        .apply {
            if (offer.store == "epic") {
                put("epicRating", offer.ratingValue ?: 0)
                put("epicRatingCheckedAt", offer.epicRatingCheckedAt)
            }
        }
        .put("genres", JSONArray(offer.genres)).put("platforms", JSONArray(offer.platforms))

    private fun time(item: JSONObject, key: String): Long {
        if (!item.has(key)) return 0
        val value = item.get(key)
        require(value is Number && value.toDouble().isFinite() && value.toDouble() in 0.0..MAX_TIME.toDouble())
        return value.toLong()
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).mapNotNull { array.opt(it) as? String }
    private fun storeOf(id: String) = when { id.startsWith("epic:") -> "epic"; id.startsWith("gog:") -> "gog"; else -> "steam" }
    private fun clockText(minute: Int) = String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
    private fun minute(text: String, fallback: Int): Int {
        if (!Regex("[0-2][0-9]:[0-5][0-9]").matches(text)) return fallback
        return (text.take(2).toInt() * 60 + text.takeLast(2).toInt()).takeIf { it < 1440 } ?: fallback
    }

    private fun safeUrl(id: String, value: String): String {
        val store = storeOf(id)
        val fallback = when (store) {
            "steam" -> "https://store.steampowered.com/${id.replace(':', '/')}/"
            "gog" -> "https://www.gog.com/en/"
            else -> "https://store.epicgames.com/en-US/"
        }
        val uri = runCatching { URI(value) }.getOrNull() ?: return fallback
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1) return fallback
        val host = URI(fallback).host
        if (uri.host != host) return fallback
        val path = when (store) {
            "epic" -> uri.rawPath.replace(Regex("^/[^/]+/p/"), "/en-US/p/")
            "gog" -> uri.rawPath.replace(Regex("^/(?:[a-z]{2}/)?game/"), "/en/game/")
            else -> uri.rawPath
        }
        val normalized = "https://$host$path"
        return normalized.takeIf { StoreUrls.isSafe(store, it) } ?: fallback
    }
}
