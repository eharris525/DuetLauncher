@file:OptIn(ExperimentalFoundationApi::class)

package dev.duet.launcher

import android.content.Intent
import android.os.SystemClock
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberCoroutineScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The clock is drawn once, floating above every layout. Each layout only reserves a
 * [ClockSlot]; the floating clock follows the slot — gliding when the launcher switches
 * between one- and two-screen arrangements, and tracking it exactly otherwise (scrolling).
 */
class ClockAnchor {
    var target by mutableStateOf(Offset.Unspecified)
    var size by mutableStateOf(IntSize.Zero)
    var slots by mutableIntStateOf(0)
    var alpha by mutableFloatStateOf(1f)
    /** Last laid-out position of each [glide]-tagged component, so its next instance can glide from there. */
    val placedAt = HashMap<String, Offset>()
    private var glideUntil = 0L

    fun glideForAWhile() { glideUntil = SystemClock.uptimeMillis() + GLIDE_WINDOW_MS }
    val gliding: Boolean get() = SystemClock.uptimeMillis() < glideUntil

    private companion object { const val GLIDE_WINDOW_MS = 700L }
}

val LocalClockAnchor = staticCompositionLocalOf<ClockAnchor?> { null }

/** Reserves the clock's space in a layout and reports where it is. */
@Composable
fun ClockSlot(alpha: Float = 1f) {
    val anchor = LocalClockAnchor.current ?: return
    val density = LocalDensity.current
    DisposableEffect(anchor) {
        anchor.slots++
        onDispose { anchor.slots-- }
    }
    SideEffect { anchor.alpha = alpha }
    Box(
        Modifier
            .padding(top = 28.dp, bottom = 20.dp)
            .size(with(density) { anchor.size.width.toDp() }, with(density) { anchor.size.height.toDp() })
            .onGloballyPositioned { anchor.target = it.positionInRoot() }
    )
}

/** Same motion as the floating clock: critically damped, one continuous ease. */
private val GlideSpring = spring<Offset>(dampingRatio = 1f, stiffness = 170f)

/**
 * Lets a layout component (favorites, alphabet bar, action button, widgets) glide like the
 * clock when the launcher switches arrangement. Each layout composes its own copy of the
 * component; the new copy starts drawn where the previous one was and eases into place.
 */
fun Modifier.glide(key: String): Modifier = composed {
    val anchor = LocalClockAnchor.current ?: return@composed this
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val scope = rememberCoroutineScope()
    this
        .onGloballyPositioned { coords ->
            val now = coords.positionInRoot()
            val before = anchor.placedAt.put(key, now)
            if (before != null && before != now && anchor.gliding) {
                // Undispatched: the offset is applied before this frame draws, so nothing flashes at the new spot.
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    offset.snapTo(offset.value + (before - now))
                    offset.animateTo(Offset.Zero, GlideSpring)
                }
            }
        }
        .graphicsLayer {
            val o = offset.value
            translationX = o.x
            translationY = o.y
            val s = 1f + (o.getDistance() / 4000f).coerceAtMost(0.04f)
            scaleX = s; scaleY = s
        }
}

@Composable
fun FloatingClock(activity: MainActivity, anchor: ClockAnchor) {
    val position = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    val presence by animateFloatAsState(if (anchor.slots > 0) 1f else 0f, tween(220), label = "clockPresence")

    LaunchedEffect(anchor.target) {
        val t = anchor.target
        if (!t.isSpecified) return@LaunchedEffect
        when {
            !placed -> { position.snapTo(t); placed = true }
            // Critically damped: one continuous ease with no wobble at the end.
            anchor.gliding -> position.animateTo(t, GlideSpring)
            else -> position.snapTo(t)
        }
    }

    val alpha = presence * anchor.alpha
    // Fully hidden (e.g. the app list is open): don't keep an invisible clock catching taps.
    if (!placed || alpha < 0.01f) return
    Box(
        Modifier
            // Translate at draw time only: moving it costs no layout pass per frame.
            .graphicsLayer {
                translationX = position.value.x
                translationY = position.value.y
                this.alpha = alpha
                // A slight lift while it travels.
                val travel = (anchor.target - position.value).getDistance()
                val s = 1f + (travel / 4000f).coerceAtMost(0.04f)
                scaleX = s; scaleY = s
            }
            .onSizeChanged { anchor.size = it }
    ) { ClockPill(activity) }
}

@Composable
private fun ClockPill(activity: MainActivity) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000 - now % 1000)
        }
    }
    val is24 = DateFormat.is24HourFormat(ctx)
    val minute = now / 60_000
    val time = remember(minute, is24) { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now)) }
    val weekday = remember(minute) { SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(now)) }
    val date = remember(minute) { SimpleDateFormat("MMMM d", Locale.getDefault()).format(Date(now)) }
    var menu by remember { mutableStateOf(false) }

    val pill = RoundedCornerShape(percent = 50)
    Box {
        Row(
            Modifier
                .glass(pill)
                .combinedClickable(
                    onClick = {
                        // Like tapping an app: opens on the screen opposite the touch.
                        DuetApp.instance.apps.startOn(Intent(AlarmClock.ACTION_SHOW_ALARMS), ScreenCoverage.lastTouchScreen.opposite(), asApp = true)
                    },
                    onLongClick = { menu = true },
                )
                .padding(start = 30.dp, end = 32.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(time, style = OnWallpaper, fontSize = 54.sp, fontWeight = FontWeight.Light)
            Box(
                Modifier.padding(horizontal = 18.dp).width(1.dp).height(44.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent)))
            )
            Column {
                Text(weekday, style = OnWallpaper, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(date, style = OnWallpaper, fontSize = 15.sp, color = Color.White.copy(alpha = 0.8f))
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Add widget") }, onClick = { menu = false; activity.ui.pickerOpen = true })
            DropdownMenuItem(text = { Text("Edit widgets") }, onClick = { menu = false; activity.ui.editWidgets = true })
            DropdownMenuItem(text = { Text("Wallpaper") }, onClick = {
                menu = false
                runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Wallpaper")) }
            })
            DropdownMenuItem(text = { Text("Launcher settings") }, onClick = { menu = false; activity.openSettings() })
        }
    }
}

