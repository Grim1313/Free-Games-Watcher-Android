// Copyright (c) 2026 Grim1313. SPDX-License-Identifier: MIT
package bz.fgw.android.epic

import org.json.JSONObject
import bz.fgw.android.providers.StoreUrls

/** Shared tag IDs from the extension's Epic metadata map. Unknown tags remain unknown. */
object EpicMetadata {
    private val genres = mapOf(
        "1216" to "Action", "1336" to "Action-Adventure", "1117" to "Adventure", "1181" to "Card Game",
        "1296" to "Casual", "1146" to "City Builder", "1116" to "Comedy", "1265" to "Dungeon Crawler",
        "1381" to "Exploration", "1287" to "Fantasy", "1344" to "Fighting", "1294" to "First Person",
        "1218" to "Horror", "1263" to "Indie", "1275" to "MOBA", "1129" to "Music", "1395" to "Narration",
        "1307" to "Open World", "1110" to "Party", "1151" to "Platformer", "1298" to "Puzzle",
        "1212" to "Racing", "1198" to "Retro", "1158" to "Rhythm", "1083" to "Rogue-Lite",
        "1367" to "RPG", "1120" to "RTS", "1210" to "Shooter", "1393" to "Simulation", "1121" to "Space",
        "1283" to "Sports", "1084" to "Stealth", "1115" to "Strategy", "1080" to "Survival",
        "1170" to "Tower Defense", "1318" to "Trivia", "1386" to "Turn-Based", "1088" to "Turn-Based Strategy",
    )
    private val platforms = mapOf("9547" to "windows", "10719" to "mac")

    fun genres(item: JSONObject): List<String> = tags(item).mapNotNull(genres::get).distinct()
    fun platforms(item: JSONObject): List<String> = tags(item).mapNotNull(platforms::get).distinct()
    fun image(item: JSONObject): String? {
        val images = item.optJSONArray("keyImages") ?: return null
        val valid = (0 until images.length()).mapNotNull { images.optJSONObject(it) }
            .filter { StoreUrls.isCover("epic", it.optString("url")) }
        return (valid.firstOrNull { it.optString("type").startsWith("Screenshot", ignoreCase = true) }
            ?: valid.firstOrNull { it.optString("type") == "OfferImageWide" }
            ?: valid.firstOrNull { it.optString("type") == "Thumbnail" })?.optString("url")
    }
    fun contentType(item: JSONObject): String = when (item.optString("offerType")) {
        "DLC", "ADD_ON" -> "dlc"
        "DEMO" -> "demo"
        "BUNDLE" -> "package"
        "BASE_GAME", "EDITION" -> "game"
        else -> {
            val categories = item.optJSONArray("categories")
            val paths = if (categories == null) emptyList() else (0 until categories.length()).mapNotNull {
                categories.optJSONObject(it)?.optString("path")
            }
            when {
                "games/demo" in paths -> "demo"
                "addons" in paths -> "dlc"
                "bundles/games" in paths -> "package"
                else -> "game"
            }
        }
    }
    fun priceText(price: JSONObject?): String? = price?.optJSONObject("fmtPrice")
        ?.optString("originalPrice")?.takeIf { it.isNotBlank() && it != "null" }

    private fun tags(item: JSONObject): List<String> {
        val tags = item.optJSONArray("tags") ?: return emptyList()
        return (0 until tags.length()).mapNotNull { tags.optJSONObject(it)?.optString("id") }
    }
}
