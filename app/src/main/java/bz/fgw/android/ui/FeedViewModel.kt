package bz.fgw.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import bz.fgw.android.data.BackupData
import bz.fgw.android.data.FilterPreferences
import bz.fgw.android.data.exportBackup
import bz.fgw.android.data.importBackup
import bz.fgw.android.data.FeedState
import bz.fgw.android.data.StoredOffer
import bz.fgw.android.watchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class FeedViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = application.watchRepository
    val state = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedState())
    private val refreshing = MutableStateFlow(false)
    val isRefreshing = refreshing.asStateFlow()
    private val failed = MutableStateFlow(false)
    val actionFailed = failed.asStateFlow()
    private var activeChecks = 0
    private val storeChanges = Mutex()

    fun refresh() {
        if (refreshing.value) return
        save { scan() }
    }

    fun setCountry(country: String) = save { repository.setCountry(country); scan() }
    fun setStore(store: String, enabled: Boolean) = save {
        storeChanges.withLock {
            val current = repository.state.first().selectedStores
            repository.setStores(if (enabled) current + store else current - store)
        }
        scan()
    }
    fun setLanguage(language: String) = save { repository.setLanguage(language) }
    fun setIntervalMinutes(minutes: Int) = save { repository.setIntervalMinutes(minutes) }
    fun setReminderHours(hours: Int) = save { repository.setReminderHours(hours) }
    fun setRepeatAlerts(enabled: Boolean, days: Int) = save { repository.setRepeatAlerts(enabled, days) }
    fun setBadge(enabled: Boolean) = save { repository.setBadge(enabled) }
    fun resetSettings() = save { repository.resetSettings() }
    suspend fun testNotification(): Boolean = repository.testNotification()
    fun setHistory(enabled: Boolean, days: Int) = save { repository.setHistory(enabled, days) }
    fun setTracking(enabled: Boolean) = save { repository.setTracking(enabled) }
    fun setFilters(filters: FilterPreferences) = save { repository.setFilters(filters) }
    fun setSort(sort: String) = save { repository.setSort(sort) }
    fun setCompact(compact: Boolean) = save { repository.setCompact(compact) }
    fun act(offer: StoredOffer, action: String) = save { repository.act(offer, action) }
    fun restoreMark(key: String) = save { repository.restoreMark(key) }
    fun clearHistory() = save { repository.clearHistory() }
    fun clearMarks() = save { repository.clearMarks() }
    fun markAllRead() = save { repository.markAllRead() }

    suspend fun exportBackup(includeHistory: Boolean): String = repository.exportBackup(includeHistory)
    suspend fun importBackup(data: BackupData, includeHistory: Boolean) = repository.importBackup(data, includeHistory)
    fun setNotificationsEnabled(enabled: Boolean) = save { repository.setNotificationsEnabled(enabled) }
    fun setReminders(enabled: Boolean) = save { repository.setReminders(enabled) }
    fun setQuietHours(enabled: Boolean, start: Int, end: Int) = save { repository.setQuietHours(enabled, start, end) }
    fun setClaimed(offer: StoredOffer, claimed: Boolean) = save { repository.setClaimed(offer, claimed) }
    fun dismissMessage() { failed.value = false }

    private suspend fun scan() {
        activeChecks++
        refreshing.value = true
        try {
            repository.refresh()
        } finally {
            activeChecks--
            refreshing.value = activeChecks > 0
        }
    }

    private fun save(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failed.value = true
            }
        }
    }
}
