package com.brushwork.paint.ui.vector

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.editor.PendingButtonSize
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.points.BevelPopup
import com.brushwork.paint.ui.theme.IbisDims

/**
 * v1.9 (R18): Polyline's bevel popup ([BevelPopup], tag `V19Tags.BEVEL_POPUP`) while
 * [CurveTool.bevelPopupShown]. Like Blender's "Adjust Last Operation" panel it sits at the bottom:
 * centred above the bottom bar, the slider rows and the ✓ / ✕ bar ([CurveBevelPlacement]), so the
 * bevelled points stay in sight and ✓ stays reachable. It is its own window and not focusable:
 * touches outside it reach the canvas (pan and zoom pass by; the one-finger tap that closes it is
 * consumed by [CurveTool]). Its changes re-apply the same in-tool step ([CurveTool.setBevelAmount],
 * [CurveTool.setBevelSegments]); "Done" closes it ([CurveTool.closeBevelPopup]).
 */
@Composable
internal fun CurveBevelPopupHost(tool: CurveTool) {
    val session = tool.bevelSession ?: return
    if (!session.popupOpen) return
    val density = LocalDensity.current
    val view = LocalView.current
    val navDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }.value
    val widthDp = with(density) { view.width.toDp() }.value.takeIf { it > 0f } ?: LocalConfiguration.current.screenWidthDp.toFloat()
    val bottomPx = with(density) { CurveBevelPlacement.bottomDp(navDp, ChromeLayout.sliderRowCount(widthDp)).dp.roundToPx() }
    val position = remember(view, bottomPx) { AboveTheBottomChrome(view, bottomPx) }
    Popup(
        popupPositionProvider = position,
        onDismissRequest = { tool.closeBevelPopup() },
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        BevelPopup(
            amount = session.amount,
            segments = session.segments,
            onAmount = { tool.setBevelAmount(it) },
            onSegments = { tool.setBevelSegments(it) },
            onDone = { tool.closeBevelPopup() },
        )
    }
}

/** Where the bevel popup sits (pure, so a test can pin it). */
internal object CurveBevelPlacement {
    /**
     * Its bottom edge above the window's bottom (dp): the navigation bar, the bottom bar, the
     * [sliderRows] slider rows and the ✓ / ✕ bar with its gaps (EditorScreen's place for a
     * minimized panel while a path is pending).
     */
    fun bottomDp(navDp: Float, sliderRows: Int): Float =
        navDp + IbisDims.BottomBarHeight.value + sliderRows * IbisDims.SliderRowHeight.value +
            IbisDims.PendingBarGap.value + PendingButtonSize.value + IbisDims.PendingBarGap.value

    /**
     * The popup's top-left in window px: centred on the editor's view ([left], [top], [width] ×
     * [height]) with its bottom [bottomPx] above the view's bottom, kept inside the view.
     */
    fun position(left: Int, top: Int, width: Int, height: Int, bottomPx: Int, popup: IntSize): IntOffset {
        val x = (left + (width - popup.width) / 2).coerceAtLeast(left)
        val y = (top + height - bottomPx - popup.height).coerceAtLeast(top)
        return IntOffset(x, y)
    }
}

/** [CurveBevelPlacement.position] on the editor's [view] as it is laid out now. */
private class AboveTheBottomChrome(private val view: View, private val bottomPx: Int) : PopupPositionProvider {
    private val at = IntArray(2)

    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        view.getLocationInWindow(at)
        return CurveBevelPlacement.position(at[0], at[1], view.width, view.height, bottomPx, popupContentSize)
    }
}
