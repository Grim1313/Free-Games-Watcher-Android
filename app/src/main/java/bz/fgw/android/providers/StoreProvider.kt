package bz.fgw.android.providers

import java.net.URI

data class StoreOffer(
    val id: String,
    val title: String,
    val url: String,
    val start: Long? = null,
    val end: Long? = null,
    val imageUrl: String? = null,
    val originalPrice: Long? = null,
    val currency: String? = null,
    val priceText: String? = null,
    val ratingPercent: Int? = null,
    val reviewCount: Int? = null,
    val ratingValue: Double? = null,
    val genres: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val contentType: String = "game",
    val epicRatingCheckedAt: Long = 0,
)

/** A partial response may contain verified offers. Only explicit evidence belongs in [ended]. */
data class StoreResult(
    val offers: List<StoreOffer>,
    val complete: Boolean = true,
    val ended: Set<String> = emptySet(),
    val requests: Int = 0,
    val details: String = "",
    val retryAfterMillis: Long? = null,
    val successful: Boolean = true,
    val upcoming: List<StoreOffer> = emptyList(),
)

fun interface StoreProvider {
    fun fetch(country: String, known: List<StoreOffer>, now: Long): StoreResult
}

object StoreUrls {
    fun isCover(store: String, url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1) return false
        val domains = when (store) {
            "steam" -> listOf("steamstatic.com", "store.steampowered.com")
            "epic" -> listOf("epicgames.com", "unrealengine.com")
            "gog" -> listOf("gog-statics.com", "images.gog.com")
            else -> return false
        }
        domains.any { uri.host == it || uri.host?.endsWith(".$it") == true }
    }.getOrDefault(false)

    private val steamPath = Regex("/(?:app|sub)/[1-9][0-9]*/?")
    private val epicPath = Regex("/en-US/p/[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9_-]+)*")
    private val gogPath = Regex("/en/game/[a-zA-Z0-9_-]+/?")

    fun isSafe(store: String, url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port != -1 ||
            uri.rawFragment != null || uri.rawQuery != null) return false
        when (store) {
            "steam" -> uri.host == "store.steampowered.com" && steamPath.matches(uri.rawPath)
            "epic" -> uri.host == "store.epicgames.com" && (uri.rawPath == "/en-US/" || epicPath.matches(uri.rawPath))
            "gog" -> uri.host == "www.gog.com" && (uri.rawPath == "/en/" || gogPath.matches(uri.rawPath))
            else -> false
        }
    }.getOrDefault(false)
}
