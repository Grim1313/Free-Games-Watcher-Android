package bz.fgw.android.providers

import org.junit.Assert.*
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URI

class PublicStoreHttpTest {
    private val endpoint = URI("https://catalog.gog.com/v1/catalog")

    private fun response(code: Int, header: String): HttpURLConnection =
        object : HttpURLConnection(endpoint.toURL()) {
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun getResponseCode() = code
            override fun getHeaderField(name: String?) = header
        }

    @Test fun redirectIsReturnedAsErrorWithoutOpeningLocation() {
        val redirect = response(302, "https://unrelated.example/page")
        var connections = 0
        val http = PublicStoreHttp { connections++; redirect }
        val failure = assertThrows(StoreHttpException::class.java) { http.get(endpoint.toString()) }
        assertEquals(302, failure.status)
        assertFalse(redirect.instanceFollowRedirects)
        assertEquals(1, connections)
    }

    @Test fun retryAfterSecondsReachTheProvider() {
        val http = PublicStoreHttp { response(429, "120") }
        val failure = assertThrows(StoreHttpException::class.java) { http.get(endpoint.toString()) }
        assertEquals(120_000L, failure.retryAfterMillis)
    }

    @Test fun unknownAndNonHttpsHostsAreRejectedBeforeConnecting() {
        val http = PublicStoreHttp { error("Unexpected connection") }
        for (url in listOf("http://catalog.gog.com/", "https://catalog.gog.com.example/", "https://u@catalog.gog.com/")) {
            assertThrows(IllegalArgumentException::class.java) { http.get(url) }
        }
    }
}
