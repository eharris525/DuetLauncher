package dev.duet.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** How the launcher's panels (clock, alphabet bar, search, buttons) are drawn. */
enum class SurfaceStyle(val title: String, val summary: String) {
    LIQUID_GLASS("Liquid glass", "Glossy, with a shine across the top and a bright rim"),
    FROSTED("Frosted", "Soft milky glass, no shine"),
    SOLID("Solid", "Opaque dark panels"),
    OUTLINE("Outline", "Clear, with a thin outline"),
    MINIMAL("Minimal", "No panels — just text and icons on the wallpaper"),
    TINTED("Tinted glass", "Frosted glass in your accent colour"),
    GRADIENT("Gradient", "Panels that fade through your accent colour"),
    NEON("Neon", "Dark panels with a glowing accent edge"),
    AMOLED("Pure black", "Black panels, for OLED screens and dark wallpapers"),
    RETRO("Retro card", "Warm dark cards with a thick outline and a hard drop shadow"),
}

/** Accent for highlights (the active letter, the action button's glyph). */
enum class AccentColor(val title: String, val color: Color) {
    BLUE("Blue", Color(0xFF8AB4F8)),
    PURPLE("Purple", Color(0xFFC3A6FF)),
    PINK("Pink", Color(0xFFFF9EC7)),
    RED("Red", Color(0xFFFF8A80)),
    ORANGE("Orange", Color(0xFFFFB870)),
    YELLOW("Yellow", Color(0xFFFFE082)),
    GREEN("Green", Color(0xFF8BE0A4)),
    TEAL("Teal", Color(0xFF7FDDE0)),
    CRIMSON("Crimson", Color(0xFFD35C4D)),
    INDIGO("Indigo", Color(0xFF9FA8FF)),
    LAVENDER("Lavender", Color(0xFFDCC8FF)),
    CORAL("Coral", Color(0xFFFF9E80)),
    GOLD("Gold", Color(0xFFE6C068)),
    LIME("Lime", Color(0xFFC6F68D)),
    MINT("Mint", Color(0xFFA8F0D0)),
    SKY("Sky", Color(0xFF81D4FA)),
    WHITE("White", Color(0xFFFFFFFF)),
}

/** A ready-made look: panel style, accent colour, font and wallpaper dimming in one tap. */
enum class ThemePreset(
    val title: String,
    val summary: String,
    val style: SurfaceStyle,
    val accent: AccentColor,
    val font: FontChoice = FontChoice.DEFAULT,
    val dim: Int = 0,
) {
    DUET("Duet", "Liquid glass and blue, the original look", SurfaceStyle.LIQUID_GLASS, AccentColor.BLUE),
    DROPLERT("Droplert", "Chunky retro cards, crimson accent, heavy type", SurfaceStyle.RETRO, AccentColor.CRIMSON, FontChoice.HEAVY, dim = 30),
    FROST("Frost", "Soft frosted glass with sky blue", SurfaceStyle.FROSTED, AccentColor.SKY, FontChoice.LIGHT),
    MIDNIGHT("Midnight", "Solid dark panels with lavender", SurfaceStyle.SOLID, AccentColor.LAVENDER, dim = 25),
    OLED("OLED", "Pure black panels on a dimmed wallpaper", SurfaceStyle.AMOLED, AccentColor.WHITE, dim = 45),
    NEON("Neon", "Glowing teal edges on dark glass", SurfaceStyle.NEON, AccentColor.TEAL, FontChoice.MEDIUM, dim = 20),
    SUNSET("Sunset", "Warm gradient panels", SurfaceStyle.GRADIENT, AccentColor.CORAL),
    FOREST("Forest", "Green-tinted glass", SurfaceStyle.TINTED, AccentColor.MINT),
    TERMINAL("Terminal", "Thin outlines, lime text accents, monospace", SurfaceStyle.OUTLINE, AccentColor.LIME, FontChoice.MONO, dim = 35),
    PAPER("Paper", "Just text on the wallpaper, serif font", SurfaceStyle.MINIMAL, AccentColor.GOLD, FontChoice.SERIF, dim = 15);

    fun isCurrent(p: Prefs) = p.surfaceStyle == style && p.accent == accent && p.font == font && p.wallpaperDim == dim

    fun apply(p: Prefs) { p.surfaceStyle = style; p.accent = accent; p.font = font; p.wallpaperDim = dim }
}

val RetroPanel = Color(0xFF28231D)
val RetroEdge = Color(0xFF6E6757)

val accent: Color get() = DuetApp.instance.prefs.accent.color

/** A panel in the chosen [SurfaceStyle]. Read during composition, so a style change redraws it. */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    strength: Float = 1f,
): Modifier = when (DuetApp.instance.prefs.surfaceStyle) {
    SurfaceStyle.LIQUID_GLASS -> liquidGlass(shape, strength)
    SurfaceStyle.FROSTED -> this
        .clip(shape)
        .background(Color.White.copy(alpha = 0.16f * strength))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f * strength), Color.Transparent)))
        .border(1.dp, Color.White.copy(alpha = 0.22f * strength), shape)
    SurfaceStyle.SOLID -> this
        .clip(shape)
        .background(Color(0xFF1B1C20).copy(alpha = 0.92f))
        .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
    SurfaceStyle.OUTLINE -> this
        .clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.55f * strength), shape)
    SurfaceStyle.MINIMAL -> this.clip(shape)
    SurfaceStyle.TINTED -> this
        .clip(shape)
        .background(accent.copy(alpha = 0.22f * strength))
        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.12f * strength), Color.Transparent)))
        .border(1.dp, accent.copy(alpha = 0.45f * strength), shape)
    SurfaceStyle.GRADIENT -> this
        .clip(shape)
        .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.50f * strength), Color(0xFF6A3FA0).copy(alpha = 0.35f * strength))))
        .border(1.dp, Color.White.copy(alpha = 0.18f * strength), shape)
    SurfaceStyle.NEON -> this
        .clip(shape)
        .background(Color(0xFF07090D).copy(alpha = 0.72f))
        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.16f * strength), Color.Transparent)))
        .border(1.5.dp, accent.copy(alpha = 0.95f * strength), shape)
    SurfaceStyle.RETRO -> this
        // A hard shadow offset down-right, like a printed card lifted off the page.
        .drawBehind {
            val outline = shape.createOutline(size, layoutDirection, this)
            translate(3.dp.toPx(), 4.dp.toPx()) { drawOutline(outline, Color.Black.copy(alpha = 0.6f)) }
        }
        .clip(shape)
        .background(RetroPanel.copy(alpha = 0.96f))
        .border(2.dp, RetroEdge, shape)
    SurfaceStyle.AMOLED -> this
        .clip(shape)
        .background(Color.Black)
        .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
}

/**
 * Glossy glass surface: a dark translucent base, a glass gradient, a specular sheen across
 * the top half, a soft diagonal glint, and a bright rim along the edge.
 */
private fun Modifier.liquidGlass(shape: Shape, strength: Float): Modifier = this
    .clip(shape)
    .background(Color.Black.copy(alpha = 0.22f * strength))
    .background(
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.20f * strength),
            0.5f to Color.White.copy(alpha = 0.06f * strength),
            1f to Color.White.copy(alpha = 0.12f * strength),
        )
    )
    .drawWithContent {
        drawContent()
        // Specular sheen on the upper half, like light on curved glass.
        drawRect(
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.30f * strength),
                1f to Color.White.copy(alpha = 0.02f * strength),
                endY = size.height * 0.5f,
            ),
            size = Size(size.width, size.height * 0.5f),
        )
        // Diagonal glint.
        drawRect(
            Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.10f * strength),
                1f to Color.Transparent,
                start = Offset(size.width * 0.15f, 0f),
                end = Offset(size.width * 0.55f, size.height),
            )
        )
    }
    .border(
        1.dp,
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.60f * strength),
                Color.White.copy(alpha = 0.10f * strength),
                Color.White.copy(alpha = 0.28f * strength),
            )
        ),
        shape,
    )
