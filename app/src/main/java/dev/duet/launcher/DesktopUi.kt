package dev.duet.launcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

private val BarColor = Color(0xE6161A20)
private val MenuColor = Color(0xF01B2028)

/** Taskbar along the bottom of the external display, plus the start menu above it when open. */
@Composable
fun DesktopShell(onMenuChange: () -> Unit) {
    val open = DesktopMode.menuOpen
    LaunchedEffect(open) { onMenuChange() }
    Box(Modifier.fillMaxSize()) {
        if (open) {
            // Click outside the menu to close it.
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { DesktopMode.toggleMenu() })
            StartMenu(Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = (DesktopMode.TASKBAR_DP + 8).dp))
        }
        Taskbar(Modifier.align(Alignment.BottomCenter))
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
        modifier.fillMaxWidth().height(DesktopMode.TASKBAR_DP.dp).background(BarColor).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        BarButton(selected = DesktopMode.menuOpen, onClick = { DesktopMode.toggleMenu() }) {
            Text("⊞", fontSize = 24.sp, color = MaterialTheme.colorScheme.primary)
        }
        pinned.forEach { a ->
            BarButton(running = a.component.packageName in running, onClick = { DesktopMode.launch(a) }) { AppIcon(a, 30) }
        }
        if (others.isNotEmpty() && pinned.isNotEmpty()) {
            Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = 0.2f)))
        }
        others.forEach { a -> BarButton(running = true, onClick = { DesktopMode.launch(a) }) { AppIcon(a, 30) } }
        Spacer(Modifier.weight(1f))
        BarButton(onClick = { DesktopMode.showDesktop() }) { Text("▭", fontSize = 20.sp, color = Color.White.copy(alpha = 0.8f)) }
        BarClock()
    }
}

@Composable
private fun BarButton(selected: Boolean = false, running: Boolean = false, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        content()
        if (running) Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(width = 14.dp, height = 3.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.primary)
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
            items(results, key = { it.key }) { a -> GridApp(a) { DesktopMode.launch(a) } }
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

@Composable
private fun GridApp(a: AppInfo, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
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
        setContent { DuetTheme { DesktopScreen() } }
    }
}

@Composable
private fun DesktopScreen() {
    val prefs = DuetApp.instance.prefs
    val byKey = DuetApp.instance.apps.all.associateBy { it.key }
    val favs = prefs.favorites.mapNotNull { byKey[it] }
    val accent = MaterialTheme.colorScheme.primary
    Box(
        Modifier.fillMaxSize()
            // Android doesn't draw the wallpaper on external displays, so paint a backdrop in the accent colour.
            .background(Brush.linearGradient(listOf(Color(0xFF0B0E14), accent.copy(alpha = 0.35f).compositeOver(Color(0xFF141A24)), Color(0xFF07090D))))
            .clickable(remember { MutableInteractionSource() }, null) { DesktopMode.menuOpen = false }
            .padding(bottom = DesktopMode.TASKBAR_DP.dp)
    ) {
        // Shortcuts fill columns top to bottom, then wrap to the next column, like a desktop.
        LazyHorizontalGrid(GridCells.Adaptive(92.dp), Modifier.fillMaxSize().padding(12.dp)) {
            items(favs, key = { it.key }) { a ->
                Column(
                    Modifier.width(96.dp).clip(RoundedCornerShape(10.dp)).clickable { DesktopMode.launch(a) }.padding(8.dp),
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
