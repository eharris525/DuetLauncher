package dev.duet.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Settings for one built-in widget: tap action, panel, and the widget's own options. */
@Composable
fun BuiltInWidgetSettings(activity: MainActivity, id: Int, onDismiss: () -> Unit) {
    val prefs = DuetApp.instance.prefs
    val c = prefs.builtin(id) ?: return onDismiss()
    fun update(f: (BuiltInConfig) -> BuiltInConfig) = prefs.updateBuiltin(f(c))
    val ctx = LocalContext.current
    var pickApp by remember { mutableStateOf(false) }
    val defaultLabel = when (c.kind) {
        BuiltInKind.CALENDAR, BuiltInKind.AGENDA -> "Open your calendar"
        BuiltInKind.WEATHER -> "Open a weather forecast"
        BuiltInKind.MUSIC -> "Open the music app"
        BuiltInKind.BATTERY -> "Open battery usage"
        BuiltInKind.ALARM -> "Open your alarms"
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 500.dp).fillMaxWidth(0.92f).fillMaxSize(0.88f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(Modifier.padding(20.dp)) {
                item {
                    Text(c.kind.title, style = MaterialTheme.typography.headlineSmall)
                    Text("Colours, panel style and font follow Settings → Style and Font.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                }

                // Widget-specific options first: that's usually why you're here.
                when (c.kind) {
                    BuiltInKind.WEATHER -> item { WeatherOptions(c) { prefs.updateBuiltin(it) } }
                    BuiltInKind.CALENDAR, BuiltInKind.AGENDA -> item {
                        Heading("Calendar")
                        if (!CalendarData.hasPermission(ctx)) {
                            FilledTonalButton(onClick = { activity.requestCalendarAccess() }) { Text("Allow calendar access") }
                            Text("Needed to show your events.", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                        }
                        if (c.kind == BuiltInKind.CALENDAR) {
                            Toggle("Mark days with events", c.showEvents) { v -> update { it.copy(showEvents = v) } }
                            Toggle("Week starts on Monday", c.mondayFirst) { v -> update { it.copy(mondayFirst = v) } }
                        } else {
                            Text("Show the next", modifier = Modifier.padding(top = 8.dp))
                            Row {
                                listOf(1, 3, 7, 14).forEach { d ->
                                    Choice("$d day${if (d > 1) "s" else ""}", c.days == d) { update { it.copy(days = d) } }
                                }
                            }
                        }
                    }
                    else -> Unit
                }

                item {
                    Heading("Look")
                    Toggle("Show panel", c.panel, "Off: just the content on your wallpaper") { v -> update { it.copy(panel = v) } }
                    Heading("When tapped")
                    Choice(defaultLabel, c.tap == TAP_DEFAULT) { update { it.copy(tap = TAP_DEFAULT) } }
                }
                items(DuetAction.entries) { a ->
                    val label = if (a == DuetAction.LAUNCH_APP && c.tap == a.name) a.describe(c.tapApp) else a.title
                    Choice(label, c.tap == a.name) {
                        update { it.copy(tap = a.name) }
                        if (a == DuetAction.LAUNCH_APP) pickApp = true
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = onDismiss) { Text("Done") }
                }
            }
        }
    }

    if (pickApp) {
        Dialog(onDismissRequest = { pickApp = false }) {
            Surface(Modifier.widthIn(max = 480.dp).fillMaxWidth().fillMaxSize(0.8f), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
                LazyColumn(Modifier.padding(16.dp)) {
                    items(DuetApp.instance.apps.all, key = { it.key }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { update { it.copy(tapApp = app.key) }; pickApp = false }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app, 28); Spacer(Modifier.width(12.dp)); Text(app.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherOptions(c: BuiltInConfig, save: (BuiltInConfig) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350) // wait for typing to pause
        val r = withContext(Dispatchers.IO) { runCatching { WeatherData.search(query) } }
        results = r.getOrDefault(emptyList()); error = if (r.isFailure) "Couldn't search — check your connection." else null
    }
    Column {
        Heading("Location")
        Text(if (c.hasPlace) c.place else "Not set", fontWeight = FontWeight.Medium)
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Search for a city") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Text("Weather comes from Open-Meteo; only the city's coordinates are sent.", fontSize = 12.sp, color = Color.White.copy(alpha = 0.5f))
        error?.let { Text(it, fontSize = 13.sp, color = Color(0xFFFF8A80)) }
        results.forEach { p ->
            Column(
                Modifier.fillMaxWidth().clickable { save(c.copy(place = p.name, lat = p.lat, lon = p.lon)); query = ""; results = emptyList() }
                    .padding(vertical = 8.dp)
            ) {
                Text(p.name)
                if (p.detail.isNotBlank()) Text(p.detail, fontSize = 12.sp, color = Color.White.copy(alpha = 0.6f))
            }
        }
        Toggle("Fahrenheit (°F)", c.fahrenheit) { save(c.copy(fahrenheit = it)) }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
}

@Composable
private fun Toggle(title: String, value: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)) }
        }
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(end = 8.dp))
    }
}
