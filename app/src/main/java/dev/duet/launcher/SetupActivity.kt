package dev.duet.launcher

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * First-run guide: the Android and Surface Duo settings Duet needs, each with its current
 * state and a button that opens the right settings page. Also reachable from Duet Settings.
 */
class SetupActivity : ComponentActivity() {
    /** Bumped on resume so every step re-checks its state after a trip to Settings. */
    private var checks by mutableIntStateOf(0)

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { checks++ }
    private val calendar = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) CalendarPermission.grants++
        checks++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            DuetTheme {
                @Suppress("UNUSED_EXPRESSION") checks // re-read states whenever this changes
                SetupScreen(
                    this,
                    onNotifications = { if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    onCalendar = { calendar.launch(Manifest.permission.READ_CALENDAR) },
                    onDone = { DuetApp.instance.prefs.setupDone = true; finish() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checks++
    }

    companion object {
        fun isDefaultHome(ctx: Context): Boolean {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            return ctx.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
        }

        fun globalOn(ctx: Context, key: String) = runCatching { Settings.Global.getInt(ctx.contentResolver, key, 0) != 0 }.getOrDefault(false)

        fun developerOptionsOn(ctx: Context) = globalOn(ctx, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED)
        fun freeformOn(ctx: Context) = globalOn(ctx, "enable_freeform_support")
        fun desktopModeOn(ctx: Context) = globalOn(ctx, "force_desktop_mode_on_external_displays")
    }
}

@Composable
private fun SetupScreen(activity: SetupActivity, onNotifications: () -> Unit, onCalendar: () -> Unit, onDone: () -> Unit) {
    val ctx = activity
    fun open(action: String) = runCatching { ctx.startActivity(Intent(action)) }
    val notificationsOk = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    val intro: LazyListScope.() -> Unit = {
            item {
                Spacer(Modifier.height(24.dp))
                Text("Welcome to Duet", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.height(6.dp))
                Text(
                    "A few settings make Duet work its best on the Surface Duo. Each step opens the right page; " +
                        "come back here afterwards. You can reopen this guide from Duet Settings.",
                    color = Color.White.copy(alpha = 0.7f),
                )
                Group("Essentials")
            }
            item {
                Step(
                    "Make Duet your home screen", SetupActivity.isDefaultHome(ctx),
                    "Choose Duet Launcher as the Home app so it opens when you press Home.",
                    "Choose home app",
                ) { open(Settings.ACTION_HOME_SETTINGS) }
            }
            item {
                Step(
                    "Turn on Duet screen detection", ScreenCoverage.serviceConnected,
                    "Settings → Accessibility → Duet screen detection. Lets Duet see which screen an app is on " +
                        "(never what's in it), and is what drives the pointer in desktop mode.",
                    "Open Accessibility",
                ) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            }
            item {
                Step(
                    "Span Duet across both screens", ScreenCoverage.geometry != null,
                    "Drag the home screen onto the hinge, like any app, to use both screens at once.",
                )
            }
            item { Group("Optional") }
            item {
                Step(
                    "Notification access", GlanceMirrorService.hasAccess(ctx),
                    "For the Music widget and extra Glance Bar apps. Turn on \"Duet Launcher\" in the list.",
                    "Open notification access",
                ) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            }
            item {
                Step(
                    "Calendar", CalendarData.hasPermission(ctx),
                    "Shows your events in the Calendar and Upcoming events widgets.",
                    "Allow calendar",
                    onClick = onCalendar,
                )
            }
            if (Build.VERSION.SDK_INT >= 33) item {
                Step(
                    "Notifications", notificationsOk,
                    "Duet shows a notification while desktop mode is on; tap it to bring back the touchpad.",
                    "Allow notifications",
                    onClick = onNotifications,
                )
            }
        }
    val desktop: LazyListScope.(Boolean) -> Unit = { topSpace ->
            item {
                if (topSpace) Spacer(Modifier.height(24.dp))
                Group("Desktop mode (experimental)")
                Text(
                    "Plug a monitor, TV or display glasses into the USB-C port and Duet puts a desktop with a taskbar " +
                        "on it, with the Duo as the touchpad. For apps in movable, resizable windows, turn on two " +
                        "developer options, then restart the Duo:",
                    color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                )
            }
            item {
                Step(
                    "1. Turn on Developer options", SetupActivity.developerOptionsOn(ctx),
                    "Settings → System → About → tap Build number seven times.",
                    "Open About phone",
                ) { open(Settings.ACTION_DEVICE_INFO_SETTINGS) }
            }
            item {
                Step(
                    "2. Enable freeform windows", SetupActivity.freeformOn(ctx),
                    "Developer options → \"Enable freeform windows\".",
                    "Open Developer options",
                ) { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            }
            item {
                Step(
                    "3. Force desktop mode", SetupActivity.desktopModeOn(ctx),
                    "Developer options → \"Force desktop mode\". Then restart the Duo (or unplug and reconnect the display).",
                    "Open Developer options",
                ) { open(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
            }
            item {
                Step(
                    "4. Duet screen detection", ScreenCoverage.serviceConnected,
                    "Needed for the pointer, taskbar and window controls (see Essentials).",
                )
            }
            item {
                Text(
                    "Using it: the touchpad opens on the Duo when you plug in (or tap the \"Desktop mode is on\" " +
                        "notification). Tap to click, use two fingers to scroll, and the Right click button or a " +
                        "two-finger tap for menus. Each window has controls on its title bar to snap it left or " +
                        "right, maximize it or make it full screen, and a corner you can drag to resize. Android " +
                        "doesn't let other apps resize an open window, so Duet closes it and reopens it at the new " +
                        "size: most apps come back where you were, but unsaved text can be lost.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp),
                )
            }
            item {
                Spacer(Modifier.height(24.dp))
                Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                Spacer(Modifier.height(32.dp))
            }
        }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF121316)).safeDrawingPadding()) {
        if (maxWidth > 840.dp) {
            // Spanned across the Duo's two screens: one half each, clear of the hinge.
            Row(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).padding(start = 24.dp, end = 32.dp)) { intro() }
                LazyColumn(Modifier.weight(1f).padding(start = 32.dp, end = 24.dp)) { desktop(true) }
            }
        } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 20.dp)) {
                intro()
                desktop(false)
            }
        }
    }
}

@Composable
private fun Group(title: String) {
    Text(
        title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 28.dp, bottom = 8.dp),
    )
}

/** One setting: a status dot (green when done, null for "can't tell"), what to do, and a button to do it. */
@Composable
private fun Step(title: String, done: Boolean?, body: String, action: String? = null, onClick: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.05f)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(12.dp).clip(CircleShape).background(
                    when (done) { true -> Color(0xFF5BD17A); false -> Color(0xFFFFB74D); null -> Color.White.copy(alpha = 0.25f) }
                )
            )
            Spacer(Modifier.size(10.dp))
            Text(title, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (done == true) Text("Done", color = Color(0xFF5BD17A), fontSize = 13.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(body, color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp)
        if (action != null && done != true) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledTonalButton(onClick = onClick) { Text(action) }
            }
        }
    }
}
