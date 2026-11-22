@file:OptIn(ExperimentalFoundationApi::class)

package dev.duet.launcher

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.os.Bundle
import android.os.UserManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The stack of hosted widgets, with move / resize / remove controls in edit mode. */
@Composable
fun WidgetColumn(activity: MainActivity, modifier: Modifier = Modifier) {
    val prefs = DuetApp.instance.prefs
    val ui = activity.ui
    val widgets = prefs.widgets
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        widgets.forEachIndexed { index, entry ->
            key(entry.id) {
                Box(Modifier.fillMaxWidth().height(entry.heightDp.dp)) {
                    HostedWidget(activity, entry.id, Modifier.fillMaxSize())
                    if (ui.editWidgets) {
                        WidgetEditOverlay(
                            activity, entry,
                            canMoveUp = index > 0,
                            canMoveDown = index < widgets.lastIndex,
                        )
                    }
                }
            }
        }
        if (ui.editWidgets) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = { ui.pickerOpen = true }) { Text("Add widget") }
                OutlinedButton(onClick = { ui.editWidgets = false }) { Text("Done", color = Color.White) }
            }
        }
    }
}

@Composable
private fun HostedWidget(activity: MainActivity, id: Int, modifier: Modifier) {
    val info = remember(id) { activity.widgetManager.getAppWidgetInfo(id) }
    if (info == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Widget unavailable", style = OnWallpaper)
        }
        return
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    LaunchedEffect(size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        val w = with(density) { size.width.toDp().value.toInt() }
        val h = with(density) { size.height.toDp().value.toInt() }
        if (activity.sentWidgetSizes[id] == (w to h)) return@LaunchedEffect
        activity.sentWidgetSizes[id] = w to h
        activity.widgetManager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
        })
    }
    AndroidView(
        factory = { _ -> activity.widgetView(id, info) },
        modifier = modifier.onSizeChanged { size = it },
    )
}

@Composable
private fun WidgetEditOverlay(activity: MainActivity, entry: WidgetEntry, canMoveUp: Boolean, canMoveDown: Boolean) {
    Box(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            // swallow touches so the widget doesn't react while editing
            .clickable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${entry.heightDp} dp tall", style = OnWallpaper, fontSize = 13.sp)
            Row {
                EditButton("▲", enabled = canMoveUp) { activity.moveWidget(entry.id, -1) }
                EditButton("▼", enabled = canMoveDown) { activity.moveWidget(entry.id, 1) }
                EditButton("−") { activity.resizeWidget(entry.id, -24) }
                EditButton("+") { activity.resizeWidget(entry.id, 24) }
                EditButton("✕") { activity.removeWidget(entry.id) }
            }
        }
    }
}

@Composable
private fun EditButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Text(label, fontSize = 20.sp, color = if (enabled) Color.White else Color.White.copy(alpha = 0.3f))
    }
}

/** Our own widget picker (the system one was removed from modern Android). */
@Composable
fun WidgetPicker(activity: MainActivity, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.packageManager
    val groups = remember {
        val um = ctx.getSystemService(UserManager::class.java)
        um.userProfiles
            .flatMap { activity.widgetManager.getInstalledProvidersForProfile(it) }
            .groupBy { it.provider.packageName }
            .map { (pkg, list) ->
                val appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                    .getOrDefault(pkg)
                appLabel to list.sortedBy { it.loadLabel(pm) }
            }
            .sortedBy { it.first.lowercase() }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.92f).fillMaxSize(0.88f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Add a widget", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    groups.forEach { (appLabel, providers) ->
                        item(key = "h:$appLabel") {
                            Text(
                                appLabel,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                            )
                        }
                        items(providers, key = { it.provider.flattenToString() + it.profile.hashCode() }) { info ->
                            WidgetChoice(info) {
                                onDismiss()
                                activity.addWidget(info)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WidgetChoice(info: AppWidgetProviderInfo, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val density = ctx.resources.displayMetrics.density
    val icon by produceState<ImageBitmap?>(null, info) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                info.loadIcon(ctx, ctx.resources.displayMetrics.densityDpi)?.toBitmap(96, 96)?.asImageBitmap()
            }.getOrNull()
        }
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(info.loadLabel(ctx.packageManager))
            Text(
                "${(info.minWidth / density).toInt()} × ${(info.minHeight / density).toInt()} dp",
                fontSize = 12.sp,
                color = Color.White.copy(alpha = 0.6f),
            )
        }
    }
}
