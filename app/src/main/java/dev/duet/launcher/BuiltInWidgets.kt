package dev.duet.launcher

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val Dim = Color.White.copy(alpha = 0.65f)
private val Faint = Color.White.copy(alpha = 0.3f)

/** A built-in widget: themed panel, tap action, and the widget's content. */
@Composable
fun BuiltInWidget(activity: MainActivity, config: BuiltInConfig, modifier: Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .then(if (config.panel) Modifier.glass(shape) else Modifier.clip(shape))
            .clickable { runWidgetTap(activity, config) }
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        when (config.kind) {
            BuiltInKind.CALENDAR -> CalendarWidget(config)
            BuiltInKind.AGENDA -> AgendaWidget(config)
            BuiltInKind.WEATHER -> WeatherWidget(config)
            BuiltInKind.MUSIC -> MusicWidget()
            BuiltInKind.BATTERY -> BatteryWidget()
            BuiltInKind.ALARM -> AlarmWidget()
        }
    }
}

/** Tapping a widget: its own app by default, otherwise the chosen Duet action or app. Placed like a tapped app. */
fun runWidgetTap(activity: MainActivity, c: BuiltInConfig) {
    val target = ScreenCoverage.tapTarget()
    if (c.tap != TAP_DEFAULT) {
        val action = DuetAction.entries.firstOrNull { it.name == c.tap } ?: return
        return performAction(activity, action, c.tapApp, target)
    }
    val apps = DuetApp.instance.apps
    when (c.kind) {
        BuiltInKind.CALENDAR, BuiltInKind.AGENDA ->
            apps.startOn(Intent(Intent.ACTION_VIEW, CalendarContract.CONTENT_URI.buildUpon().appendPath("time").build()), target, asApp = true)
        BuiltInKind.WEATHER ->
            apps.startOn(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com/search?q=weather+" + android.net.Uri.encode(c.place))), target)
        BuiltInKind.MUSIC -> MusicSessions.current?.sessionActivity?.let { pi ->
            ScreenCoverage.launchOptions(target).let { opts -> runCatching { pi.send(activity, 0, null, null, null, null, opts) } }
        } ?: Unit
        BuiltInKind.BATTERY -> apps.startOn(Intent(Intent.ACTION_POWER_USAGE_SUMMARY), target)
        BuiltInKind.ALARM -> apps.startOn(Intent(AlarmClock.ACTION_SHOW_ALARMS), target, asApp = true)
    }
}

/** Current time, refreshed every [stepMs] (aligned to the step). */
@Composable
private fun rememberNow(stepMs: Long = 60_000L): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(stepMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(stepMs - now % stepMs)
        }
    }
    return now
}

/** Re-reads calendar data when it changes or permission is granted. */
@Composable
private fun rememberCalendarVersion(): Int {
    val ctx = LocalContext.current
    var version by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { version++ }
        }
        runCatching { ctx.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }
        onDispose { ctx.contentResolver.unregisterContentObserver(observer) }
    }
    return version + CalendarPermission.grants
}

object CalendarPermission {
    /** Bumped when the user grants calendar access, so calendar widgets reload. */
    var grants by mutableStateOf(0)
}

// --- Calendar ---------------------------------------------------------------------------

@Composable
private fun CalendarWidget(c: BuiltInConfig) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val today = remember(now / 3_600_000) { Calendar.getInstance() }
    val month = remember(today.get(Calendar.MONTH), today.get(Calendar.YEAR), c.mondayFirst) {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
    }
    val version = rememberCalendarVersion()
    val eventDays by produceState(emptySet<Int>(), month.timeInMillis, c.showEvents, version) {
        value = if (!c.showEvents) emptySet() else withContext(Dispatchers.IO) {
            val end = (month.clone() as Calendar).apply { add(Calendar.MONTH, 1) }.timeInMillis
            CalendarData.events(ctx, month.timeInMillis, end).flatMap { e ->
                val days = mutableListOf<Int>()
                val d = Calendar.getInstance().apply { timeInMillis = maxOf(e.begin, month.timeInMillis) }
                while (d.timeInMillis < minOf(e.end, end) && d.get(Calendar.MONTH) == month.get(Calendar.MONTH)) {
                    days += d.get(Calendar.DAY_OF_MONTH); d.add(Calendar.DAY_OF_MONTH, 1)
                    if (e.allDay && days.size > 31) break
                }
                if (days.isEmpty()) listOf(Calendar.getInstance().apply { timeInMillis = e.begin }.get(Calendar.DAY_OF_MONTH)) else days
            }.toSet()
        }
    }
    val firstDow = if (c.mondayFirst) Calendar.MONDAY else Calendar.SUNDAY
    val lead = (month.get(Calendar.DAY_OF_WEEK) - firstDow + 7) % 7
    val daysInMonth = month.getActualMaximum(Calendar.DAY_OF_MONTH)
    val weeks = (lead + daysInMonth + 6) / 7
    val names = remember(firstDow) {
        val f = SimpleDateFormat("EEE", Locale.getDefault())
        (0 until 7).map { i -> f.format(Calendar.getInstance().apply { set(Calendar.DAY_OF_WEEK, (firstDow - 1 + i) % 7 + 1) }.time) }
    }
    val isThisMonth = true
    val accentColor = accent

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(month.time), style = OnWallpaper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(DateFormat.getMediumDateFormat(ctx).format(Date(now)), style = OnWallpaper, fontSize = 14.sp, color = Dim)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            names.forEach { Text(it, style = OnWallpaper, fontSize = 11.sp, color = Dim, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
            for (w in 0 until weeks) {
                Row(Modifier.fillMaxWidth()) {
                    for (d in 0 until 7) {
                        val day = w * 7 + d - lead + 1
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            if (day in 1..daysInMonth) {
                                val isToday = isThisMonth && day == today.get(Calendar.DAY_OF_MONTH)
                                Box(
                                    Modifier.size(28.dp).clip(CircleShape)
                                        .then(if (isToday) Modifier.background(accentColor.copy(alpha = 0.35f)) else Modifier),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(day.toString(), style = OnWallpaper, fontSize = 13.sp, fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal)
                                    if (day in eventDays) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(4.dp).clip(CircleShape).background(accentColor))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// --- Agenda -----------------------------------------------------------------------------

@Composable
private fun AgendaWidget(c: BuiltInConfig) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val version = rememberCalendarVersion()
    val events by produceState<List<CalEvent>?>(null, now / 300_000, c.days, version) {
        value = withContext(Dispatchers.IO) { CalendarData.events(ctx, now, now + c.days * 86_400_000L) }
    }
    val timeFmt = remember { DateFormat.getTimeFormat(ctx) }
    val dayFmt = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Text("Upcoming", style = OnWallpaper, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        when {
            !CalendarData.hasPermission(ctx) -> Text("Allow calendar access to see your events (tap ⚙ in Edit widgets).", style = OnWallpaper, fontSize = 13.sp, color = Dim)
            events == null -> Unit
            events!!.isEmpty() -> Text("Nothing in the next ${c.days} days", style = OnWallpaper, fontSize = 14.sp, color = Dim)
            else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                var lastDay = ""
                events!!.take(12).forEach { e ->
                    val day = dayFmt.format(Date(e.begin))
                    if (day != lastDay) {
                        lastDay = day
                        Text(if (isToday(e.begin)) "Today" else day, style = OnWallpaper, fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold)
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { openEvent(ctx, e) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(Color(e.color or 0xFF000000.toInt())))
                        Spacer(Modifier.width(10.dp))
                        Text(if (e.allDay) "All day" else timeFmt.format(Date(e.begin)), style = OnWallpaper, fontSize = 13.sp, color = Dim, modifier = Modifier.width(64.dp))
                        Text(e.title, style = OnWallpaper, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun isToday(t: Long): Boolean {
    val a = Calendar.getInstance(); val b = Calendar.getInstance().apply { timeInMillis = t }
    return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
}

private fun openEvent(ctx: Context, e: CalEvent) {
    DuetApp.instance.apps.startOn(
        Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end),
        ScreenCoverage.tapTarget(),
    )
}

// --- Weather ----------------------------------------------------------------------------

@Composable
private fun WeatherWidget(c: BuiltInConfig) {
    val now = rememberNow()
    val weather by produceState(WeatherData.cached(c), c.lat, c.lon, c.fahrenheit, now / (30 * 60_000)) {
        withContext(Dispatchers.IO) { runCatching { WeatherData.load(c) }.getOrNull() }?.let { value = it }
    }
    if (!c.hasPlace) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
            Text("Set a location: Edit widgets → ⚙", style = OnWallpaper, fontSize = 14.sp, color = Dim)
        }
        return
    }
    val w = weather
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
            Text(c.place, style = OnWallpaper, fontSize = 15.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(w?.let { "${it.temp}°" } ?: "—", style = OnWallpaper, fontSize = 44.sp, fontWeight = FontWeight.Light)
            Text(
                w?.let { "${WeatherData.describe(it.code)} · H:${it.high}° L:${it.low}°" } ?: "Loading…",
                style = OnWallpaper, fontSize = 13.sp, color = Dim, maxLines = 1,
            )
        }
        if (w != null) WeatherGlyph(w.code, w.isDay, Modifier.size(64.dp))
    }
}

/** Simple drawn weather icons, so they match every style. */
@Composable
fun WeatherGlyph(code: Int, isDay: Boolean, modifier: Modifier) {
    val sun = Color(0xFFFFD54F)
    Canvas(modifier) {
        val s = size.minDimension
        fun cloud(dx: Float, dy: Float, scale: Float, color: Color) {
            val r = s * 0.16f * scale
            drawCircle(color, r * 1.25f, Offset(dx + s * 0.5f, dy + s * 0.5f))
            drawCircle(color, r, Offset(dx + s * 0.32f, dy + s * 0.58f))
            drawCircle(color, r, Offset(dx + s * 0.68f, dy + s * 0.58f))
            drawRoundRect(color, Offset(dx + s * 0.32f, dy + s * 0.5f), Size(s * 0.36f, r * 1.1f), CornerRadius(r / 2))
        }
        fun sunOrMoon(cx: Float, cy: Float, r: Float) {
            if (isDay) {
                drawCircle(sun, r, Offset(cx, cy))
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    drawLine(sun, Offset(cx + (r * 1.35f * Math.cos(a)).toFloat(), cy + (r * 1.35f * Math.sin(a)).toFloat()),
                        Offset(cx + (r * 1.75f * Math.cos(a)).toFloat(), cy + (r * 1.75f * Math.sin(a)).toFloat()), strokeWidth = s * 0.04f, cap = StrokeCap.Round)
                }
            } else {
                drawCircle(Color(0xFFE8EAF6), r, Offset(cx, cy))
                drawCircle(Color(0xFF1B1C20), r * 0.85f, Offset(cx + r * 0.45f, cy - r * 0.3f))
            }
        }
        val white = Color.White.copy(alpha = 0.92f)
        when (code) {
            0, 1 -> sunOrMoon(s / 2, s / 2, s * 0.2f)
            2 -> { sunOrMoon(s * 0.38f, s * 0.36f, s * 0.15f); cloud(s * 0.06f, s * 0.08f, 0.85f, white) }
            3, 45, 48 -> { cloud(0f, -s * 0.05f, 1f, white); if (code != 3) for (i in 0..1) drawLine(white, Offset(s * 0.2f, s * (0.8f + i * 0.1f)), Offset(s * 0.8f, s * (0.8f + i * 0.1f)), s * 0.04f, StrokeCap.Round) }
            71, 73, 75, 77, 85, 86 -> { cloud(0f, -s * 0.12f, 1f, white); for (i in 0..2) drawCircle(white, s * 0.04f, Offset(s * (0.32f + i * 0.18f), s * 0.82f)) }
            95, 96, 99 -> {
                cloud(0f, -s * 0.12f, 1f, white)
                drawPath(Path().apply { moveTo(s * 0.52f, s * 0.62f); lineTo(s * 0.42f, s * 0.8f); lineTo(s * 0.52f, s * 0.8f); lineTo(s * 0.45f, s * 0.97f); lineTo(s * 0.62f, s * 0.74f); lineTo(s * 0.52f, s * 0.74f); close() }, sun)
            }
            else -> { // drizzle, rain, showers
                cloud(0f, -s * 0.12f, 1f, white)
                val rain = Color(0xFF81D4FA)
                for (i in 0..2) drawLine(rain, Offset(s * (0.36f + i * 0.16f), s * 0.72f), Offset(s * (0.32f + i * 0.16f), s * 0.88f), s * 0.045f, StrokeCap.Round)
            }
        }
    }
}

// --- Music ------------------------------------------------------------------------------

/** The media session the music widget follows (playing first). Needs notification access. */
object MusicSessions {
    var current by mutableStateOf<MediaController?>(null)
}

@Composable
private fun MusicWidget() {
    val ctx = LocalContext.current
    val listener = remember { ComponentName(ctx, GlanceMirrorService::class.java) }
    var allowed by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val msm = ctx.getSystemService(MediaSessionManager::class.java)
        fun pick(list: List<MediaController>?) {
            MusicSessions.current = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        }
        val l = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
        try {
            pick(msm.getActiveSessions(listener))
            msm.addOnActiveSessionsChangedListener(l, listener)
        } catch (e: SecurityException) { allowed = false }
        onDispose { runCatching { msm.removeOnActiveSessionsChangedListener(l) } }
    }
    val controller = MusicSessions.current
    var meta by remember(controller) { mutableStateOf(controller?.metadata) }
    var state by remember(controller) { mutableStateOf(controller?.playbackState) }
    DisposableEffect(controller) {
        val cb = object : MediaController.Callback() {
            override fun onMetadataChanged(m: MediaMetadata?) { meta = m }
            override fun onPlaybackStateChanged(s: PlaybackState?) { state = s }
        }
        controller?.registerCallback(cb)
        onDispose { controller?.unregisterCallback(cb) }
    }
    if (!allowed) {
        Box(Modifier.fillMaxSize().clickable {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, contentAlignment = Alignment.CenterStart) {
            Text("Tap to allow notification access (\"Duet Launcher\") so this can show your music.", style = OnWallpaper, fontSize = 13.sp, color = Dim)
        }
        return
    }
    val art: Bitmap? = meta?.let { it.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) }
    val tint = remember(art) { art?.let { Color(Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0)) } }
    val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: meta?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
    val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    val playing = state?.state == PlaybackState.STATE_PLAYING
    val controls = controller?.transportControls

    Box(Modifier.fillMaxSize()) {
        // A wash of the album art's colour from the left, like the art is glowing through the glass.
        if (tint != null) Box(Modifier.matchParentSizeCompat().background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.45f), Color.Transparent))))
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                if (art != null) Image(art.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("♪", style = OnWallpaper, fontSize = 26.sp, color = Dim)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title ?: "Nothing playing", style = OnWallpaper, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (artist != null) Text(artist, style = OnWallpaper, fontSize = 13.sp, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val st = state
                if (duration > 0 && st != null) Scrubber(st, duration, controls, tint)
            }
            if (controls != null) {
                MediaButton(Glyph.PREV) { controls.skipToPrevious() }
                MediaButton(if (playing) Glyph.PAUSE else Glyph.PLAY, big = true) { if (playing) controls.pause() else controls.play() }
                MediaButton(Glyph.NEXT) { controls.skipToNext() }
            }
        }
    }
}

/**
 * Song position: follows playback between updates, and drag or tap it to seek (when the
 * player allows seeking).
 */
@Composable
private fun Scrubber(st: PlaybackState, duration: Long, controls: MediaController.TransportControls?, tint: Color?) {
    val playing = st.state == PlaybackState.STATE_PLAYING
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(playing, st) {
        now = SystemClock.elapsedRealtime()
        while (playing) { delay(250); now = SystemClock.elapsedRealtime() }
    }
    val live = if (playing && st.lastPositionUpdateTime > 0)
        st.position + ((now - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else st.position
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    // After a seek, show the new spot until the player reports it (it can take a moment).
    var seekedTo by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val fresh = seekedTo?.takeIf { SystemClock.elapsedRealtime() - it.second < 1500 }
    val frac = dragFrac ?: fresh?.first ?: (live.toFloat() / duration).coerceIn(0f, 1f)
    val canSeek = controls != null && st.actions and PlaybackState.ACTION_SEEK_TO != 0L
    val fill = (tint?.let { lerp(it, Color.White, 0.55f) } ?: Color.White)
    val scope = rememberCoroutineScope()

    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(clockTime((frac * duration).toLong()), style = OnWallpaper, fontSize = 11.sp, color = Dim)
        Box(
            Modifier.weight(1f).height(22.dp).padding(horizontal = 8.dp)
                .then(if (!canSeek) Modifier else Modifier.pointerInput(duration) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragFrac = (down.position.x / size.width).coerceIn(0f, 1f)
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            dragFrac = (c.position.x / size.width).coerceIn(0f, 1f)
                        }
                        dragFrac?.let { f ->
                            controls?.seekTo((f * duration).toLong())
                            seekedTo = f to SystemClock.elapsedRealtime()
                            scope.launch { delay(1600); seekedTo = null }
                        }
                        dragFrac = null
                    }
                }),
            contentAlignment = Alignment.Center,
        ) {
            val grabbing = dragFrac != null
            Canvas(Modifier.fillMaxWidth().height(22.dp)) {
                val y = size.height / 2
                val h = if (grabbing) 6.dp.toPx() else 4.dp.toPx()
                val x = size.width * frac
                drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(fill, Offset(0f, y - h / 2), Size(x, h), CornerRadius(h / 2))
                if (canSeek) drawCircle(Color.White, if (grabbing) 7.dp.toPx() else 5.dp.toPx(), Offset(x, y))
            }
        }
        Text(clockTime(duration), style = OnWallpaper, fontSize = 11.sp, color = Dim)
    }
}

private fun clockTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = t / 60 % 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

private fun Modifier.matchParentSizeCompat() = this.fillMaxSize()

private enum class Glyph { PREV, PLAY, PAUSE, NEXT }

@Composable
private fun MediaButton(g: Glyph, big: Boolean = false, onClick: () -> Unit) {
    val d = if (big) 46.dp else 38.dp
    Box(
        Modifier.padding(horizontal = 3.dp).size(d).clip(CircleShape).glass(CircleShape, strength = 0.7f).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(d * 0.38f)) { drawMediaGlyph(g, Color.White) }
    }
}

private fun DrawScope.drawMediaGlyph(g: Glyph, c: Color) {
    val w = size.width; val h = size.height
    fun tri(x0: Float, x1: Float) = Path().apply { moveTo(x0, 0f); lineTo(x1, h / 2); lineTo(x0, h); close() }
    when (g) {
        Glyph.PLAY -> drawPath(tri(w * 0.12f, w), c)
        Glyph.PAUSE -> { drawRoundRect(c, Offset(w * 0.1f, 0f), Size(w * 0.28f, h), CornerRadius(2f)); drawRoundRect(c, Offset(w * 0.62f, 0f), Size(w * 0.28f, h), CornerRadius(2f)) }
        Glyph.NEXT -> { drawPath(tri(0f, w * 0.78f), c); drawRect(c, Offset(w * 0.8f, 0f), Size(w * 0.18f, h)) }
        Glyph.PREV -> { drawPath(Path().apply { moveTo(w, 0f); lineTo(w * 0.22f, h / 2); lineTo(w, h); close() }, c); drawRect(c, Offset(0f, 0f), Size(w * 0.18f, h)) }
    }
}

// --- Battery ----------------------------------------------------------------------------

@Composable
private fun BatteryWidget() {
    val ctx = LocalContext.current
    var level by remember { mutableStateOf(-1) }
    var status by remember { mutableStateOf(BatteryManager.BATTERY_STATUS_UNKNOWN) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0) level = l * 100 / s
                status = i.getIntExtra(BatteryManager.EXTRA_STATUS, status)
            }
        }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { r.onReceive(ctx, it) }
        onDispose { ctx.unregisterReceiver(r) }
    }
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    val color = when {
        charging -> Color(0xFF8BE0A4)
        level in 0..15 -> Color(0xFFFF8A80)
        else -> accent
    }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.fillMaxHeight().aspectRatio(1f)) {
            val stroke = size.minDimension * 0.1f
            val inset = stroke / 2
            drawArc(Color.White.copy(alpha = 0.15f), 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
            drawArc(color, -90f, 360f * level.coerceAtLeast(0) / 100f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(if (level >= 0) "$level%" else "—", style = OnWallpaper, fontSize = 30.sp, fontWeight = FontWeight.Light)
            Text(
                when (status) {
                    BatteryManager.BATTERY_STATUS_FULL -> "Fully charged"
                    BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                    else -> "On battery"
                },
                style = OnWallpaper, fontSize = 13.sp, color = Dim,
            )
        }
    }
}

// --- Next alarm -------------------------------------------------------------------------

@Composable
private fun AlarmWidget() {
    val ctx = LocalContext.current
    val now = rememberNow()
    var version by remember { mutableStateOf(0) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { version++ } }
        ctx.registerReceiver(r, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED))
        onDispose { ctx.unregisterReceiver(r) }
    }
    val next = remember(version, now) { ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.triggerTime }
    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(34.dp)) {
            val c = Color.White.copy(alpha = 0.9f); val s = size.minDimension
            drawCircle(c, s * 0.38f, Offset(s / 2, s * 0.55f), style = Stroke(s * 0.08f))
            drawLine(c, Offset(s / 2, s * 0.55f), Offset(s / 2, s * 0.35f), s * 0.08f, StrokeCap.Round)
            drawLine(c, Offset(s / 2, s * 0.55f), Offset(s * 0.65f, s * 0.62f), s * 0.08f, StrokeCap.Round)
            drawLine(c, Offset(s * 0.15f, s * 0.2f), Offset(s * 0.3f, s * 0.08f), s * 0.08f, StrokeCap.Round)
            drawLine(c, Offset(s * 0.85f, s * 0.2f), Offset(s * 0.7f, s * 0.08f), s * 0.08f, StrokeCap.Round)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            if (next == null) {
                Text("No alarms set", style = OnWallpaper, fontSize = 17.sp)
            } else {
                Text(DateFormat.getTimeFormat(ctx).format(Date(next)), style = OnWallpaper, fontSize = 26.sp, fontWeight = FontWeight.Light)
                val mins = (next - now) / 60_000
                Text(
                    (if (isToday(next)) "Today" else SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(next))) +
                        " · in " + (if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"),
                    style = OnWallpaper, fontSize = 13.sp, color = Dim,
                )
            }
        }
    }
}
