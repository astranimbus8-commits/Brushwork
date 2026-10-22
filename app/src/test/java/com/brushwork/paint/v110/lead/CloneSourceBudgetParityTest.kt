package com.brushwork.paint.v110.lead

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.clone.CloneSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max

/**
 * v1.10 F1 (design 3.3; lead, frozen): the clone source's All-layers snapshot cap comes from the
 * session's budget (`EngineConfig.budget.cloneSnapshotCap()`), and with memory unknown (v1.9's
 * budgets, as every session of F1 and every phone whose memory cannot be read) it is v1.9's
 * `max(48 MiB, heap / 4)`.
 */
@RunWith(RobolectricTestRunner::class)
class CloneSourceBudgetParityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val heap = Runtime.getRuntime().maxMemory()
    private val v19Cap = max(48L shl 20, heap / 4)

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        scope.cancel()
    }

    private fun cloneSource(): CloneSource {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val doc = Document("clone", "Clone", 64, 48)
        doc.layers += Layer(doc.newLayerId(), "Layer", BitmapUtils.createLayerBitmap(64, 48))
        return CloneSource(EditorController(context, doc, scope, settings))
    }

    @Test
    fun withMemoryUnknownTheCapIsV19s() {
        assertEquals("outside a session", v19Cap, EngineConfig.budget.cloneSnapshotCap())
        assertEquals("the clone source outside a session", v19Cap, cloneSource().snapshotBudgetBytes)
        EngineConfig.openSession(EngineLevel.CLASSIC, Budgets.v19(heap), null)
        assertEquals("a session with v1.9's budgets", v19Cap, EngineConfig.budget.cloneSnapshotCap())
        assertEquals("the clone source in that session", v19Cap, cloneSource().snapshotBudgetBytes)
    }

    @Test
    fun theCloneSourceReadsTheSessionsBudget() {
        EngineConfig.openSession(EngineLevel.FULL, Budgets(8L shl 30, 4L shl 30, known = true, stepDown = 0), null)
        assertEquals("the session's cap", EngineConfig.budget.cloneSnapshotCap(), cloneSource().snapshotBudgetBytes)
        EngineConfig.closeSession()
        assertEquals("back to the gallery's budget", v19Cap, cloneSource().snapshotBudgetBytes)
    }
}
