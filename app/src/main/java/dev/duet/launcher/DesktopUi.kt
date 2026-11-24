package dev.duet.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val BarColor = Color(0xFF161A20)
private val MenuColor = Color(0xF01B2028)

/** Taskbar along the bottom of the external display, plus the start menu and right-click menus when open. */
@Composable
fun DesktopShell(onLayoutChange: () -> Unit) {
    val open = DesktopMode.menuOpen
    val menu = DesktopMode.contextMenu
    val hidden = DesktopMode.taskbarHidden
    LaunchedEffect(open, menu, hidden) { onLayoutChange() }
    Box(Modifier.fillMaxSize()) {
        if (open || menu != null) {
            // Click outside a menu to close it.
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) {
                DesktopMode.menuOpen = false; DesktopMode.contextMenu = null
            })
        }
        if (open) StartMenu(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = taskbarDp() + 8.dp))
        if (!hidden) Taskbar(Modifier.align(Alignment.BottomCenter))
        menu?.let { RightClickMenu(it) }
    }
}

@Composable
private fun taskbarDp() = with(LocalDensity.current) { DesktopMode.taskbarPx().toDp() }

/** Opens the right-click menu for [pkg] (or the desktop, when null) at the pointer. */
fun showContextMenu(pkg: String?) {
    DesktopMode.menuOpen = false
    DesktopMode.contextMenu = ContextMenu(pkg, DesktopMode.pointerX, DesktopMode.pointerY)
}

@Composable
private fun RightClickMenu(m: ContextMenu) {
    val apps = DuetApp.instance.apps.all
    val prefs = DuetApp.instance.prefs
    // Placed at the pointer, pushed back inside the screen near the edges (so it opens upward from the taskbar).
    Column(
        Modifier
            .layout { measurable, constraints ->
                val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                layout(constraints.maxWidth, constraints.maxHeight) {
                    p.place(
                        m.x.toInt().coerceIn(0, (constraints.maxWidth - p.width).coerceAtLeast(0)),
                        m.y.toInt().coerceIn(0, (constraints.maxHeight - p.height).coerceAtLeast(0)),
                    )
                }
            }
            .width(230.dp).clip(RoundedCornerShape(12.dp)).background(MenuColor)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(12.dp))
            .padding(vertical = 6.dp)
    ) {
        if (m.pkg == null) {
            MenuItem("Start") { DesktopMode.contextMenu = null; DesktopMode.menuOpen = true }
            MenuItem("Show desktop") { DesktopMode.contextMenu = null; DesktopMode.showDesktop() }
            MenuItem("Open touchpad on phone") { DesktopMode.contextMenu = null; DesktopMode.openTouchpad() }
            MenuItem("Duet settings") { DesktopMode.contextMenu = null; openSettingsOnDesktop() }
            return@Column
        }
        val pkg = m.pkg
        val a = apps.firstOrNull { it.component.packageName == pkg }
        val window = DesktopMode.windows.firstOrNull { it.pkg == pkg }
        Text(
            a?.label ?: pkg, fontSize = 12.sp, color = Color.White.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
        a?.let { MenuItem(if (window != null || pkg in DesktopMode.running) "Bring to front" else "Open") { DesktopMode.launch(it) } }
        if (window != null) {
            val snap = DesktopMode.snapOf(window)
            MenuDivider()
            MenuItem("Snap left", WinGlyph.LEFT) { DesktopMode.snap(pkg, DesktopMode.Snap.LEFT) }
            MenuItem("Snap right", WinGlyph.RIGHT) { DesktopMode.snap(pkg, DesktopMode.Snap.RIGHT) }
            if (snap == DesktopMode.Snap.MAXIMIZE) MenuItem("Restore", WinGlyph.RESTORE) { DesktopMode.snap(pkg, DesktopMode.Snap.RESTORE) }
            else MenuItem("Maximize", WinGlyph.MAXIMIZE) { DesktopMode.snap(pkg, DesktopMode.Snap.MAXIMIZE) }
            if (snap == DesktopMode.Snap.FULLSCREEN) MenuItem("Exit full screen", WinGlyph.EXIT_FULLSCREEN) { DesktopMode.snap(pkg, DesktopMode.Snap.RESTORE) }
            else MenuItem("Full screen", WinGlyph.FULLSCREEN) { DesktopMode.snap(pkg, DesktopMode.Snap.FULLSCREEN) }
            if (snap != null && snap != DesktopMode.Snap.MAXIMIZE && snap != DesktopMode.Snap.FULLSCREEN)
                MenuItem("Restore", WinGlyph.RESTORE) { DesktopMode.snap(pkg, DesktopMode.Snap.RESTORE) }
            MenuItem("Close window") { DesktopMode.close(pkg) }
        }
        MenuDivider()
        if (a != null) {
            val pinned = a.key in prefs.favorites
            MenuItem(if (pinned) "Unpin from taskbar" else "Pin to taskbar") { DesktopMode.contextMenu = null; prefs.toggleFavorite(a.key) }
        }
        MenuItem("App info") { DesktopMode.appInfo(pkg) }
    }
}

@Composable
private fun MenuItem(label: String, glyph: WinGlyph? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp)) { glyph?.let { WinIcon(it, Color.White.copy(alpha = 0.8f)) } }
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, color = Color.White)
    }
}

@Composable
private fun MenuDivider() {
    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
}

enum class WinGlyph { MOVE, LEFT, RIGHT, MAXIMIZE, RESTORE, FULLSCREEN, EXIT_FULLSCREEN }

/** Small line icons for the window controls, drawn so they look the same with any font. */
@Composable
fun WinIcon(g: WinGlyph, color: Color, modifier: Modifier = Modifier.size(18.dp)) {
    Canvas(modifier) {
        val s = size.minDimension
        val w = s * 0.09f
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val o = Offset(s * 0.12f, s * 0.2f)
        val box = Size(s * 0.76f, s * 0.6f)
        when (g) {
            WinGlyph.MOVE -> for (r in 0..2) for (c in 0..1) drawCircle(color, s * 0.07f, Offset(s * (0.38f + c * 0.24f), s * (0.26f + r * 0.24f)))
            WinGlyph.LEFT, WinGlyph.RIGHT -> {
                drawRoundRect(color, o, box, CornerRadius(s * 0.08f), style = st)
                val x = if (g == WinGlyph.LEFT) o.x else o.x + box.width / 2
                drawRect(color, Offset(x, o.y), Size(box.width / 2, box.height))
            }
            WinGlyph.MAXIMIZE -> {
                drawRoundRect(color, o, box, CornerRadius(s * 0.08f), style = st)
                drawRect(color, o, Size(box.width, s * 0.14f))
            }
            WinGlyph.RESTORE -> {
                drawRoundRect(color, Offset(s * 0.3f, s * 0.14f), Size(s * 0.56f, s * 0.46f), CornerRadius(s * 0.06f), style = st)
                drawRoundRect(color, Offset(s * 0.14f, s * 0.36f), Size(s * 0.56f, s * 0.46f), CornerRadius(s * 0.06f), style = st)
            }
            WinGlyph.FULLSCREEN, WinGlyph.EXIT_FULLSCREEN -> {
                val a = s * 0.16f; val b = s * 0.84f; val l = s * 0.24f
                val inward = g == WinGlyph.EXIT_FULLSCREEN
                // Four corner brackets, pointing out (enter) or in (exit).
                listOf(Offset(a, a) to Offset(1f, 1f), Offset(b, a) to Offset(-1f, 1f), Offset(a, b) to Offset(1f, -1f), Offset(b, b) to Offset(-1f, -1f))
                    .forEach { (p, d) ->
                        val corner = if (inward) Offset(p.x + d.x * l, p.y + d.y * l) else p
                        val dx = if (inward) -d.x else d.x; val dy = if (inward) -d.y else d.y
                        drawLine(color, corner, Offset(corner.x + dx * l, corner.y), w, StrokeCap.Round)
                        drawLine(color, corner, Offset(corner.x, corner.y + dy * l), w, StrokeCap.Round)
                    }
            }
        }
    }
}

/**
 * Controls on the active window's top edge: drag the dots to move it, then snap left/right,
 * maximize/restore and full screen.
 */
@Composable
fun WindowControls(onLayoutChange: () -> Unit) {
    val a = DesktopMode.activeWindow
    LaunchedEffect(a, DesktopMode.menuOpen, DesktopMode.contextMenu, DesktopMode.fullscreenPkg) { onLayoutChange() }
    if (a == null) return
    val snap = DesktopMode.snapOf(a)
    val tint = Color.White.copy(alpha = 0.9f)
    Row(
        Modifier.fillMaxSize().clip(RoundedCornerShape(17.dp)).background(BarColor)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(17.dp)).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        Box(
            Modifier.size(width = 38.dp, height = 30.dp).clip(RoundedCornerShape(8.dp))
                .pointerInput(a.pkg) {
                    detectDragGestures(
                        onDragStart = { DesktopMode.activeWindow?.let { DesktopMode.dragOutline = Rect(it.bounds) }; DesktopMode.relayoutOverlays() },
                        onDrag = { change, d ->
                            change.consume()
                            DesktopMode.dragOutline = DesktopMode.dragOutline?.let { Rect(it).apply { offset(d.x.toInt(), d.y.toInt()) } }
                            DesktopMode.relayoutOverlays()
                        },
                        onDragEnd = { DesktopMode.finishDrag(a.pkg); DesktopMode.relayoutOverlays() },
                        onDragCancel = { DesktopMode.dragOutline = null; DesktopMode.relayoutOverlays() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) { WinIcon(WinGlyph.MOVE, tint.copy(alpha = 0.6f)) }
        CtlButton(WinGlyph.LEFT, tint) { DesktopMode.snap(a.pkg, DesktopMode.Snap.LEFT) }
        CtlButton(WinGlyph.RIGHT, tint) { DesktopMode.snap(a.pkg, DesktopMode.Snap.RIGHT) }
        if (snap == DesktopMode.Snap.MAXIMIZE) CtlButton(WinGlyph.RESTORE, tint) { DesktopMode.snap(a.pkg, DesktopMode.Snap.RESTORE) }
        else CtlButton(WinGlyph.MAXIMIZE, tint) { DesktopMode.snap(a.pkg, DesktopMode.Snap.MAXIMIZE) }
        if (snap == DesktopMode.Snap.FULLSCREEN) CtlButton(WinGlyph.EXIT_FULLSCREEN, tint) { DesktopMode.snap(a.pkg, DesktopMode.Snap.RESTORE) }
        else CtlButton(WinGlyph.FULLSCREEN, tint) { DesktopMode.snap(a.pkg, DesktopMode.Snap.FULLSCREEN) }
    }
}

@Composable
private fun CtlButton(g: WinGlyph, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(width = 38.dp, height = 30.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { WinIcon(g, tint) }
}

/** Bottom-right corner of the active window: drag to resize it. */
@Composable
fun ResizeGrip() {
    val tint = Color.White.copy(alpha = 0.75f)
    Canvas(
        Modifier.fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        DesktopMode.gripDragging = true
                        DesktopMode.activeWindow?.let { DesktopMode.dragOutline = Rect(it.bounds) }
                        DesktopMode.relayoutOverlays()
                    },
                    onDrag = { change, d ->
                        change.consume()
                        DesktopMode.dragOutline = DesktopMode.dragOutline?.let {
                            Rect(it).apply { right += d.x.toInt(); bottom += d.y.toInt() }
                        }
                        DesktopMode.relayoutOverlays()
                    },
                    onDragEnd = {
                        DesktopMode.gripDragging = false
                        DesktopMode.activeWindow?.let { DesktopMode.finishDrag(it.pkg) } ?: run { DesktopMode.dragOutline = null }
                        DesktopMode.relayoutOverlays()
                    },
                    onDragCancel = { DesktopMode.gripDragging = false; DesktopMode.dragOutline = null; DesktopMode.relayoutOverlays() },
                )
            }
    ) {
        // Three diagonal lines in the corner, the usual "drag to resize" mark.
        val s = size.minDimension
        drawCircle(Color(0xCC161A20), s * 0.5f)
        for (i in 1..3) {
            val k = s * (0.2f + i * 0.16f)
            drawLine(tint, Offset(k, s * 0.82f), Offset(s * 0.82f, k), s * 0.07f, StrokeCap.Round)
        }
    }
}

@Composable
private fun Taskbar(modifier: Modifier) {
    val prefs = DuetApp.instance.prefs
    val apps = DuetApp.instance.apps.all
    val byKey = remember(apps) { apps.associateBy { it.key } }
    val pinned = prefs.favorites.mapNotNull { byKey[it] }
    val running = DesktopMode.running
    val others = running.filterNot { pkg -> pinned.any { it.component.packageName == pkg } }
        .mapNotNull { pkg -> apps.firstOrNull { it.component.packageName == pkg } }

    Row(
        modifier.fillMaxWidth().height(taskbarDp()).background(BarColor).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarButton(selected = DesktopMode.menuOpen, onClick = { DesktopMode.toggleMenu() }) {
            Text("⊞", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        }
        pinned.forEach { a ->
            BarButton(
                running = a.component.packageName in running, active = DesktopMode.activeWindow?.pkg == a.component.packageName,
                onClick = { DesktopMode.launch(a) }, onRightClick = { showContextMenu(a.component.packageName) },
            ) { AppIcon(a, 30) }
        }
        if (others.isNotEmpty() && pinned.isNotEmpty()) {
            Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.2f)))
        }
        others.forEach { a ->
            BarButton(
                running = true, active = DesktopMode.activeWindow?.pkg == a.component.packageName,
                onClick = { DesktopMode.launch(a) }, onRightClick = { showContextMenu(a.component.packageName) },
            ) { AppIcon(a, 30) }
        }
        Spacer(Modifier.weight(1f))
        BarButton(onClick = { DesktopMode.showDesktop() }) { Text("▭", fontSize = 20.sp, color = Color.White.copy(alpha = 0.8f)) }
        BarClock()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BarButton(
    selected: Boolean = false, running: Boolean = false, active: Boolean = false,
    onClick: () -> Unit, onRightClick: (() -> Unit)? = null, content: @Composable () -> Unit,
) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected || active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onRightClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (running) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = if (active) 20.dp else 10.dp, height = 3.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = if (active) 1f else 0.6f))
        )
    }
}

@Composable
private fun BarClock() {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1000L - now % 1000L) } }
    Column(Modifier.padding(horizontal = 10.dp), horizontalAlignment = Alignment.End) {
        Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now)), fontSize = 14.sp, color = Color.White)
        Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(now)), fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f))
    }
}

@Composable
private fun StartMenu(modifier: Modifier) {
    val results = DesktopMode.searchResults()
    Column(
        modifier.width(520.dp).height(560.dp).clip(RoundedCornerShape(16.dp)).background(MenuColor)
            .clickable(remember { MutableInteractionSource() }, null) { } // don't close when clicking inside
            .padding(16.dp)
    ) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            val q = DesktopMode.query
            Text(
                if (q.isEmpty()) "Type on the touchpad keyboard to search" else q,
                color = Color.White.copy(alpha = if (q.isEmpty()) 0.5f else 1f), fontSize = 15.sp,
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(GridCells.Fixed(5), Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(results, key = { it.key }) { a -> GridApp(a, onRightClick = { showContextMenu(a.component.packageName) }) { DesktopMode.launch(a) } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "Duet settings", fontSize = 13.sp, color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { openSettingsOnDesktop() }.padding(8.dp),
            )
        }
    }
}

private fun openSettingsOnDesktop() {
    val app = DuetApp.instance
    DesktopMode.menuOpen = false
    runCatching {
        app.startActivity(
            android.content.Intent(app, SettingsActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            DesktopMode.options(),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GridApp(a: AppInfo, onRightClick: () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).combinedClickable(onClick = onClick, onLongClick = onRightClick).padding(vertical = 10.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppIcon(a, 44)
        Spacer(Modifier.height(6.dp))
        Text(a.label, fontSize = 12.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** Invisible: opening it moves input focus to the external display; it closes at once. See [DesktopMode.back]. */
class FocusActivity : android.app.Activity() {
    override fun onResume() {
        super.onResume()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        DesktopMode.pressBack()
    }
}

/**
 * The desktop itself on the external display: wallpaper and your favorites as shortcuts. It's
 * also Duet's "secondary home", which Android shows on external displays in its own desktop mode.
 */
class DesktopActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Clicking a focusable window pulls it in front of the others; the desktop must stay behind
        // the apps (only "show desktop" brings it forward), and it never needs the keyboard.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        setContent { DuetTheme { DesktopScreen() } }
        // After setContent: a floating window is reset to wrap its content when the content is set.
        window.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT, android.view.WindowManager.LayoutParams.MATCH_PARENT)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopScreen() {
    val prefs = DuetApp.instance.prefs
    val byKey = DuetApp.instance.apps.all.associateBy { it.key }
    val favs = prefs.favorites.mapNotNull { byKey[it] }
    Box(
        Modifier.fillMaxSize()
            // Black: plain, and on display glasses black is see-through.
            .background(Color.Black)
            .combinedClickable(
                remember { MutableInteractionSource() }, null,
                onClick = { DesktopMode.menuOpen = false; DesktopMode.contextMenu = null },
                onLongClick = { showContextMenu(null) },
            )
            .padding(bottom = taskbarDp())
    ) {
        // Shortcuts fill columns top to bottom, then wrap to the next column, like a desktop.
        LazyHorizontalGrid(GridCells.Adaptive(92.dp), Modifier.fillMaxSize().padding(12.dp)) {
            items(favs, key = { it.key }) { a ->
                Column(
                    Modifier.width(96.dp).clip(RoundedCornerShape(10.dp))
                        .combinedClickable(onClick = { DesktopMode.launch(a) }, onLongClick = { showContextMenu(a.component.packageName) })
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppIcon(a, 48)
                    Text(a.label, fontSize = 12.sp, color = Color.White, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (!DesktopMode.canControl) Text(
            "Turn on \"Duet screen detection\" in Accessibility settings on your phone to get the taskbar and touchpad pointer.",
            color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(20.dp),
        )
    }
}
