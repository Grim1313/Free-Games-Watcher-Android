package bz.fgw.android.providers

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

fun interface StoreHttp {
    fun get(url: String): String
}

class StoreHttpException(val status: Int, val retryAfterMillis: Long?) : IOException("HTTP $status")

class PublicStoreHttp(
    private val connect: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection },
) : StoreHttp {
    override fun get(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host in hosts && uri.port == -1 && uri.rawUserInfo == null)
        val connection = connect(uri)
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "FreeGamesWatcherAndroid/1.0")
            val productPage = uri.host == "store.steampowered.com" &&
                (uri.path.startsWith("/app/") || uri.path.startsWith("/sub/"))
            connection.setRequestProperty("Accept", if (productPage) "text/html" else "application/json")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw StoreHttpException(code, retryDelay(connection.getHeaderField("Retry-After")))
            }
            val started = System.nanoTime()
            return connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 4 * 1024 * 1024) throw IOException("Response exceeds 4 MiB")
                    if (System.nanoTime() - started > 30_000_000_000L) throw IOException("Response timed out")
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun retryDelay(header: String?): Long? {
        val value = header?.trim() ?: return null
        value.toLongOrNull()?.let { return it.coerceIn(0, 86_400) * 1000 }
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant().toEpochMilli() - System.currentTimeMillis()).coerceIn(0, 86_400_000)
        }.getOrNull()
    }

    private companion object {
        val hosts = setOf(
            "store.steampowered.com", "api.steampowered.com", "store.epicgames.com",
            "store-site-backend-static.ak.epicgames.com", "api.egdata.app",
            "sections.gog.com", "catalog.gog.com",
        )
    }
}
