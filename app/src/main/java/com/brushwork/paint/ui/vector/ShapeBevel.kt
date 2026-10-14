package com.brushwork.paint.ui.vector

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pentagon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.PendingButtonSize
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.points.BevelPopup
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripRow

/**
 * v1.9 (R18): the Shape strip's [PointLabels19.BEVEL] (Points mode only; a 40 dp icon tagged
 * [V19Tags.BEVEL], the More row's [StripKeysV19.BEVEL], after v1.8's More items): it bevels the
 * selected sharp corners at once ([ShapeTool.bevelPoints]) and opens the popup on that step;
 * dimmed while no point is selected.
 */
internal fun shapeBevelItem(tool: ShapeTool): StripItem =
    StripItem(StripKeysV19.BEVEL.key, PointLabels19.BEVEL, StripKeysV19.BEVEL.widthDp.dp, row = StripRow.MORE) {
        Box(Modifier.testTag(V19Tags.BEVEL)) {
            ShapeIconAction(Icons.Filled.Pentagon, PointLabels19.BEVEL, enabled = tool.canBevel) { tool.bevelPoints() }
        }
    }

/**
 * R18: the bevel popup ([BevelPopup], tag [V19Tags.BEVEL_POPUP]) while [ShapeTool.bevelPopupOpen]:
 * anchored at the bottom, centred, above the ✓ / ✕ (which sit above the slider rows and the
 * bottom bar), so the bevelled points stay in view. It is not focusable: touches beside it reach
 * the canvas (two fingers pan and zoom; one finger closes it and is consumed by the tool, adding
 * no point). Its Amount and Segments re-apply the bevel from the points before it, at frame rate
 * (I22), within the one step of the bevel.
 */
@Composable
internal fun ShapeBevelPopupHost(tool: ShapeTool) {
    if (!tool.bevelPopupOpen) return
    // Back closes it (the popup is not focusable, so the activity hears Back).
    BackHandler { tool.closeBevelPopup() }
    val density = LocalDensity.current
    val widthDp = LocalConfiguration.current.screenWidthDp.toFloat()
    // The window may reach under the navigation bar (edge to edge): its height counts too.
    val navDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }.value
    val bottomDp = ChromeLayout.fitInsetBottom(navDp, rows = ChromeLayout.sliderRowCount(widthDp)) +
        2 * IbisDims.PendingBarGap.value + PendingButtonSize.value
    val bottomPx = with(density) { bottomDp.dp.roundToPx() }
    val position = remember(bottomPx) { AboveTheBottom(bottomPx) }
    Popup(
        popupPositionProvider = position,
        // Back reaches the popup window only when it has the keys (tests, a hardware key):
        // it closes it there too.
        onDismissRequest = tool::closeBevelPopup,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = false),
    ) {
        BevelPopup(
            amount = tool.bevelAmount,
            segments = tool.bevelSegments,
            onAmount = tool::changeBevelAmount,
            onSegments = tool::changeBevelSegments,
            onDone = tool::closeBevelPopup,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** Centred, its bottom [bottomPx] above the window's. */
private class AboveTheBottom(private val bottomPx: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
        IntOffset(
            ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0),
            (windowSize.height - bottomPx - popupContentSize.height).coerceAtLeast(0),
        )
}
