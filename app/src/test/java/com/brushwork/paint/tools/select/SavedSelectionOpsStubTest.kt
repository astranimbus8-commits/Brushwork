package com.brushwork.paint.tools.select

import android.graphics.Path
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F2 (§3.14, §4.6): the `SavedSelectionOps.mappedForCanvas` stub on `main` keeps the list
 * when a canvas operation keeps the geometry and the size, and empties it otherwise (a flip, a
 * smaller crop), cleared undoably inside the operation's step. Area G deletes or rewrites this
 * test when it fills the mapping.
 */
@RunWith(RobolectricTestRunner::class)
class SavedSelectionOpsStubTest {
    private val w = 40
    private val h = 30

    private fun snapshot(): CanvasSnapshot {
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "L", BitmapUtils.createLayerBitmap(w, h))
        val p = Path().apply { addRect(2f, 3f, 12f, 9f, Path.Direction.CW) }
        doc.savedSelections = listOf(SavedSelection.of(doc.newSelectionId(), "Selection 1", Selection.fromPath(p, w, h, antiAlias = false), 1L)!!)
        return CanvasSnapshot.of(doc)
    }

    @Test
    fun theStubKeepsOrEmptiesTheList() {
        val snap = snapshot()
        val list = snap.savedSelections
        assertSame(list, SavedSelectionOps.mappedForCanvas(list, CanvasOps.cropTo(snap, Rect(0, 0, w, h)), w, h))
        assertEquals(emptyList<SavedSelection>(), SavedSelectionOps.mappedForCanvas(list, CanvasOps.flip(snap, horizontal = true), w, h))
        assertEquals(emptyList<SavedSelection>(), SavedSelectionOps.mappedForCanvas(list, CanvasOps.cropTo(snap, Rect(0, 0, w - 10, h - 10)), w, h))
    }
}
