package com.brushwork.paint.ui.assist

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.brushwork.paint.model.RulerType
import kotlin.math.cos
import kotlin.math.sin

/** 24dp line icons for the ruler types (tinted by Icon like the Material icons). */
internal object AssistIcons {
    val Straight: ImageVector by lazy {
        icon("RulerStraight") {
            moveTo(4f, 20f); lineTo(20f, 4f)
            // Ticks perpendicular to the line.
            tick(8f, 16f, 1.6f); tick(12f, 12f, 2.8f); tick(16f, 8f, 1.6f)
        }
    }

    val Circle: ImageVector by lazy {
        icon("RulerCircle") {
            circle(12f, 12f, 8.5f)
            circle(12f, 12f, 0.9f)
        }
    }

    val Ellipse: ImageVector by lazy {
        icon("RulerEllipse") {
            moveTo(2.5f, 12f)
            arcTo(9.5f, 5.5f, 0f, false, true, 21.5f, 12f)
            arcTo(9.5f, 5.5f, 0f, false, true, 2.5f, 12f)
            close()
            circle(12f, 12f, 0.9f)
        }
    }

    val Radial: ImageVector by lazy {
        icon("RulerRadial") {
            for (k in 0 until 8) {
                val a = Math.toRadians(k * 45.0 + 22.5)
                val c = cos(a).toFloat(); val s = sin(a).toFloat()
                moveTo(12f + c * 3.5f, 12f + s * 3.5f)
                lineTo(12f + c * 10f, 12f + s * 10f)
            }
            circle(12f, 12f, 0.9f)
        }
    }

    fun of(type: RulerType): ImageVector = when (type) {
        RulerType.STRAIGHT -> Straight
        RulerType.CIRCLE -> Circle
        RulerType.ELLIPSE -> Ellipse
        RulerType.RADIAL -> Radial
    }

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .path(
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
            .build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, false, true, cx + r, cy)
        arcTo(r, r, 0f, false, true, cx - r, cy)
        close()
    }

    /** Short tick at (x, y) across a line running at -45 degrees. */
    private fun PathBuilder.tick(x: Float, y: Float, half: Float) {
        moveTo(x - half, y - half)
        lineTo(x + half, y + half)
    }
}
