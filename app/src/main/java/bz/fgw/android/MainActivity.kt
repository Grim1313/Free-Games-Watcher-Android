package bz.fgw.android

import android.Manifest
import android.app.LocaleManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import bz.fgw.android.data.StoredOffer
import bz.fgw.android.providers.StoreUrls
import bz.fgw.android.ui.FeedScreen
import bz.fgw.android.ui.FeedViewModel
import bz.fgw.android.ui.FgwTheme
import bz.fgw.android.ui.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model: FeedViewModel by viewModels()
    private var systemNotificationsEnabled by mutableStateOf(false)
    private var systemRemindersEnabled by mutableStateOf(false)
    private var alertKey by mutableStateOf<String?>(null)
    private var notificationOffer by mutableStateOf<StoredOffer?>(null)
    private val requestNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemNotificationsEnabled = notificationsAvailable()
        systemRemindersEnabled = remindersAvailable()
        model.setNotificationsEnabled(granted)
        if (!granted) alertKey = "permission"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        alertKey = savedInstanceState?.getString("alertKey")
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            val dark = when (state.settings.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.BLACK)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.BLACK)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            val text = UiText(state.language)
            val alert = when (alertKey) {
                "permission" -> text.permissionDenied
                "missing" -> text.offerMissing
                "region" -> text.regionChanged
                "unsafe" -> text.unsafeUrl
                "browser" -> text.noBrowser
                else -> null
            }
            FgwTheme(state.settings.theme) {
                FeedScreen(
                    model = model,
                    notificationsAvailable = systemNotificationsEnabled,
                    remindersAvailable = systemRemindersEnabled,
                    onNotificationsChanged = ::changeNotifications,
                    onOpenNotificationSettings = ::openNotificationSettings,
                    onOpenOffer = ::openOffer,
                    alert = alert,
                    onDismissAlert = { alertKey = null },
                    notificationOffer = notificationOffer,
                    onDismissNotificationOffer = { notificationOffer = null },
                )
            }
        }
        lifecycleScope.launch {
            if (Build.VERSION.SDK_INT >= 33) {
                val locales = getSystemService(LocaleManager::class.java).applicationLocales
                val systemLanguage = if (locales.isEmpty) null else locales[0].language
                if (systemLanguage == "ru" || systemLanguage == "en") {
                    watchRepository.setLanguage(systemLanguage)
                }
            }
            val savedOffer = savedInstanceState?.getString("notificationOffer")
            if (savedOffer != null) notificationOffer = watchRepository.findOffer(savedOffer)
            if (savedInstanceState == null) handleNotification(intent)
            watchRepository.state.map { it.settings.language }.distinctUntilChanged().collect { language ->
                if (Build.VERSION.SDK_INT >= 33) {
                    val manager = getSystemService(LocaleManager::class.java)
                    val tags = if (language == "auto") "" else language
                    if (manager.applicationLocales.toLanguageTags() != tags) {
                        manager.applicationLocales = LocaleList.forLanguageTags(tags)
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        notificationOffer?.let { outState.putString("notificationOffer", it.campaignId) }
        alertKey?.let { outState.putString("alertKey", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        systemNotificationsEnabled = notificationsAvailable()
        systemRemindersEnabled = remindersAvailable()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        lifecycleScope.launch { handleNotification(intent) }
    }

    private suspend fun handleNotification(intent: Intent) {
        val campaignId = intent.getStringExtra("campaignId") ?: return
        intent.removeExtra("campaignId")
        val repository = watchRepository
        val offer = repository.findOffer(campaignId)
        val state = repository.state.first()
        when {
            offer == null -> alertKey = "missing"
            offer.country != state.country -> alertKey = "region"
            offer.ended || (offer.end != null && offer.end <= System.currentTimeMillis()) -> notificationOffer = offer
            else -> {
                model.act(offer, "read")
                openOffer(offer)
            }
        }
    }

    private fun openOffer(offer: StoredOffer) {
        if (!StoreUrls.isSafe(offer.store, offer.url)) {
            alertKey = "unsafe"
            return
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, offer.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            alertKey = "browser"
        }
    }

    private fun changeNotifications(enabled: Boolean) {
        if (!enabled) {
            model.setNotificationsEnabled(false)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            model.setNotificationsEnabled(true)
            if (!notificationsAvailable()) openNotificationSettings()
        }
    }

    private fun notificationsAvailable(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            manager.getNotificationChannel("new_giveaways")?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun remindersAvailable(): Boolean {
        val manager = getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(this).areNotificationsEnabled() &&
            manager.getNotificationChannel("giveaway_reminders")?.importance != NotificationManager.IMPORTANCE_NONE
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
}
