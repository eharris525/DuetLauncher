package dev.duet.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    WHITE("White", Color(0xFFFFFFFF)),
}

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
