package bz.fgw.android.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class FilterPreferences(
    val ignoreDlc: Boolean = true,
    val ignoreSoundtracks: Boolean = true,
    val ignoreDemos: Boolean = true,
    val ignoreTools: Boolean = true,
    val ignorePackages: Boolean = false,
    val blockedAppIds: Set<String> = emptySet(),
    val blockedKeywords: Set<String> = emptySet(),
    val minRating: Int = 0,
    val minReviews: Int = 0,
    val includeUnrated: Boolean = true,
    val genres: Set<String> = emptySet(),
    val platforms: Set<String> = emptySet(),
) {
    fun accepts(offer: StoredOffer): Boolean {
        val excludedType = when (offer.contentType) {
            "dlc" -> ignoreDlc
            "soundtrack" -> ignoreSoundtracks
            "demo" -> ignoreDemos
            "tool" -> ignoreTools
            "package" -> ignorePackages
            else -> false
        }
        if (excludedType) return false
        if (offer.store == "steam" && offer.id.substringAfterLast(':') in blockedAppIds) return false
        if (blockedKeywords.any { it.isNotBlank() && offer.title.contains(it, ignoreCase = true) }) return false
        if (offer.store == "steam") {
            val reviewCount = offer.reviewCount ?: 0
            if (reviewCount <= 0) {
                if (!includeUnrated) return false
            } else if (reviewCount < minReviews || (offer.ratingPercent ?: 0) < minRating) return false
        }
        if (genres.isNotEmpty() && offer.genres.none { genre -> genres.any { genreKey(it) == genreKey(genre) } }) return false
        if (platforms.isNotEmpty() && offer.platforms.none { it.lowercase(Locale.ROOT) in platforms }) return false
        return true
    }

    fun toJson(): String = JSONObject().apply {
        put("ignoreDlc", ignoreDlc)
        put("ignoreSoundtracks", ignoreSoundtracks)
        put("ignoreDemos", ignoreDemos)
        put("ignoreTools", ignoreTools)
        put("ignorePackages", ignorePackages)
        put("blockedAppIds", JSONArray(blockedAppIds.toList()))
        put("blockedKeywords", JSONArray(blockedKeywords.toList()))
        put("minRating", minRating.coerceIn(0, 100))
        put("minReviews", minReviews.coerceIn(0, 10_000_000))
        put("includeUnrated", includeUnrated)
        put("genres", JSONArray(genres.toList()))
        put("platforms", JSONArray(platforms.toList()))
    }.toString()

    companion object {
        fun fromJson(value: String): FilterPreferences {
            val json = runCatching { JSONObject(value) }.getOrDefault(JSONObject())
            fun values(key: String): Set<String> {
                val array = json.optJSONArray(key) ?: return emptySet()
                return (0 until array.length()).map { array.optString(it).trim().lowercase(Locale.ROOT) }
                    .filter { it.isNotEmpty() }.take(500).toSet()
            }
            return FilterPreferences(
                ignoreDlc = json.optBoolean("ignoreDlc", true),
                ignoreSoundtracks = json.optBoolean("ignoreSoundtracks", true),
                ignoreDemos = json.optBoolean("ignoreDemos", true),
                ignoreTools = json.optBoolean("ignoreTools", true),
                ignorePackages = json.optBoolean("ignorePackages", false),
                blockedAppIds = values("blockedAppIds").mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 }?.toString() }.toSet(),
                blockedKeywords = values("blockedKeywords"),
                minRating = json.optInt("minRating", 0).coerceIn(0, 100),
                minReviews = json.optInt("minReviews", 0).coerceIn(0, 10_000_000),
                includeUnrated = json.optBoolean("includeUnrated", true),
                genres = values("genres").take(50).toSet(),
                platforms = values("platforms").intersect(setOf("windows", "mac", "linux")),
            )
        }

        private fun genreKey(value: String): String = value.trim().lowercase(Locale.ROOT).let {
            if (it == "role-playing") "rpg" else it
        }
    }
}

fun sortOffers(offers: List<StoredOffer>, sort: String): List<StoredOffer> = when (sort) {
    "title" -> offers.sortedBy { it.title.lowercase(Locale.ROOT) }
    "newest" -> offers.sortedByDescending { it.firstSeen }
    "rating" -> offers.groupBy { it.store }.values.flatMap { group ->
        if (group.first().store == "steam") group.sortedWith(
            compareByDescending<StoredOffer> { it.ratingPercent ?: -1 }.thenByDescending { it.reviewCount ?: 0 }
                .thenBy { it.title.lowercase(Locale.ROOT) },
        ) else sortOffers(group, "ending")
    }
    else -> offers.sortedWith(compareBy<StoredOffer> { it.end ?: Long.MAX_VALUE }.thenBy { it.title.lowercase(Locale.ROOT) })
}
