package bz.fgw.android.ui

import bz.fgw.android.data.FeedState
import java.time.Instant

internal fun buildDiagnosticsReport(state: FeedState, version: String, now: Long): String = buildString {
    appendLine("Free Games Watcher for Android $version")
    appendLine("Generated = ${Instant.ofEpochMilli(now)}")
    appendLine("Country = ${state.country}")
    appendLine("Language = ${state.settings.language}")
    appendLine("Tracking enabled = ${state.settings.trackFreeToKeep}")
    appendLine("Selected stores = ${state.selectedStores.sorted().joinToString(", ")}")
    appendLine("Check interval minutes = ${state.settings.intervalMinutes}")
    for (store in state.selectedStores.sorted()) {
        appendLine()
        appendLine(storeName(store))
        val check = state.checks.find { it.store == store }
        if (check == null) {
            appendLine("Outcome = never checked")
            continue
        }
        appendLine("Last attempt = ${Instant.ofEpochMilli(check.lastAttempt)}")
        appendLine("Last success = ${check.lastSuccess?.let(Instant::ofEpochMilli) ?: "none"}")
        appendLine("Outcome = ${check.error ?: if (check.complete) "complete" else "partial"}")
        appendLine("Requests = ${check.requestCount}")
        check.retryAt?.let { appendLine("Retry after = ${Instant.ofEpochMilli(it)}") }
        if (check.details.isNotBlank()) {
            appendLine("Details")
            appendLine(check.details)
        }
    }
}
