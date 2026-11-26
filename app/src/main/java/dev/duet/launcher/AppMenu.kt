package dev.duet.launcher

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The menu for a long-pressed app: its icon and name in a glow of its own colour, the app's
 * shortcuts, which screen to open it on, and round buttons for favorite / move / hide / info /
 * uninstall. Opens just below the app (above it near the bottom of the screen).
 */
@Composable
fun AppMenu(
    app: AppInfo,
    isFavorite: Boolean,
    canMove: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onLaunched: () -> Unit,
) {
    val prefs = DuetApp.instance.prefs
    val repo = DuetApp.instance.apps
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val gap = with(LocalDensity.current) { 6.dp.roundToPx() }
    val margin = with(LocalDensity.current) { 12.dp.roundToPx() }
    val belowState = remember { androidx.compose.runtime.mutableStateOf(true) }
    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
                val x = anchorBounds.left.coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
                val below = anchorBounds.bottom + gap + popupContentSize.height <= windowSize.height - margin
                belowState.value = below
                val y = if (below) anchorBounds.bottom + gap else (anchorBounds.top - gap - popupContentSize.height).coerceAtLeast(margin)
                return IntOffset(x, y)
            }
        }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        appear.animateTo(1f, spring(dampingRatio = 0.75f, stiffness = 700f))
    }

    val px = with(LocalDensity.current) { 52.dp.roundToPx() }
    val icon by produceState<ImageBitmap?>(repo.peekIcon(app, prefs.iconPack, px, prefs.iconStyle), app.key) {
        value = value ?: repo.icon(app, prefs.iconPack, px, prefs.iconStyle)
    }
    // The app's own colour, from its original icon.
    val tint by produceState<Color?>(null, app.key) {
        value = withContext(Dispatchers.Default) {
            repo.icon(app, prefs.iconPack, 48, IconStyle.ORIGINAL)?.let {
                Color(IconStyle.mainColor(BitmapDrawable(ctx.resources, it.asAndroidBitmap())))
            }
        }
    }
    val shortcuts by produceState(emptyList<Pair<ShortcutInfo, ImageBitmap?>>(), app.key) {
        value = withContext(Dispatchers.IO) { appShortcuts(ctx, app) }
    }
    val spanned = ScreenCoverage.geometry
    val glow = tint ?: accent

    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier
                .graphicsLayer {
                    val s = 0.85f + 0.15f * appear.value
                    scaleX = s; scaleY = s; alpha = appear.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(0.15f, if (belowState.value) 0f else 1f)
                }
                .width(300.dp)
                .then(menuPanel(shape, glow))
                .padding(14.dp)
        ) {
            // Header: icon and name.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).drawBehind {
                        drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.55f), Color.Transparent)), radius = size.minDimension * 0.9f)
                    }
                ) { icon?.let { Image(it, null, Modifier.size(52.dp)) } }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(app.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (isFavorite) "Favorite" else "App", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                }
                // Reorder favorites without closing the menu.
                if (canMove) {
                    SmallRound(Icons.Filled.KeyboardArrowUp, "Move up", glow, canMoveUp) { prefs.moveFavorite(app.key, -1) }
                    Spacer(Modifier.width(6.dp))
                    SmallRound(Icons.Filled.KeyboardArrowDown, "Move down", glow, canMoveDown) { prefs.moveFavorite(app.key, 1) }
                }
            }

            // The app's own shortcuts.
            if (shortcuts.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f))) {
                    shortcuts.take(4).forEachIndexed { i, (s, ic) ->
                        if (i > 0) Box(Modifier.fillMaxWidth().padding(start = 50.dp).height(1.dp).background(Color.White.copy(alpha = 0.06f)))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                onDismiss()
                                val la = ctx.getSystemService(LauncherApps::class.java)
                                runCatching { la.startShortcut(s, null, ScreenCoverage.launchOptions(ScreenCoverage.tapTarget())) }
                                onLaunched()
                            }.padding(horizontal = 12.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                                if (ic != null) Image(ic, null, Modifier.size(26.dp))
                                else Box(Modifier.size(8.dp).clip(CircleShape).background(glow))
                            }
                            Spacer(Modifier.width(12.dp))
                            Text((s.shortLabel ?: s.longLabel ?: "").toString(), color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            // Which screen, when spanned.
            if (spanned != null) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (a, b) = if (spanned.sideBySide) "Left screen" to "Right screen" else "Top screen" to "Bottom screen"
                    ScreenChip(a, first = true, sideBySide = spanned.sideBySide, glow = glow, modifier = Modifier.weight(1f)) {
                        onDismiss(); repo.launchOn(app, Covered.FIRST); onLaunched()
                    }
                    ScreenChip(b, first = false, sideBySide = spanned.sideBySide, glow = glow, modifier = Modifier.weight(1f)) {
                        onDismiss(); repo.launchOn(app, Covered.SECOND); onLaunched()
                    }
                }
            }

            // Actions.
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                MenuAction(if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star, if (isFavorite) "Unfavorite" else "Favorite", glow) {
                    onDismiss(); prefs.toggleFavorite(app.key)
                }
                MenuAction(null, "Hide", glow, custom = { c -> HideGlyph(c) }) {
                    onDismiss()
                    prefs.hidden = prefs.hidden + app.key
                    prefs.favorites = prefs.favorites - app.key
                }
                MenuAction(Icons.Filled.Info, "Info", glow) { onDismiss(); repo.openInfo(app) }
                MenuAction(Icons.Filled.Delete, "Uninstall", glow, danger = true) { onDismiss(); repo.uninstall(app) }
            }
        }
    }
}

/** The menu's panel, in the current theme's style (always solid enough to read). */
private fun menuPanel(shape: RoundedCornerShape, glow: Color): Modifier =
    if (DuetApp.instance.prefs.surfaceStyle == SurfaceStyle.RETRO) Modifier
        .drawBehind { translate4(this) }
        .clip(shape)
        .background(RetroPanel)
        .border(2.dp, RetroEdge, shape)
    else Modifier
        .shadow(18.dp, shape, ambientColor = glow, spotColor = glow)
        .clip(shape)
        .background(Color(0xF2181A1F))
        .background(Brush.verticalGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent, Color.Transparent)))
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.28f), Color.White.copy(alpha = 0.06f))), shape)

/** Posterboard's hard offset shadow. */
private fun translate4(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
    drawRoundRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(4.dp.toPx(), 5.dp.toPx()), size = size,
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(24.dp.toPx()))
}

@Composable
private fun SmallRound(icon: ImageVector, label: String, glow: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(glow.copy(alpha = if (enabled) 0.18f else 0.05f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White.copy(alpha = if (enabled) 1f else 0.25f), modifier = Modifier.size(20.dp)) }
}

@Composable
private fun ScreenChip(label: String, first: Boolean, sideBySide: Boolean, glow: Color, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Two little screens, the chosen one lit.
        Canvas(Modifier.size(width = 22.dp, height = 16.dp)) {
            val g = 2.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
            if (sideBySide) {
                val w = (size.width - g) / 2
                drawRoundRect(if (first) glow else Color.White.copy(alpha = 0.3f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, size.height), r)
                drawRoundRect(if (!first) glow else Color.White.copy(alpha = 0.3f), Offset(w + g, 0f), androidx.compose.ui.geometry.Size(w, size.height), r)
            } else {
                val h = (size.height - g) / 2
                drawRoundRect(if (first) glow else Color.White.copy(alpha = 0.3f), Offset(0f, 0f), androidx.compose.ui.geometry.Size(size.width, h), r)
                drawRoundRect(if (!first) glow else Color.White.copy(alpha = 0.3f), Offset(0f, h + g), androidx.compose.ui.geometry.Size(size.width, h), r)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun MenuAction(
    icon: ImageVector?, label: String, glow: Color, enabled: Boolean = true, danger: Boolean = false,
    custom: (@Composable (Color) -> Unit)? = null, onClick: () -> Unit,
) {
    val tint = when { !enabled -> Color.White.copy(alpha = 0.25f); danger -> Color(0xFFFF8A80); else -> Color.White }
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .background(if (danger) Color(0x33FF8A80) else glow.copy(alpha = if (enabled) 0.18f else 0.06f)),
            contentAlignment = Alignment.Center,
        ) {
            if (custom != null) custom(tint) else if (icon != null) Icon(icon, label, tint = tint, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = tint.copy(alpha = if (enabled) 0.8f else 0.4f), fontSize = 10.sp, maxLines = 1)
    }
}

/** An eye with a line through it. */
@Composable
private fun HideGlyph(c: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension; val w = s * 0.09f
        drawOval(c, Offset(s * 0.08f, s * 0.28f), androidx.compose.ui.geometry.Size(s * 0.84f, s * 0.44f), style = Stroke(w))
        drawCircle(c, s * 0.12f, Offset(s / 2, s / 2))
        drawLine(c, Offset(s * 0.15f, s * 0.85f), Offset(s * 0.85f, s * 0.15f), w, StrokeCap.Round)
    }
}

/** The app's launcher shortcuts (Duet is the home app, so it may show and start them), with icons. */
private fun appShortcuts(ctx: android.content.Context, app: AppInfo): List<Pair<ShortcutInfo, ImageBitmap?>> {
    val la = ctx.getSystemService(LauncherApps::class.java)
    if (!la.hasShortcutHostPermission()) return emptyList()
    val q = LauncherApps.ShortcutQuery()
        .setPackage(app.component.packageName)
        .setActivity(app.component)
        .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
    val list = runCatching { la.getShortcuts(q, app.user) }.getOrNull().orEmpty()
        .filter { it.isEnabled }
        .sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank }))
    val density = ctx.resources.displayMetrics.densityDpi
    return list.take(4).map { s ->
        s to runCatching { la.getShortcutIconDrawable(s, density)?.toBitmap(72, 72)?.asImageBitmap() }.getOrNull()
    }
}
