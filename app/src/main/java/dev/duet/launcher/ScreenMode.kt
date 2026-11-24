package dev.duet.launcher

import android.app.Activity
import android.graphics.Rect
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/**
 * What the launcher window currently covers.
 *
 * On the Surface Duo / Duo 2, the window reports a separating hinge FoldingFeature only
 * while it is spanned across both screens. With the device folded back (one screen
 * active), or the launcher sitting on just one screen, there is no separating fold, so
 * we fall back to the single-screen layout.
 */
sealed interface ScreenMode {
    data object Single : ScreenMode

    /** [hinge] is in window pixels; [sideBySide] is true when the screens sit left/right. */
    data class Dual(val hinge: Rect, val sideBySide: Boolean) : ScreenMode

    /**
     * One continuous screen that folds (Pixel Fold, Galaxy Z Fold, ...), open. Laid out like
     * [Dual] around the fold line, but apps open full screen or in split screen (see [FoldSplit]).
     */
    data class Unfolded(val asDual: Dual) : ScreenMode
}

@Composable
fun rememberScreenMode(activity: Activity): ScreenMode {
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsStateWithLifecycle(initialValue = null)
    val folds = info?.displayFeatures?.filterIsInstance<FoldingFeature>().orEmpty()
    // The Duo's hinge is a real gap between two screens; a foldable's fold is a line on one screen.
    fun FoldingFeature.occluding() =
        occlusionType == FoldingFeature.OcclusionType.FULL || (bounds.width() > 0 && bounds.height() > 0)
    folds.firstOrNull { it.isSeparating && it.occluding() }?.let {
        return ScreenMode.Dual(hinge = it.bounds, sideBySide = it.orientation == FoldingFeature.Orientation.VERTICAL)
    }
    folds.firstOrNull { !it.occluding() }?.let {
        return ScreenMode.Unfolded(ScreenMode.Dual(hinge = it.bounds, sideBySide = it.orientation == FoldingFeature.Orientation.VERTICAL))
    }
    return ScreenMode.Single
}

fun ScreenMode.describe(): String = when (this) {
    ScreenMode.Single -> "Single screen"
    is ScreenMode.Unfolded -> "Foldable, open (tap: full screen, swipe: split screen)"
    is ScreenMode.Dual -> if (sideBySide) "Spanned across both screens (side by side)"
    else "Spanned across both screens (top and bottom)"
}

/** Shows [content] on just one of the two screens (the other is covered by an app). */
@Composable
fun OneScreenOf(mode: ScreenMode.Dual, useFirst: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    with(density) {
        if (mode.sideBySide) {
            Row(Modifier.fillMaxSize()) {
                if (useFirst) {
                    Box(Modifier.width(mode.hinge.left.toDp()).fillMaxHeight().windowInsetsPadding(bars)) { content() }
                } else {
                    Spacer(Modifier.width(mode.hinge.right.toDp()))
                    Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(bars)) { content() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (useFirst) {
                    Box(
                        Modifier.height(mode.hinge.top.toDp()).fillMaxWidth()
                            .windowInsetsPadding(bars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    ) { content() }
                } else {
                    Spacer(Modifier.height(mode.hinge.bottom.toDp()))
                    Box(
                        Modifier.weight(1f).fillMaxWidth()
                            .windowInsetsPadding(bars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    ) { content() }
                }
            }
        }
    }
}

/** Lays out two panes so that neither draws under the hinge. */
@Composable
fun DualSplit(
    mode: ScreenMode.Dual,
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val bars = WindowInsets.systemBars
    with(density) {
        if (mode.sideBySide) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(mode.hinge.left.toDp()).fillMaxHeight().windowInsetsPadding(bars)) { first() }
                Spacer(Modifier.width(mode.hinge.width().toDp()))
                Box(Modifier.weight(1f).fillMaxHeight().windowInsetsPadding(bars)) { second() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier.height(mode.hinge.top.toDp()).fillMaxWidth()
                        .windowInsetsPadding(bars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                ) { first() }
                Spacer(Modifier.height(mode.hinge.height().toDp()))
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .windowInsetsPadding(bars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                ) { second() }
            }
        }
    }
}
