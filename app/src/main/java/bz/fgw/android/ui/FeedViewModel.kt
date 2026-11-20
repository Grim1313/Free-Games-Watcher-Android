package bz.fgw.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
    fun setInterval(hours: Int) = save { repository.setInterval(hours) }
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
