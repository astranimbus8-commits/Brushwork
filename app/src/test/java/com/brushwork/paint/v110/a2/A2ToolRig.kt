package com.brushwork.paint.v110.a2

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A2 test kit: editors without a screen (as `ShapeLayerEditTest` builds them) driven through the
 * controller's real input dispatch, for the color delegates' tests. Each controller's scope runs
 * unconfined; [release] ends them (the tools' snapshot observers would keep the editors alive).
 */
internal class A2ToolRig {
    private val scopes = ArrayList<CoroutineScope>()

    fun controller(w: Int = 200, h: Int = 200, color: Int = RED): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("t", "t", w, h).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.color = color
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    fun release() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    companion object {
        const val RED = 0xFFFF0000.toInt()
        const val BLUE = 0xFF0000FF.toInt()
        const val GREEN = 0xFF00FF00.toInt()
        const val BLACK = 0xFF000000.toInt()

        fun EditorController.drag(vararg pts: Pair<Float, Float>) {
            pointerDown(ToolPoint(pts[0].first, pts[0].second))
            for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
            pointerUp(ToolPoint(pts.last().first, pts.last().second))
        }

        fun EditorController.tap(x: Float, y: Float) = drag(x to y)

        /** What the canvas shows of the document: the composite with the live previews. */
        fun EditorController.composite(): Bitmap {
            val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            compositor.drawDocument(Canvas(out), null, target = null)
            return out
        }

        /** Delivers the state changes to their snapshot observers (what a frame does in the app). */
        fun frame() = Snapshot.sendApplyNotifications()

        fun hex(c: Int) = "%08X".format(c)
    }
}
