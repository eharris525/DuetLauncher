package dev.duet.launcher

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The Duo as a touchpad for the external display (see [DesktopMode]). */
class TouchpadActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setFocusable(false)
        val closeAt = DesktopMode.closeTouchpad
        setContent {
            DuetTheme {
                LaunchedEffect(DesktopMode.closeTouchpad) { if (DesktopMode.closeTouchpad != closeAt) finish() }
                TouchpadScreen(onClose = { finish() }, onTyping = { setFocusable(it) })
            }
        }
    }

    /**
     * Touching a focusable window moves focus to the Duo's display, and Back (and key focus)
     * then goes to the Duo instead of the desktop. So the touchpad only takes focus while its
     * keyboard is open.
     */
    private fun setFocusable(focusable: Boolean) {
        if (focusable) window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        else window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
    }
}

@Composable
private fun TouchpadScreen(onClose: () -> Unit, onTyping: (Boolean) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var typing by remember { mutableStateOf(false) }
    LaunchedEffect(typing) { onTyping(typing) }
    Box(Modifier.fillMaxSize().background(Color(0xFF050608)).safeDrawingPadding()) {
        when {
            !DesktopMode.active -> Message("No external display", "Connect a monitor or TV to use desktop mode.", onClose, "Close")
            !DesktopMode.canControl -> Message(
                "Turn on Duet screen detection",
                "The touchpad moves the pointer through Duet's accessibility service. Turn on \"Duet screen detection\", then come back.",
                { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } },
                "Open Accessibility settings",
            )
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // Spanned across both screens. (Not just landscape: the keyboard also makes the area wider than tall.)
                val wide = maxWidth > 840.dp
                val controls: @Composable () -> Unit = {
                    Controls(typing, onTyping = { typing = it }, onClose = onClose)
                }
                if (wide) {
                    // Spanned across both screens: pad on one, buttons and keyboard on the other.
                    Row(Modifier.fillMaxSize()) {
                        Pad(Modifier.weight(1f).padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 28.dp))
                        Box(Modifier.weight(1f).padding(start = 28.dp, end = 16.dp, top = 16.dp, bottom = 16.dp)) { controls() }
                    }
                } else {
                    Column(Modifier.fillMaxSize().padding(16.dp)) {
                        Pad(Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        controls()
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(title: String, body: String, action: () -> Unit, actionLabel: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Color.White.copy(alpha = 0.7f))
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(onClick = action) { Text(actionLabel) }
    }
}

@Composable
private fun Pad(modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(20.dp)).background(Color(0xFF101318))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Text(
                "Move: one finger · Click: tap · Right-click: two-finger tap\nScroll: two fingers · Drag: double-tap and hold, or hold still",
                fontSize = 12.sp, color = Color.White.copy(alpha = 0.3f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(14.dp),
            )
            AndroidView({ TouchpadView(it) }, Modifier.fillMaxSize())
        }
        // Like a laptop touchpad's buttons.
        Row(Modifier.fillMaxWidth().height(64.dp)) {
            PadButton("Left click", Modifier.weight(1f)) { DesktopMode.click() }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.08f)))
            PadButton("Right click", Modifier.weight(1f)) { DesktopMode.rightClick() }
        }
    }
}

@Composable
private fun PadButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.fillMaxHeight().background(Color.White.copy(alpha = 0.04f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)) }
}

@Composable
private fun Controls(typing: Boolean, onTyping: (Boolean) -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (typing) Keyboard(onDone = { onTyping(false) })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key("◀  Back") { DesktopMode.back() }
            Key("⊞  Start") { DesktopMode.toggleMenu() }
            Key("▭  Desktop") { DesktopMode.showDesktop() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Key(if (typing) "Hide keyboard" else "⌨  Keyboard") { onTyping(!typing) }
            Key("Exit touchpad", onClick = onClose)
        }
    }
}

@Composable
private fun RowScope.Key(label: String, onClick: () -> Unit) {
    Box(
        Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.08f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 15.sp) }
}

/**
 * Types into whatever has focus on the desktop. It mirrors that text box: it starts with what's
 * already there, and every change replaces the text on the desktop.
 */
@Composable
private fun Keyboard(onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    var value by remember { DesktopMode.currentText().let { mutableStateOf(TextFieldValue(it, TextRange(it.length))) } }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = value,
        onValueChange = { v ->
            if (v.text != value.text) failed = !DesktopMode.setText(v.text)
            value = v
        },
        modifier = Modifier.fillMaxWidth().focusRequester(focus),
        singleLine = true,
        placeholder = { Text(if (DesktopMode.menuOpen) "Search apps" else "Type into the selected text box") },
        supportingText = if (failed) { { Text("Click a text box on the desktop first.") } } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = {
            DesktopMode.enter()
            value = TextFieldValue("")
        }),
    )
    LaunchedEffect(DesktopMode.menuOpen) {
        // Switching between the start menu and an app: pick up the new target's text.
        val t = DesktopMode.currentText()
        value = TextFieldValue(t, TextRange(t.length))
    }
}

/** Turns touches into pointer moves, clicks, scrolls and drags on the external display. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
private class TouchpadView(ctx: Context) : View(ctx) {
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val phoneDensity = ctx.resources.displayMetrics.density

    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var downAt = 0L
    private var travel = 0f
    private var maxPointers = 0
    private var scrolling = false
    private var dragging = false
    /** Second touch soon after a tap: moving now drags instead of just pointing. */
    private var dragArmed = false
    private var lastTapUp = 0L

    /** Recent finger positions while scrolling (time, x, y), for the speed at lift-off. */
    private val samples = ArrayDeque<Triple<Long, Float, Float>>()

    /** Display pixels scrolled per touchpad pixel: about 2.5x the finger's movement, like a laptop touchpad. */
    private fun scrollGain() = DesktopMode.density() / phoneDensity * 2.5f

    /** Ends a scroll with the fingers' speed over the last moments, so a quick lift keeps gliding. */
    private fun endScroll() {
        val now = SystemClock.uptimeMillis()
        var vx = 0f; var vy = 0f
        val first = samples.firstOrNull(); val last = samples.lastOrNull()
        // Fingers that stopped before lifting (no movement in the last 50 ms) don't fling.
        if (first != null && last != null && last !== first && now - last.first < 50) {
            val dt = (last.first - first.first).coerceAtLeast(1) / 1000f
            vx = (last.second - first.second) / dt * scrollGain()
            vy = (last.third - first.third) / dt * scrollGain()
        }
        samples.clear()
        DesktopMode.endScroll(vx, vy)
    }

    private val holdToDrag = Runnable {
        if (!dragging && !scrolling && maxPointers == 1 && travel < slop) {
            dragging = true
            DesktopMode.startDrag()
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
        return x / e.pointerCount to y / e.pointerCount
    }

    /** Desktop pixels per touchpad pixel, faster for quick swipes (pointer acceleration). */
    private fun gain(dist: Float, dt: Long): Float {
        val speedDp = dist / phoneDensity / max(dt, 1L) // dp per ms
        val base = DesktopMode.density() / phoneDensity * (0.6f + DuetApp.instance.prefs.touchpadSpeed * 0.16f)
        return base * (0.7f + min(speedDp, 3f) * 0.8f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val now = SystemClock.uptimeMillis()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y; lastT = now; downAt = now
                travel = 0f; maxPointers = 1; scrolling = false; dragging = false
                dragArmed = now - lastTapUp < DOUBLE_TAP_MS
                if (!dragArmed) postDelayed(holdToDrag, HOLD_MS)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                removeCallbacks(holdToDrag)
                maxPointers = max(maxPointers, e.pointerCount)
                if (dragging) { DesktopMode.endDrag(); dragging = false }
                centroid(e).let { lastX = it.first; lastY = it.second }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Re-anchor on the remaining fingers so the pointer doesn't jump.
                val rest = (0 until e.pointerCount).filter { it != e.actionIndex }
                lastX = rest.map { e.getX(it) }.average().toFloat(); lastY = rest.map { e.getY(it) }.average().toFloat()
                if (e.pointerCount - 1 < 2 && scrolling) { endScroll(); scrolling = false }
            }
            MotionEvent.ACTION_MOVE -> {
                val (x, y) = if (e.pointerCount >= 2) centroid(e) else e.x to e.y
                val dx = x - lastX; val dy = y - lastY
                val dist = hypot(dx, dy)
                travel += dist
                if (travel > slop) removeCallbacks(holdToDrag)
                if (e.pointerCount >= 2) {
                    if (travel > slop) {
                        if (!scrolling) samples.clear()
                        scrolling = true
                        DesktopMode.scrollBy(dx * scrollGain(), dy * scrollGain())
                        samples.addLast(Triple(now, x, y))
                        while (samples.size > 2 && now - samples.first().first > 100) samples.removeFirst()
                    }
                } else if (maxPointers == 1) {
                    if (dragArmed && !dragging && travel > slop) { dragging = true; DesktopMode.startDrag() }
                    val g = gain(dist, now - lastT)
                    DesktopMode.movePointer(dx * g, dy * g)
                }
                lastX = x; lastY = y; lastT = now
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdToDrag)
                val tap = e.actionMasked == MotionEvent.ACTION_UP && travel < slop * 2 && now - downAt < TAP_MS
                when {
                    dragging -> { DesktopMode.endDrag(); dragging = false }
                    scrolling -> { endScroll(); scrolling = false }
                    tap && maxPointers >= 2 -> DesktopMode.longPress()
                    tap -> { DesktopMode.click(); lastTapUp = now; return true }
                }
                lastTapUp = 0L
            }
        }
        return true
    }

    private companion object {
        const val TAP_MS = 250L
        const val DOUBLE_TAP_MS = 280L
        const val HOLD_MS = 550L
    }
}
