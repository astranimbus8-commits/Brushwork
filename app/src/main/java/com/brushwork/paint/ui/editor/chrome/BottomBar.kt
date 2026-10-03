package com.brushwork.paint.ui.editor.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.South
import androidx.compose.material.icons.filled.North
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.SliderMath
import com.brushwork.paint.ui.editor.blockCanvasTouches
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims

/**
 * The ibisPaint bottom bar (v1.6 §3.7.5), 50 dp of [IbisColors.BottomBar], seven slots of
 * 56 × 50 dp (shared evenly on narrower screens), left to right: brush / eraser switch, the tool
 * menu (current tool), the brush size disc (brush panel), the colour square (colour panel), hide /
 * show interface, the layer window, back to the gallery. The tool and layer slots show their open
 * state as a darker box with a light edge.
 */
@Composable
internal fun BottomBar(
    controller: EditorController,
    toolMenuOpen: Boolean,
    onToolMenu: () -> Unit,
    onBrushPanel: () -> Unit,
    onColorPanel: () -> Unit,
    interfaceHidden: Boolean,
    onToggleInterface: () -> Unit,
    layersOpen: Boolean,
    onLayers: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = controller.activeToolId
    val paintTool = controller.lastPaintTool
    val erasing = active == ToolId.ERASER
    val preset = controller.presetFor(controller.sliderToolId)
    // Derived: layersVersion changes with every committed edit; only the number may recompose.
    val layerNumber by remember(controller) {
        derivedStateOf {
            controller.layersVersion
            controller.doc.activeLayerIndex.coerceIn(0, (controller.doc.layers.size - 1).coerceAtLeast(0)) + 1
        }
    }
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(IbisDims.BottomBarHeight)
            .background(IbisColors.BottomBar)
            .blockCanvasTouches(),
    ) {
        val slot = ChromeLayout.bottomSlotWidth(maxWidth.value).dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            // 1: switch between the painting tool and the eraser (shows what a tap switches to).
            Slot(
                slot,
                if (erasing) "Eraser on: switch to ${paintTool.label.lowercase()}" else "Switch to eraser",
                onClick = { controller.endCanvasGesture(); controller.toggleEraser() },
            ) {
                Box(Modifier.size(IbisDims.BottomGlyph)) {
                    Icon(ChromeGlyphs.SwapArrows, null, tint = Color.White, modifier = Modifier.size(15.dp).align(Alignment.BottomStart))
                    Icon(if (erasing) EditorIcons.tool(paintTool) else EditorIcons.Eraser, null, tint = Color.White, modifier = Modifier.size(18.dp).align(Alignment.TopEnd))
                }
            }
            // 2: the tool menu, showing the current tool.
            Slot(slot, "Tools (current: ${active.label})", open = toolMenuOpen, onClick = onToolMenu) {
                Icon(EditorIcons.tool(active), null, tint = Color.White, modifier = Modifier.size(IbisDims.BottomGlyph))
            }
            // 3: the brush size disc (its number is drawn, not a label: the slot is "Open brush
            // settings" and the size row's value already reads the size).
            val sizeText = SliderMath.formatSizeFixed(preset?.size ?: 0f)
            Slot(slot, "Open brush settings", state = "$sizeText px", onClick = onBrushPanel) {
                Box(
                    Modifier
                        .size(IbisDims.BrushDisc)
                        .clip(CircleShape)
                        .background(Color.Black)
                        .border(IbisDims.BrushDiscRing, Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    DrawnText(sizeText, TextStyle(fontSize = IbisDims.BrushDiscText, color = Color.White), Modifier.fillMaxSize())
                }
            }
            // 4: the colour square.
            Slot(slot, "Open color picker", onClick = onColorPanel) {
                Box(
                    Modifier
                        .size(IbisDims.ColorSquare)
                        .checkerboard(4.dp)
                        .background(Color(controller.color))
                        .border(IbisDims.ColorSquareBorder, Color.White),
                )
            }
            // 5: hide / show the interface (the bar itself and any ✓ / ✕ stay).
            Slot(slot, if (interfaceHidden) "Show interface" else "Hide interface", onClick = onToggleInterface) {
                Icon(if (interfaceHidden) Icons.Filled.North else Icons.Filled.South, null, tint = Color.White, modifier = Modifier.size(IbisDims.BottomGlyph))
            }
            // 6: the layer window.
            Slot(
                slot,
                if (layersOpen) "Close layers (active layer $layerNumber)" else "Open layers (active layer $layerNumber)",
                open = layersOpen,
                onClick = onLayers,
            ) {
                if (layersOpen) {
                    Icon(ChromeGlyphs.DoubleChevronDown, null, tint = Color.White, modifier = Modifier.size(IbisDims.BottomGlyph))
                } else {
                    LayersGlyph(layerNumber)
                }
            }
            // 7: back to the gallery (autosaves).
            Slot(slot, "Back to gallery", onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White, modifier = Modifier.size(IbisDims.BottomGlyph))
            }
        }
    }
}

/**
 * One 56 × 50 slot: the whole slot is the target; [open] draws ibisPaint's open-state box behind
 * the glyph; [state] is read after the label by a screen reader.
 */
@Composable
private fun Slot(width: Dp, label: String, open: Boolean = false, state: String? = null, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        Modifier
            .width(width)
            .height(IbisDims.BottomBarHeight)
            .semantics {
                contentDescription = label
                if (state != null) stateDescription = state
            }
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (open) {
            Box(
                Modifier
                    .size(IbisDims.OpenBoxWidth, IbisDims.OpenBoxHeight)
                    .background(IbisColors.BottomBarOpen)
                    .border(IbisDims.OpenBoxEdge, IbisColors.BottomBarOpenEdge),
            )
        }
        glyph()
    }
}

/** ibisPaint's layers glyph: stacked squares behind a white tile with the active layer's number. */
@Composable
private fun LayersGlyph(number: Int) {
    Box(Modifier.size(IbisDims.BottomGlyph + 4.dp)) {
        Icon(ChromeGlyphs.StackedSquares, null, tint = Color.White, modifier = Modifier.size(IbisDims.BottomGlyph + 4.dp))
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .offset(x = 1.dp, y = (-1).dp)
                .size(18.dp)
                .background(Color.White, RoundedCornerShape(2.dp))
                .border(1.dp, IbisColors.BottomBar, RoundedCornerShape(2.dp)),
        ) {
            // Drawn, not a label: the slot already says "active layer N" (and a bare number
            // would clash with the layer window's row numbers).
            DrawnText(
                number.toString(),
                TextStyle(fontSize = if (number < 100) 11.sp else 8.sp, fontWeight = FontWeight.Bold, color = IbisColors.ListText),
                Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * [text] drawn centred in the space [modifier] gives, with no semantics: a glyph's decoration
 * (the size in the brush disc, the layer number on the layers glyph), never a second label.
 */
@Composable
private fun DrawnText(text: String, style: TextStyle, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Spacer(
        modifier.drawWithCache {
            val layout = measurer.measure(text, style, maxLines = 1, softWrap = false)
            onDrawBehind {
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, (size.height - layout.size.height) / 2f))
            }
        },
    )
}
