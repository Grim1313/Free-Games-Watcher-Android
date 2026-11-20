package bz.fgw.android.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import bz.fgw.android.MainActivity
import bz.fgw.android.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal class GiveawayNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels(language: String) {
        val ru = language == "ru"
        manager.createNotificationChannel(NotificationChannel(
            NEW_GIVEAWAYS_CHANNEL, if (ru) "Новые раздачи" else "New giveaways", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = if (ru) "Новые бесплатные игры" else "New free-to-keep games" })
        manager.createNotificationChannel(NotificationChannel(
            REMINDERS_CHANNEL, if (ru) "Напоминания" else "Reminders", NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = if (ru) "Раздачи, которые скоро закончатся" else "Giveaways ending soon" })
    }

    private fun canNotify(channel: String): Boolean {
        val permission = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return permission && NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(channel)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun show(offer: StoredOffer, type: String, language: String, now: Long): Boolean {
        createChannels(language)
        val channel = if (type == "reminder") REMINDERS_CHANNEL else NEW_GIVEAWAYS_CHANNEL
        if (!canNotify(channel)) return false
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData("fgw://campaign/${Uri.encode(offer.campaignId)}".toUri())
            .putExtra("campaignId", offer.campaignId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ru = language == "ru"
        val store = when (offer.store) { "epic" -> "Epic Games Store"; "steam" -> "Steam"; else -> "GOG" }
        val deadline = offer.end?.let {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(Locale.forLanguageTag(language)).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(it))
        }
        val text = if (deadline == null) {
            if (ru) "$store · срок окончания неизвестен" else "$store · end date unknown"
        } else {
            if (ru) "$store · бесплатно до $deadline" else "$store · free until $deadline"
        }
        val title = if (type == "reminder") {
            if (ru) "Скоро закончится · ${offer.title}" else "Ending soon · ${offer.title}"
        } else offer.title
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        offer.end?.let { notification.setTimeoutAfter((it - now).coerceAtLeast(1)) }
        return try {
            manager.notify(offer.campaignId, if (type == "new") 1 else 2, notification.build())
            true
        } catch (_: SecurityException) { false }
    }

    fun cancel(campaignId: String) {
        manager.cancel(campaignId, 1)
        manager.cancel(campaignId, 2)
    }

    fun cancelAll() = manager.cancelAll()

    fun cancelReminders() {
        manager.activeNotifications.filter { it.id == 2 }.forEach { manager.cancel(it.tag, it.id) }
    }
}

const val NEW_GIVEAWAYS_CHANNEL = "new_giveaways"
const val REMINDERS_CHANNEL = "giveaway_reminders"
