package com.brushwork.paint.ui.assist

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.brushwork.paint.assist.StrokeAssist
import com.brushwork.paint.assist.StrokePipeline
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * Scratch area that runs the real [StrokePipeline] with [settings] (1 "document px" = 1 screen
 * px here, matching 100% zoom): the faint line is the finger, the blue line what gets painted.
 */
@Composable
internal fun StabilizerTryPad(settings: StabilizerSettings, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    val params = remember(settings, density) {
        StrokePipeline.Params(
            mode = settings.mode,
            catchUp = settings.catchUp,
            ropeLength = settings.ropeLengthDp * density,
            smoothLag = StrokeAssist.smoothLagDp(settings.strength) * density,
            step = 2f,
        )
    }
    val currentParams by rememberUpdatedState(params)
    val pipeline = remember { StrokePipeline() }
    val raw = remember { ArrayList<Offset>() }
    val painted = remember { ArrayList<Offset>() }
    var version by remember { mutableIntStateOf(0) }
    var used by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF141518))
            .border(1.dp, BrushworkColors.ChromeBorder, shape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    used = true
                    raw.clear()
                    painted.clear()
                    raw += down.position
                    val first = pipeline.down(ToolPoint(down.position.x, down.position.y), currentParams)
                    painted += Offset(first.x, first.y)
                    version++
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        val p = ToolPoint(change.position.x, change.position.y)
                        raw += change.position
                        change.consume()
                        if (!change.pressed) {
                            for (q in pipeline.up(p)) painted += Offset(q.x, q.y)
                            version++
                            break
                        }
                        for (q in pipeline.move(p)) painted += Offset(q.x, q.y)
                        version++
                    }
                    if (pipeline.isActive) {
                        pipeline.cancel()
                        version++
                    }
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            if (version < 0) return@Canvas // reading the counter redraws on every processed event
            if (raw.size > 1) drawPoints(raw, PointMode.Polygon, Color(0x59FFFFFF), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
            if (painted.size > 1) drawPoints(painted, PointMode.Polygon, BrushworkColors.Accent, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            if (pipeline.activeMode == StabilizerMode.ROPE) {
                val b = Offset(pipeline.brushX, pipeline.brushY)
                val f = Offset(pipeline.fingerX, pipeline.fingerY)
                drawCircle(Color(0x40FFFFFF), radius = pipeline.ropeLength, center = f, style = Stroke(1.dp.toPx()))
                drawLine(Color.White, b, f, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(BrushworkColors.Accent, radius = 4.dp.toPx(), center = b)
            }
        }
        if (!used) {
            Text(
                "Draw here to try",
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}
