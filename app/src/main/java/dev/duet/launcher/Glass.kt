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

/**
 * Glossy glass surface: a dark translucent base, a glass gradient, a specular sheen across
 * the top half, a soft diagonal glint, and a bright rim along the edge.
 */
fun Modifier.glass(
    shape: Shape = RoundedCornerShape(24.dp),
    strength: Float = 1f,
): Modifier = this
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
