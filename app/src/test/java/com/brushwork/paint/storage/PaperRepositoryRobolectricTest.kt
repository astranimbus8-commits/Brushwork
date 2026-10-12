package com.brushwork.paint.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 F1a (R15, design 2.3.3): `ProjectRepository` copies `Document.paper` into `project.json`
 * on save and back on load; a document without paper saves no paper key (I13).
 */
@RunWith(RobolectricTestRunner::class)
class PaperRepositoryRobolectricTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "projects").deleteRecursively()
    }

    private fun doc(id: String, paper: PaperSpec?): Document = Document(id, "Paper $id", 40, 30).also { d ->
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(40, 30))
        d.activeLayerIndex = 0
        d.paper = paper
    }

    private fun projectJson(id: String) = File(context.filesDir, "projects/$id/project.json").readText()

    @Test
    fun thePaperIsSavedAndLoaded() = runBlocking {
        val repo = ProjectRepository(context)
        repo.save(doc("white", PaperSpec()), null)
        repo.save(doc("cream", PaperSpec(0xFFF4ECD8.toInt())), null)
        repo.save(doc("none", null), null)
        assertEquals(PaperSpec(), repo.load("white").paper)
        assertEquals(PaperSpec(0xFFF4ECD8.toInt()), repo.load("cream").paper)
        assertNull(repo.load("none").paper)
        assertTrue(projectJson("white").contains("\"paper\":{\"color\":-1}"))
        assertFalse("no paper, no key (I13)", projectJson("none").contains("\"paper\""))
        // A loaded paper document saves its paper again.
        val again = repo.load("cream")
        repo.save(again, null)
        assertEquals(PaperSpec(0xFFF4ECD8.toInt()), repo.load("cream").paper)
    }
}
