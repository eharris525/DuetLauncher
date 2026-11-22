package dev.duet.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class DuetAction(val title: String) {
    NONE("Do nothing"),
    SEARCH("Search apps"),
    APP_LIST("Open app list"),
    NOTIFICATIONS("Notifications"),
    QUICK_SETTINGS("Quick settings"),
    CAMERA("Camera"),
    ASSISTANT("Voice assistant"),
    WEB_SEARCH("Web search"),
    LAUNCH_APP("Open an app…"),
    SETTINGS("Launcher settings"),
}

fun DuetAction.describe(appKey: String): String =
    if (this == DuetAction.LAUNCH_APP) DuetApp.instance.apps.all.firstOrNull { it.key == appKey }?.label ?: "Open an app (none chosen)"
    else title

/**
 * Runs [action]. Anything it opens goes to the screen the button was pressed on (the screen
 * of the touch), and the launcher moves over to the other screen.
 */
fun performAction(activity: MainActivity, action: DuetAction, appKey: String) {
    val ui = activity.ui
    val apps = DuetApp.instance.apps
    val here = ScreenCoverage.lastTouchScreen
    fun start(intent: Intent, asApp: Boolean = false) = apps.startOn(intent, here, asApp)
    when (action) {
        DuetAction.NONE -> Unit
        DuetAction.SEARCH -> { ui.listOpen = true; ui.query = ""; ui.searchRequest++ }
        DuetAction.APP_LIST -> ui.listOpen = true
        DuetAction.NOTIFICATIONS -> expandStatusBar(activity, "expandNotificationsPanel")
        DuetAction.QUICK_SETTINGS -> expandStatusBar(activity, "expandSettingsPanel")
        DuetAction.CAMERA -> start(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), asApp = true)
        DuetAction.ASSISTANT -> start(Intent(Intent.ACTION_VOICE_COMMAND)) || start(Intent(Intent.ACTION_ASSIST))
        DuetAction.WEB_SEARCH -> start(Intent(Intent.ACTION_WEB_SEARCH))
        DuetAction.LAUNCH_APP -> apps.all.firstOrNull { it.key == appKey }?.let { apps.launchOn(it, here) }
        DuetAction.SETTINGS -> start(Intent(activity, SettingsActivity::class.java))
    }
}

/** StatusBarManager's expand methods aren't public SDK, but are allowed for apps holding EXPAND_STATUS_BAR. */
@SuppressLint("WrongConstant")
private fun expandStatusBar(ctx: Context, method: String) {
    runCatching {
        val sbm = ctx.getSystemService("statusbar")
        Class.forName("android.app.StatusBarManager").getMethod(method).invoke(sbm)
    }
}

/**
 * Round button: tap runs one action, swipe up runs another. The button follows the finger
 * a little while dragging and springs back on release.
 */
@Composable
fun DuetActionButton(activity: MainActivity, modifier: Modifier = Modifier) {
    val prefs = DuetApp.instance.prefs
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 48.dp.toPx() }
    val maxTravel = with(density) { 36.dp.toPx() }
    val tapSlop = with(density) { 12.dp.toPx() }
    val lift = remember { Animatable(0f) }
    val press = remember { Animatable(1f) }

    Box(
        modifier
            .offset { IntOffset(0, lift.value.roundToInt()) }
            .scale(press.value)
            .size(56.dp)
            .then(if (DuetApp.instance.prefs.surfaceStyle == SurfaceStyle.MINIMAL) Modifier else Modifier.shadow(8.dp, CircleShape))
            .clip(CircleShape)
            .then(if (DuetApp.instance.prefs.surfaceStyle == SurfaceStyle.LIQUID_GLASS) Modifier.background(Color(0x991B1C20)) else Modifier)
            .glass(CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    scope.launch { press.animateTo(0.92f, spring(stiffness = Spring.StiffnessMedium)) }
                    var dy = 0f
                    var dx = 0f
                    var armed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        dy = change.position.y - down.position.y
                        dx = change.position.x - down.position.x
                        change.consume()
                        val up = (-dy).coerceAtLeast(0f)
                        scope.launch { lift.snapTo(-(up.coerceAtMost(maxTravel * 2) / 2f)) }
                        if (!armed && up > swipeThreshold) {
                            armed = true
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        } else if (armed && up < swipeThreshold) {
                            armed = false
                        }
                    }
                    scope.launch { lift.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                    scope.launch { press.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
                    when {
                        armed -> performAction(activity, prefs.swipeAction, prefs.swipeApp)
                        abs(dy) < tapSlop && abs(dx) < tapSlop -> performAction(activity, prefs.tapAction, prefs.tapApp)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Two panes and a hinge — the Duet glyph.
        val tint = accent
        Canvas(Modifier.size(22.dp)) {
            val paneW = size.width * 0.4f
            val r = CornerRadius(size.width * 0.08f)
            drawRoundRect(tint, Offset.Zero, Size(paneW, size.height), r)
            drawRoundRect(Color.White, Offset(size.width - paneW, 0f), Size(paneW, size.height), r)
        }
    }
}
