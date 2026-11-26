package dev.duet.launcher

import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Google feed: swipe right on the home screen and the page slides aside to show Google's
 * Discover feed (the Google app's home page), like the feed left of the Pixel home screen.
 *
 * Google only lets its own and partner launchers embed the feed itself, so Duet opens the
 * Google app's feed on the same screen instead.
 */
object GoogleFeed {
    const val PKG = "com.google.android.googlequicksearchbox"

    fun available(): Boolean =
        runCatching { DuetApp.instance.packageManager.getLaunchIntentForPackage(PKG) != null }.getOrDefault(false)

    fun open() {
        val app = DuetApp.instance
        val i = app.packageManager.getLaunchIntentForPackage(PKG) ?: return
        val here = ScreenCoverage.lastTouchScreen.takeIf { it != Covered.NONE } ?: ScreenCoverage.tapTarget()
        app.apps.startOn(Intent(i), here)
    }
}

/**
 * Wraps the home screen: a right swipe that isn't taken by anything inside (widgets, app rows)
 * drags the page aside with a Google mark behind it, and releasing far enough opens the feed.
 */
@Composable
fun FeedSwipe(enabled: Boolean, content: @Composable BoxScope.() -> Unit) {
    if (!enabled || !remember { GoogleFeed.available() }) { Box(content = content); return }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) { 110.dp.toPx() }
    val drag = remember { Animatable(0f) }
    var raw by remember { mutableStateOf(0f) }
    var armed by remember { mutableStateOf(false) }

    Box(
        Modifier.pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { raw = 0f; armed = false },
                onHorizontalDrag = { change, d ->
                    raw = (raw + d).coerceAtLeast(0f)
                    if (raw > 0f) change.consume()
                    val shown = if (raw <= threshold) raw else threshold + sqrt(raw - threshold) * 6f
                    val nowArmed = raw >= threshold
                    if (nowArmed && !armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    armed = nowArmed
                    scope.launch { drag.snapTo(shown) }
                },
                onDragEnd = {
                    if (armed) GoogleFeed.open()
                    armed = false
                    scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
                },
                onDragCancel = { armed = false; scope.launch { drag.animateTo(0f) } },
            )
        }
    ) {
        val progress = (drag.value / threshold).coerceIn(0f, 1f)
        if (drag.value > 1f) {
            // The feed's edge, peeking in from the left as the page moves aside.
            Box(
                Modifier.fillMaxHeight().width(with(LocalDensity.current) { drag.value.toDp() })
                    .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.45f * progress), Color.Transparent))),
            )
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 18.dp)
                    .graphicsLayer { alpha = progress; val s = 0.7f + 0.3f * progress; scaleX = s; scaleY = s }
                    .size(52.dp).clip(CircleShape).background(if (armed) Color.White else Color.White.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("G", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4285F4))
            }
        }
        Box(Modifier.graphicsLayer { translationX = drag.value }, content = content)
    }
}
