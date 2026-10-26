package com.brushwork.paint.v110.a11

import android.net.Uri
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportJob
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.LayersLabels110
import com.brushwork.paint.ui.exchange.ExchangeUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v1.10 (A11 merge, lead; D47, design 2.13): an SVG or PDF export with Brushwork data of an
 * artwork above the share format's 1000 layers is refused BEFORE any work starts, with
 * `LayersLabels110.PAYLOAD_TOO_MANY`: Save as... never opens the file picker, Share never shows the
 * busy overlay or writes into the share folder, and a destination already picked is never opened
 * for writing. Without Brushwork data the same artwork goes to the picker; 1000 layers are not
 * refused. (`PayloadRefusalTest` covers the payload's own refusal at write time.)
 */
@RunWith(RobolectricTestRunner::class)
class ExportRefusedBeforeWorkTest {
    @Test
    fun anExportWithBrushworkDataAbove1000LayersIsRefusedBeforeItStarts() {
        val app = RuntimeEnvironment.getApplication()
        val c = Smoke.controller(app, Smoke.document(8, 8, layers = 1001))
        try {
            val state = ExchangeUiState(c)
            state.context = app
            var picked = 0
            state.createPicker = { _, _ -> picked++ }

            // Save as...: refused before the file picker opens.
            state.exportOptions = ExportOptions(VectorFormat.SVG)
            c.message = null
            state.saveAs()
            assertEquals("Save as: no picker", 0, picked)
            assertEquals("Save as", LayersLabels110.PAYLOAD_TOO_MANY, c.message)
            assertNull("Save as: no busy overlay", c.busyMessage)

            // Share: refused before the busy overlay; nothing in the share folder.
            state.exportOptions = ExportOptions(VectorFormat.PDF)
            val shared = File(File(app.cacheDir, "exports"), ExportJob(c, state.exportOptions).fileName)
            c.message = null
            state.share()
            assertEquals("Share", LayersLabels110.PAYLOAD_TOO_MANY, c.message)
            assertNull("Share: no busy overlay", c.busyMessage)
            Smoke.pump(200)
            assertFalse("Share: no file", shared.exists())

            // A destination already picked: never opened for writing.
            var opened = 0
            val uri = Uri.fromFile(File(app.cacheDir, "refused.svg"))
            shadowOf(app.contentResolver).registerOutputStreamSupplier(uri) { opened++; ByteArrayOutputStream() }
            state.exportOptions = ExportOptions(VectorFormat.SVG)
            c.message = null
            state.exportTo(uri)
            assertEquals("Save into a picked file", LayersLabels110.PAYLOAD_TOO_MANY, c.message)
            assertNull("Save into a picked file: no busy overlay", c.busyMessage)
            Smoke.pump(200)
            assertEquals("Save into a picked file: not written", 0, opened)

            // Without Brushwork data the same artwork exports: the picker opens.
            state.exportOptions = ExportOptions(VectorFormat.SVG, includePayload = false)
            c.message = null
            state.saveAs()
            assertEquals("without Brushwork data: the picker", 1, picked)
            assertNull("without Brushwork data: no refusal", c.message)
            assertNull("without Brushwork data", ExportJob(c, state.exportOptions).refusal())
        } finally {
            c.dispose()
        }

        // 1000 layers: no refusal.
        val fits = Smoke.controller(app, Smoke.document(8, 8, layers = 1000))
        try {
            assertNull("1000 layers fit", ExportJob(fits, ExportOptions(VectorFormat.PDF)).refusal())
        } finally {
            fits.dispose()
        }
    }
}
