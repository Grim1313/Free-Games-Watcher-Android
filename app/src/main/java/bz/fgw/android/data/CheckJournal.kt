package bz.fgw.android.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import bz.fgw.android.providers.PublicStoreHttp
import bz.fgw.android.providers.StoreHttp
import bz.fgw.android.providers.StoreHttpException
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class CheckJournal(context: Context) {
    private val prefs = context.getSharedPreferences("check_diagnostics", Context.MODE_PRIVATE)
    private val recording = MutableStateFlow(prefs.getBoolean("recording", false))
    val enabled = recording.asStateFlow()
    private val current = File(context.noBackupFilesDir, "checks.log")
    private val previous = File(context.noBackupFilesDir, "checks.previous.log")
    private var writeError: String? = null

    @Synchronized fun setEnabled(value: Boolean) {
        prefs.edit { putBoolean("recording", value) }
        recording.value = value
    }

    @Synchronized fun record(message: String) {
        if (!recording.value) return
        try {
            val line = "${Instant.now()} ${message.take(2200).replace('\n', ' ').replace('\r', ' ')}\n".toByteArray()
            if (current.length() + line.size > 512 * 1024) {
                Files.move(current.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            current.appendBytes(line)
        } catch (error: IOException) {
            writeError = error.javaClass.simpleName
        }
    }

    @Synchronized fun snapshot(): String = buildString {
        appendLine("Detailed check log (UTC), Android API ${Build.VERSION.SDK_INT}")
        appendLine("Recording = ${recording.value}")
        writeError?.let { appendLine("Journal write error = $it") }
        for (file in listOf(previous, current)) {
            if (file.exists()) append(file.readText())
        }
        if (!current.exists()) appendLine("No detailed checks recorded.")
    }
}

/** Only public endpoint paths and operation names are included. */
internal fun loggedStoreHttp(log: ((String) -> Unit)?): StoreHttp {
    val http = PublicStoreHttp()
    if (log == null) return http
    return StoreHttp { url ->
        val uri = URI(url)
        val operation = uri.rawQuery.orEmpty().split('&').firstOrNull { it.startsWith("operationName=") }
        val endpoint = uri.host + uri.rawPath + (operation?.let { "?$it" } ?: "")
        val start = System.nanoTime()
        log("GET $endpoint")
        try {
            http.get(url).also {
                log("HTTP 200 $endpoint · ${(System.nanoTime() - start) / 1_000_000} ms · ${it.length} chars")
            }
        } catch (e: Exception) {
            val retry = (e as? StoreHttpException)?.retryAfterMillis
            log("GET failed $endpoint · ${(System.nanoTime() - start) / 1_000_000} ms · ${e.javaClass.simpleName}: ${e.message} retryAfterMs=$retry")
            throw e
        }
    }
}
