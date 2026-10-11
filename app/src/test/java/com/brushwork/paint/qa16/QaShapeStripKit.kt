package com.brushwork.paint.qa16

import android.view.KeyEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.color.RobolectricUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * v1.8 F (design §3.9): what the Shape tool's QA tests need of its two-row strip. "Snap to
 * objects", the Handles group and the point actions sit on the More row now, and an open row
 * (or a popup on it) takes the next canvas touch (D25): each helper leaves both closed, and
 * [closeRow] closes the row before a canvas touch.
 */
internal object QaShapeStripKit {
    /** "Snap to objects" (on the More row) switched off, the row closed again. */
    fun snapOff(c: EditorController) {
        if (!c.snapping.enabled) return
        click("Snap to objects", exact = true)
        StripTestKit.closeMoreRow()
        assertTrue("snapping is off", !c.snapping.enabled)
    }

    /** The More row closed (a canvas touch would otherwise only close it). */
    fun closeRow() {
        StripTestKit.closeMoreRow()
    }

    /** The More row's "Handles" popup open (the v1.7 Handles group: scale and sides). */
    fun openHandles() {
        StripTestKit.openMoreRow()
        click("Handles", exact = true)
        assertTrue("the Handles popup: ${SmokeUi.shown()}", SmokeUi.has("Shorter handles", exact = true))
    }

    /** The "Handles" popup closed (Back on its window), then the More row. */
    fun closeHandles() {
        if (SmokeUi.has("Shorter handles", exact = true)) {
            val top = RobolectricUi.windowRoots().last()
            top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
            SmokeUi.settle()
            assertFalse("the Handles popup closed", SmokeUi.has("Shorter handles", exact = true))
        }
        StripTestKit.closeMoreRow()
    }
}
