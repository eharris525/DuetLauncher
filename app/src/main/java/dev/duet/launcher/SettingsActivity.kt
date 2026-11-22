package dev.duet.launcher

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontFamily
import java.io.File
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class SettingsActivity : ComponentActivity() {
    /** Font file picker: copies the chosen file into app storage and switches to it. */
    private val pickFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFont(uri)
    }

    fun chooseFontFile() = runCatching {
        pickFont.launch(arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf", "application/vnd.ms-opentype", "application/octet-stream"))
    }

    private fun importFont(uri: Uri) {
        val prefs = DuetApp.instance.prefs
        val tmp = File(filesDir, "custom_font.tmp")
        val ok = runCatching {
            contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            Typeface.createFromFile(tmp) // throws if it isn't a usable font
            // createFromFile returns DEFAULT instead of throwing on some versions; check the file looks like a font.
            val magic = tmp.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            val known = listOf(byteArrayOf(0, 1, 0, 0), "OTTO".toByteArray(), "true".toByteArray(), "ttcf".toByteArray())
            require(known.any { it.contentEquals(magic) })
        }.isSuccess
        if (ok) {
            tmp.renameTo(FontChoice.customFontFile(this))
            prefs.customFontVersion++
            prefs.font = FontChoice.CUSTOM
        } else {
            tmp.delete()
            Toast.makeText(this, "That file isn't a TTF or OTF font", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DuetTheme { SettingsScreen(this) } }
    }
}

@Composable
private fun SettingsScreen(activity: SettingsActivity) {
    val prefs = DuetApp.instance.prefs
    val repo = DuetApp.instance.apps
    val ctx = LocalContext.current
    val mode = rememberScreenMode(activity)
    var packDialog by remember { mutableStateOf(false) }
    var hiddenDialog by remember { mutableStateOf(false) }
    var fontDialog by remember { mutableStateOf(false) }
    /** Which gesture's action is being chosen: "tap" or "swipe" (null = no dialog). */
    var actionDialog by remember { mutableStateOf<String?>(null) }
    var appPickerFor by remember { mutableStateOf<String?>(null) }
    val packs by produceState(emptyList<IconPack.Info>()) { value = withContext(Dispatchers.IO) { IconPack.installed(ctx) } }
    val packLabel = packs.firstOrNull { it.pkg == prefs.iconPack }?.label
        ?: if (prefs.iconPack.isEmpty()) "System icons" else prefs.iconPack

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
        ) {
            item {
                Text("Duet Launcher", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Detected display", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                        Text(mode.describe(), fontWeight = FontWeight.Medium)
                    }
                }
                ActionRow("Set as default home app", "Opens Android's default-apps settings") {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
                }
            }

            item { Section("Icons") }
            item { ActionRow("Icon pack", packLabel) { packDialog = true } }
            item { SwitchRow("Show icons", prefs.showIcons) { prefs.showIcons = it } }
            item { SliderRow("Icon size", prefs.iconSizeDp, 24..64, "dp") { prefs.iconSizeDp = it } }
            item { SliderRow("Label size", prefs.labelSizeSp, 14..34, "sp") { prefs.labelSizeSp = it } }

            item { Section("Style") }
            items(SurfaceStyle.entries) { style ->
                Row(
                    Modifier.fillMaxWidth().clickable { prefs.surfaceStyle = style }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = prefs.surfaceStyle == style, onClick = { prefs.surfaceStyle = style })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(style.title)
                        Text(style.summary, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            item {
                Text("Accent colour · ${prefs.accent.title}", modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccentColor.entries.forEach { a ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(a.color)
                                .border(if (prefs.accent == a) 3.dp else 0.dp, Color.White, CircleShape)
                                .clickable { prefs.accent = a }
                        )
                    }
                }
            }

            item { Section("Font") }
            item { ActionRow("Font", prefs.font.title) { fontDialog = true } }

            item { Section("Layout") }
            item { SwitchRow("Alphabet bar on the right", prefs.railOnRight) { prefs.railOnRight = it } }
            item { SwitchRow("Slide on either side", prefs.railBothSides, "Drag along the other edge too; the bar itself only shows on one side") { prefs.railBothSides = it } }
            item { SwitchRow("Compact alphabet bar", prefs.compactRail, "Bottom half of the screen, for one-handed use") { prefs.compactRail = it } }
            item { SwitchRow("Show widgets in single-screen mode", prefs.widgetsInSingle) { prefs.widgetsInSingle = it } }
            item { Text("When spanned across both screens", fontSize = 14.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 12.dp)) }
            items(DualLayout.entries) { layout ->
                Row(
                    Modifier.fillMaxWidth().clickable { prefs.dualLayout = layout }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = prefs.dualLayout == layout, onClick = { prefs.dualLayout = layout })
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(layout.title)
                        Text(layout.summary, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
            item { SwitchRow("Swap screens", prefs.swapScreens, "Put the home pane on the right (or bottom) screen") { prefs.swapScreens = it } }

            item { Section("Apps on one screen") }
            item {
                SwitchRow(
                    "Move to the free screen",
                    prefs.followUncoveredScreen,
                    "When an app opens on one screen, switch to the single-screen layout on the other",
                ) { prefs.followUncoveredScreen = it }
            }
            item {
                SwitchRow(
                    "Swipe an app to pick its screen",
                    prefs.swipeToPickScreen,
                    "Swipe an app left or right to open it on the left or right screen. A tap opens it as usual.",
                ) { prefs.swipeToPickScreen = it }
            }
            item {
                ActionRow(
                    "Detection: " + if (ScreenCoverage.serviceConnected) "precise" else "basic",
                    if (ScreenCoverage.serviceConnected) "Duet screen detection is on. It sees apps opened from anywhere."
                    else "Works for apps you open from the launcher. Tap to turn on \"Duet screen detection\" in Accessibility " +
                        "to also catch apps opened from notifications or recents, or moved between screens.",
                ) { runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            }

            item { Section("Duet action button") }
            item { SwitchRow("Show the button", prefs.actionButton, "Round button in the bottom-right corner") { prefs.actionButton = it } }
            item { ActionRow("Tap", prefs.tapAction.describe(prefs.tapApp)) { actionDialog = "tap" } }
            item { ActionRow("Swipe up", prefs.swipeAction.describe(prefs.swipeApp)) { actionDialog = "swipe" } }

            item { Section("Widgets") }
            item {
                Text(
                    "Long-press the clock on the home screen and choose Add widget or Edit widgets. " +
                        "In edit mode you can reorder, resize (− / +) and remove widgets.",
                    fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f),
                )
            }

            item { Section("Apps") }
            item { ActionRow("Hidden apps", "${prefs.hidden.size} hidden") { hiddenDialog = true } }
            item { Spacer(Modifier.heightIn(min = 48.dp)) }
        }
    }

    if (packDialog) {
        AlertDialog(
            onDismissRequest = { packDialog = false },
            confirmButton = { TextButton(onClick = { packDialog = false }) { Text("Close") } },
            title = { Text("Icon pack") },
            text = {
                LazyColumn {
                    item { PackChoice("System icons", prefs.iconPack.isEmpty()) { prefs.iconPack = ""; packDialog = false } }
                    items(packs) { p -> PackChoice(p.label, prefs.iconPack == p.pkg) { prefs.iconPack = p.pkg; packDialog = false } }
                    if (packs.isEmpty()) item {
                        Text(
                            "No icon packs installed. Any pack built for Nova, ADW or Apex launchers will show up here.",
                            fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
        )
    }

    if (fontDialog) {
        AlertDialog(
            onDismissRequest = { fontDialog = false },
            confirmButton = { TextButton(onClick = { fontDialog = false }) { Text("Close") } },
            title = { Text("Font") },
            text = {
                LazyColumn {
                    items(FontChoice.entries) { choice ->
                        val family: FontFamily? = remember(choice, prefs.customFontVersion) { choice.family(ctx) }
                        val label = if (choice == FontChoice.CUSTOM && family != null) "Custom font file (tap to replace)" else choice.title
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                fontDialog = false
                                if (choice == FontChoice.CUSTOM) activity.chooseFontFile() else prefs.font = choice
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = prefs.font == choice, onClick = null)
                            Text(label, fontFamily = family ?: FontFamily.Default, fontSize = 18.sp, modifier = Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
        )
    }

    actionDialog?.let { gesture ->
        val current = if (gesture == "tap") prefs.tapAction else prefs.swipeAction
        AlertDialog(
            onDismissRequest = { actionDialog = null },
            confirmButton = { TextButton(onClick = { actionDialog = null }) { Text("Close") } },
            title = { Text(if (gesture == "tap") "On tap" else "On swipe up") },
            text = {
                LazyColumn {
                    items(DuetAction.entries) { action ->
                        PackChoice(action.title, current == action) {
                            if (gesture == "tap") prefs.tapAction = action else prefs.swipeAction = action
                            actionDialog = null
                            if (action == DuetAction.LAUNCH_APP) appPickerFor = gesture
                        }
                    }
                }
            },
        )
    }

    appPickerFor?.let { gesture ->
        AlertDialog(
            onDismissRequest = { appPickerFor = null },
            confirmButton = { TextButton(onClick = { appPickerFor = null }) { Text("Cancel") } },
            title = { Text("Choose an app") },
            text = {
                LazyColumn {
                    items(repo.all, key = { it.key }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (gesture == "tap") prefs.tapApp = app.key else prefs.swipeApp = app.key
                                appPickerFor = null
                            }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app, 28)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label)
                        }
                    }
                }
            },
        )
    }

    if (hiddenDialog) {
        AlertDialog(
            onDismissRequest = { hiddenDialog = false },
            confirmButton = { TextButton(onClick = { hiddenDialog = false }) { Text("Done") } },
            title = { Text("Hidden apps") },
            text = {
                LazyColumn {
                    items(repo.all, key = { it.key }) { app ->
                        val hidden = app.key in prefs.hidden
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                prefs.hidden = if (hidden) prefs.hidden - app.key else prefs.hidden + app.key
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = hidden, onCheckedChange = null)
                            Spacer(Modifier.width(8.dp))
                            AppIcon(app, 28)
                            Spacer(Modifier.width(12.dp))
                            Text(app.label)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 28.dp, bottom = 4.dp),
    )
}

@Composable
private fun ActionRow(title: String, subtitle: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp)) {
        Text(title)
        subtitle?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)) }
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            subtitle?.let { Text(it, fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f)) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(title: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("$title · $value $unit")
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
        )
    }
}

@Composable
private fun PackChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}
