package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.FrameFixtures.linkFrame
import com.brushwork.paint.tools.text.frames.FrameFixtures.newFrame
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.6 final QA, I4 / I8 the other way round: a project using every new v1.6 datum but no
 * adjustment layer (a Path-tool curve, a linked story in two frames, a text with letter scaling)
 * is written as format 1, so v1.5.0 opens it. The project and its flattened picture are also
 * left in `build/qa16-v16project` for the v1.5 reference build (`V16ProjectInV15Test` there):
 * v1.5.0 loads it without warnings and draws the same picture (the vector layer from its Bézier
 * form, the texts from their stored pixels).
 */
@RunWith(RobolectricTestRunner::class)
class V16ProjectForV15Test {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun aV16ProjectWithoutAdjustmentsIsFormat1() {
        val s = FrameFixtures.setup(app)
        val c = s.c
        val f1 = newFrame(s, 20f, 20f, 180f, 120f)
        linkFrame(s, f1, 220f, 20f, 380f, 120f)
        // A text with letter scaling.
        c.selectLayer(s.layer2)
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(200f, 200f)
        text.setText("ELTON JOHN")
        text.setSizePx(40f)
        text.updateSpec { it.copy(letterScale = LetterScaleSpec(smallestPercent = 50f)) }
        text.commit()
        // A Path-tool curve on a vector layer.
        c.addVectorLayer()
        assertTrue(c.activeLayer.isVectorLayer)
        val path = c.tool(ToolId.PATH)
        for ((x, y) in listOf(40f to 280f, 120f to 230f, 220f to 290f, 330f to 240f)) c.tap(x, y)
        path.commit()
        assertTrue(Smoke.pumpUntil { !c.vectors.isRendering })
        assertTrue("a Path curve", (c.activeLayer.vector ?: VectorContent.EMPTY).objects.filterIsInstance<VPath>().single().spline != null)
        assertEquals(1, ProjectFormat.writtenVersion(c.doc.layers))
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        val dir = File(app.filesDir, "projects/${c.doc.id}")
        val json = Json.parseToJsonElement(File(dir, "project.json").readText()).jsonObject
        assertEquals("v1.5 opens it (I4)", 1, json.getValue("formatVersion").jsonPrimitive.int)
        // For the v1.5 reference build.
        val out = File("build/qa16-v16project").apply { deleteRecursively(); mkdirs() }
        dir.copyRecursively(File(out, "v16-project"), overwrite = true)
        File(out, "id.txt").writeText(c.doc.id)
        File(out, "flat.png").writeBytes(V15Fixtures.png(c.compositor.renderFlattened()))
    }
}
