package dev.duet.launcher

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.app.Notification
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.format.DateUtils
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** The panels that open from the taskbar, like Samsung DeX's notification centre and recent apps. */
enum class DesktopPanel { NOTIFICATIONS, RECENTS }

val PanelColor = Color(0xF21B2028)

fun togglePanel(p: DesktopPanel) {
    DesktopMode.menuOpen = false
    DesktopMode.contextMenu = null
    DesktopMode.panel = if (DesktopMode.panel == p) null else p
}

// --- Taskbar icons ----------------------------------------------------------------------

enum class BarGlyph { APPS, RECENTS, HOME, BACK, BELL, KEYBOARD }

/** Line icons for the taskbar, drawn so they look the same with any font. */
@Composable
fun BarIcon(g: BarGlyph, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = s * 0.085f
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (g) {
            BarGlyph.APPS -> for (r in 0..2) for (c in 0..2)
                drawCircle(color, s * 0.075f, Offset(s * (0.22f + c * 0.28f), s * (0.22f + r * 0.28f)))
            BarGlyph.RECENTS -> {
                drawLine(color, Offset(s * 0.2f, s * 0.2f), Offset(s * 0.2f, s * 0.8f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.5f, s * 0.2f), Offset(s * 0.5f, s * 0.8f), w, StrokeCap.Round)
                drawLine(color, Offset(s * 0.8f, s * 0.2f), Offset(s * 0.8f, s * 0.8f), w, StrokeCap.Round)
            }
            BarGlyph.HOME -> drawRoundRect(color, Offset(s * 0.2f, s * 0.2f), Size(s * 0.6f, s * 0.6f), CornerRadius(s * 0.16f), style = st)
            BarGlyph.BACK -> drawPath(Path().apply {
                moveTo(s * 0.62f, s * 0.2f); lineTo(s * 0.3f, s * 0.5f); lineTo(s * 0.62f, s * 0.8f)
            }, color, style = st)
            BarGlyph.BELL -> {
                drawPath(Path().apply {
                    moveTo(s * 0.2f, s * 0.72f)
                    lineTo(s * 0.8f, s * 0.72f)
                    quadraticTo(s * 0.7f, s * 0.62f, s * 0.7f, s * 0.45f)
                    cubicTo(s * 0.7f, s * 0.16f, s * 0.3f, s * 0.16f, s * 0.3f, s * 0.45f)
                    quadraticTo(s * 0.3f, s * 0.62f, s * 0.2f, s * 0.72f)
                    close()
                }, color, style = st)
                drawLine(color, Offset(s * 0.43f, s * 0.86f), Offset(s * 0.57f, s * 0.86f), w, StrokeCap.Round)
            }
            BarGlyph.KEYBOARD -> {
                drawRoundRect(color, Offset(s * 0.08f, s * 0.24f), Size(s * 0.84f, s * 0.52f), CornerRadius(s * 0.08f), style = st)
                for (r in 0..1) for (c in 0..3) drawCircle(color, s * 0.04f, Offset(s * (0.26f + c * 0.16f), s * (0.4f + r * 0.13f)))
                drawLine(color, Offset(s * 0.34f, s * 0.64f), Offset(s * 0.66f, s * 0.64f), w * 0.8f, StrokeCap.Round)
            }
        }
    }
}

/** Battery level and charging state, re-read every half minute. */
@Composable
fun BatteryTray() {
    val app = DuetApp.instance
    var level by remember { mutableIntStateOf(-1) }
    var charging by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && sc > 0) level = l * 100 / sc
                charging = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            }
            delay(30_000)
        }
    }
    if (level < 0) return
    val tint = if (level <= 15 && charging == 0) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.85f)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
        Canvas(Modifier.size(width = 22.dp, height = 12.dp)) {
            val h = size.height; val bw = size.width - 3.dp.toPx()
            drawRoundRect(tint, Offset.Zero, Size(bw, h), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
            drawRect(tint, Offset(bw + 0.5.dp.toPx(), h * 0.3f), Size(2.dp.toPx(), h * 0.4f))
            val inset = 2.5.dp.toPx()
            drawRoundRect(tint, Offset(inset, inset), Size((bw - inset * 2) * level / 100f, h - inset * 2), CornerRadius(1.5.dp.toPx()))
        }
        Spacer(Modifier.width(5.dp))
        Text((if (charging != 0) "⚡" else "") + "$level%", fontSize = 12.sp, color = tint)
    }
}

// --- Notification centre ----------------------------------------------------------------

@Composable
fun NotificationPanel(modifier: Modifier) {
    val list = GlanceMirrorService.shown
    val ctx = DuetApp.instance
    Column(
        modifier.width(400.dp).heightIn(max = 640.dp).clip(RoundedCornerShape(16.dp)).background(PanelColor)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, null) { }
            .padding(14.dp)
    ) {
        // Quick settings, like the top of DeX's notification panel.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickTile("Wi-Fi", Modifier.weight(1f)) { openOnDesktop(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            QuickTile("Bluetooth", Modifier.weight(1f)) { openOnDesktop(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            QuickTile("Settings", Modifier.weight(1f)) { openOnDesktop(Intent(Settings.ACTION_SETTINGS)) }
            QuickTile("Lock", Modifier.weight(1f)) {
                DesktopMode.panel = null
                DesktopMode.globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) Text(
                "Clear all", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { GlanceMirrorService.dismissAll() }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        when {
            !GlanceMirrorService.hasAccess(ctx) -> PanelHint("Allow notification access for Duet Launcher on your phone (Duet Settings → Setup guide) to see notifications here.")
            list.isEmpty() -> PanelHint("No notifications")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { n: StatusBarNotification -> n.key }) { n -> NotificationCard(n) }
            }
        }
    }
}

@Composable
private fun PanelHint(text: String) {
    Text(text, fontSize = 13.sp, color = Color.White.copy(alpha = 0.55f), modifier = Modifier.padding(vertical = 18.dp))
}

@Composable
private fun QuickTile(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = Color.White) }
}

@Composable
private fun NotificationCard(sbn: StatusBarNotification) {
    val app = DuetApp.instance
    val info = remember(sbn.packageName) { app.apps.all.firstOrNull { it.component.packageName == sbn.packageName } }
    val extras = sbn.notification.extras
    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
    val clearable = sbn.isClearable
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.06f))
            .clickable { openNotification(sbn) }.padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (info != null) AppIcon(info, 28) else Box(Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(info?.label ?: sbn.packageName, fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                Text(" · " + DateUtils.getRelativeTimeSpanString(sbn.postTime, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), fontSize = 11.sp, color = Color.White.copy(alpha = 0.45f), maxLines = 1)
            }
            if (title != null) Text(title, fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (text != null) Text(text, fontSize = 13.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (clearable) Box(
            Modifier.size(26.dp).clip(CircleShape).clickable { GlanceMirrorService.dismiss(sbn) },
            contentAlignment = Alignment.Center,
        ) { Text("✕", fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f)) }
    }
}

/** Opens what a notification points to, in a window on the external display. */
private fun openNotification(sbn: StatusBarNotification) {
    DesktopMode.panel = null
    val d = DesktopMode.display
    val opts = DesktopMode.options() ?: d?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it.displayId).toBundle() }
    val pi = sbn.notification.contentIntent
    val sent = pi != null && runCatching {
        pi.send(DuetApp.instance, 0, null, null, null, null, opts)
    }.isSuccess
    if (!sent) DesktopMode.launchPackage(sbn.packageName)
    else if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) GlanceMirrorService.dismiss(sbn)
}

fun openOnDesktop(i: Intent) {
    DesktopMode.panel = null
    DesktopMode.menuOpen = false
    runCatching { DuetApp.instance.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), DesktopMode.options()) }
}

// --- Recent apps ------------------------------------------------------------------------

/** Every open window as a card, like DeX's recent apps: click to switch, ✕ to close. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecentsPanel(modifier: Modifier) {
    val apps = DuetApp.instance.apps.all
    val open = DesktopMode.running.mapNotNull { pkg -> apps.firstOrNull { it.component.packageName == pkg } }
    Column(
        modifier.width(760.dp).clip(RoundedCornerShape(16.dp)).background(PanelColor)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, null) { }
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Recent apps", fontWeight = FontWeight.SemiBold, color = Color.White, modifier = Modifier.weight(1f))
            if (open.isNotEmpty()) Text(
                "Close all", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                    DesktopMode.panel = null
                    DesktopMode.closeAll()
                }.padding(6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        if (open.isEmpty()) PanelHint("No apps open")
        else LazyVerticalGrid(GridCells.Fixed(4), Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(open, key = { it.key }) { a ->
                val pkg = a.component.packageName
                val active = DesktopMode.activeWindow?.pkg == pkg
                Box(
                    Modifier.height(130.dp).clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = if (active) 0.14f else 0.07f))
                        .border(if (active) 1.5.dp else 0.dp, MaterialTheme.colorScheme.primary.copy(alpha = if (active) 0.8f else 0f), RoundedCornerShape(14.dp))
                        .combinedClickable(onClick = { DesktopMode.panel = null; DesktopMode.launch(a) }, onLongClick = { showContextMenu(pkg) }),
                ) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        AppIcon(a, 48)
                        Spacer(Modifier.height(8.dp))
                        Text(a.label, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp).clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f)).clickable { DesktopMode.close(pkg) },
                        contentAlignment = Alignment.Center,
                    ) { Text("✕", fontSize = 12.sp, color = Color.White) }
                }
            }
        }
    }
}

/** Clock in the taskbar's tray; click it for the notification centre (as in DeX). */
@Composable
fun TrayClock(onClick: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) } }
    Column(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now)), fontSize = 14.sp, color = Color.White)
        Text(java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(now)), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

