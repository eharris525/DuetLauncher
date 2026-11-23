package dev.duet.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Person
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.graphics.drawable.toBitmap

/**
 * Puts more apps on the Surface Duo Glance Bar.
 *
 * The Glance Bar (System UI's "spine") only shows calls, texts, Teams and *conversation*
 * notifications that carry a colour. While the Duo is closed (screen off, locked), this
 * re-posts the latest notification of each app the user picked as a coloured conversation
 * notification, so it lights up on the bar too. The copies are removed as soon as the Duo is
 * unlocked, and whenever the original notification goes away, so the shade never keeps
 * duplicates around.
 */
class GlanceMirrorService : NotificationListenerService() {
    private val prefs get() = DuetApp.instance.prefs
    private lateinit var nm: NotificationManager
    private var away = false
    /** App package -> key of the original notification its copy is showing. */
    private val mirrored = HashMap<String, String>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> { away = true; mirrorAll() }
                Intent.ACTION_USER_PRESENT -> { away = false; clearAll() }
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        nm = getSystemService(NotificationManager::class.java)
        // The first version used a default-importance channel; the Glance Bar wants high.
        runCatching { nm.deleteNotificationChannel("glance_bar") }
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Glance Bar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Copies of your notifications for the Glance Bar while the Duo is closed"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
        )
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        instance = this
    }

    override fun onListenerDisconnected() {
        connected = false
        instance = null
        runCatching { unregisterReceiver(screenReceiver) }
        clearAll()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (away && wanted(sbn)) mirror(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (mirrored[sbn.packageName] != sbn.key) return
        // Show the app's next most recent notification instead, if it still has one.
        val next = activeOriginals().filter { it.packageName == sbn.packageName }.maxByOrNull { it.postTime }
        if (next != null && away) mirror(next) else cancelCopy(sbn.packageName)
    }

    private fun activeOriginals(): List<StatusBarNotification> =
        runCatching { activeNotifications.toList() }.getOrDefault(emptyList()).filter(::wanted)

    private fun wanted(sbn: StatusBarNotification): Boolean {
        if (!prefs.glanceMirror || sbn.packageName == packageName) return false
        if (sbn.packageName !in prefs.glanceApps) return false
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE) != 0) return false
        return n.extras.getCharSequence(Notification.EXTRA_TITLE) != null || n.extras.getCharSequence(Notification.EXTRA_TEXT) != null
    }

    private fun mirrorAll() {
        activeOriginals().groupBy { it.packageName }.values.forEach { list -> mirror(list.maxBy { it.postTime }) }
    }

    fun clearAll() {
        mirrored.keys.toList().forEach(::cancelCopy)
    }

    private fun cancelCopy(pkg: String) {
        mirrored.remove(pkg)
        runCatching { nm.cancel(pkg, NOTIFICATION_ID) }
    }

    private fun mirror(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        val n = sbn.notification
        val pm = packageManager
        val appName = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: appName
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val color = prefs.glanceColorFor(pkg) ?: n.color.takeIf { it != 0 && it != Notification.COLOR_DEFAULT } ?: DEFAULT_COLOR
        // Shortcuts may only use the publisher's own resources, so draw the app's icon to a bitmap.
        val appIcon = runCatching { Icon.createWithBitmap(pm.getApplicationIcon(pkg).toBitmap(192, 192)) }.getOrNull()
        val sender = Person.Builder().setName(appName).setKey(pkg).setIcon(appIcon).build()

        // Conversation notifications need a long-lived shortcut to count as "people".
        val shortcutId = "glance_$pkg"
        val launch = pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).setPackage(pkg)
        try {
            getSystemService(ShortcutManager::class.java).pushDynamicShortcut(
                ShortcutInfo.Builder(this, shortcutId)
                    .setShortLabel(appName)
                    .setLongLived(true)
                    .setPerson(sender)
                    .setIcon(appIcon ?: Icon.createWithResource(this, R.mipmap.ic_launcher))
                    .setIntent(launch.setAction(Intent.ACTION_MAIN))
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't publish the conversation shortcut for $pkg", e)
        }

        val style = Notification.MessagingStyle(Person.Builder().setName("You").build())
            .setConversationTitle(appName)
            .addMessage(if (text.isEmpty()) title else "$title: $text", sbn.postTime, sender)
        val copy = Notification.Builder(this, CHANNEL)
            .setSmallIcon(n.smallIcon ?: Icon.createWithResource(this, R.drawable.ic_launcher_fg))
            .setColor(color)
            .setStyle(style)
            .setShortcutId(shortcutId)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setContentIntent(n.contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setWhen(sbn.postTime)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .build()
        runCatching {
            nm.notify(pkg, NOTIFICATION_ID, copy)
            mirrored[pkg] = sbn.key
        }
    }

    companion object {
        private const val CHANNEL = "glance_bar_high"
        private const val TAG = "DuetGlance"
        private const val NOTIFICATION_ID = 7001
        private const val DEFAULT_COLOR = 0xFF8AB4F8.toInt()

        @Volatile var connected = false
            private set
        @Volatile private var instance: GlanceMirrorService? = null

        /** Removes all copies now (e.g. when the feature is switched off). */
        fun clearNow() = instance?.clearAll()

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners").orEmpty()
            return ComponentName(ctx, GlanceMirrorService::class.java).flattenToString() in flat
        }
    }
}
