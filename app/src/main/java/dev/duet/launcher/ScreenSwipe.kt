package dev.duet.launcher

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * Swipe an app row left or right to open it on the left or right screen (top / bottom when
 * the Duo is rotated). Only active while the launcher spans both screens, or on an open
 * foldable, where it opens the app in split screen on that side instead.
 */
class ScreenSwipe internal constructor(
    private val scope: CoroutineScope,
    private val thresholdPx: Float,
    private val maxPx: Float,
) {
    private val drag = Animatable(0f)
    private var raw = 0f
    var armed by mutableStateOf(false)
        private set

    /** Where the row is drawn: follows the finger, with resistance past the threshold. */
    val offset: Float get() = drag.value

    /** 0..1 progress towards the threshold, signed by direction. */
    val progress: Float get() = (drag.value / thresholdPx).coerceIn(-1f, 1f)

    internal var modifier: Modifier = Modifier

    internal fun onDrag(delta: Float, tick: () -> Unit) {
        raw += delta
        val a = abs(raw)
        val shown = if (a <= thresholdPx) a else thresholdPx + sqrt(a - thresholdPx) * 6f
        val nowArmed = a >= thresholdPx
        if (nowArmed != armed) { armed = nowArmed; if (nowArmed) tick() }
        scope.launch { drag.snapTo(sign(raw) * shown.coerceAtMost(maxPx)) }
    }

    /** Returns the chosen screen if released past the threshold. */
    internal fun onEnd(): Covered? {
        val result = if (armed) (if (raw > 0) Covered.SECOND else Covered.FIRST) else null
        raw = 0f
        armed = false
        scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
        return result
    }

    /** "Left screen" / "Right screen" label revealed behind the row as it slides. */
    @Composable
    fun Hint(modifier: Modifier) {
        val p = progress
        if (p == 0f) return
        val g = ScreenCoverage.geometry
        val split = g == null && FoldSplit.canPick
        val vertical = if (split) !FoldSplit.sideBySide else g != null && !g.sideBySide
        val label = when {
            split && !FoldSplit.canChooseSide -> if (p > 0) "Split screen →" else "← Split screen"
            split -> if (p > 0) (if (vertical) "Split, bottom ↓" else "Split right →") else (if (vertical) "↑ Split, top" else "← Split left")
            p > 0 -> if (vertical) "Bottom screen →" else "Right screen →"
            else -> if (vertical) "← Top screen" else "← Left screen"
        }
        Box(
            modifier.graphicsLayer { alpha = abs(p) * if (armed) 1f else 0.6f },
            contentAlignment = if (p > 0) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Text(
                label,
                style = OnWallpaper,
                fontSize = 14.sp,
                fontWeight = if (armed) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
fun rememberScreenSwipe(enabled: Boolean, onPick: (Covered) -> Unit): ScreenSwipe {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val pick by rememberUpdatedState(onPick)
    val swipe = remember(density) {
        with(density) { ScreenSwipe(scope, thresholdPx = 72.dp.toPx(), maxPx = 140.dp.toPx()) }
    }
    swipe.modifier = if (!enabled) Modifier else Modifier.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, amount ->
                // Nothing to choose between unless the launcher spans both screens.
                if (ScreenCoverage.geometry == null && !FoldSplit.canPick) return@detectHorizontalDragGestures
                change.consume()
                swipe.onDrag(amount) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
            },
            onDragEnd = { swipe.onEnd()?.let(pick) },
            onDragCancel = { swipe.onEnd() },
        )
    }
    return swipe
}
