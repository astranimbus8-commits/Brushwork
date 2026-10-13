package com.brushwork.paint.tools.text

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.9 (F2, design 2.5, rule T, I24): only `tools/text/TextCodec.kt` reads or writes a text
 * layer's data through the one-text codec calls; every other main source goes through
 * `TextCodec.decodeAll` / `encodeAll`, so no reader drops a layer's second and later texts.
 * The pattern is the ownership check's own (ownership-check-v19.ps1, rule T).
 */
class TextCodecGateTest {

    private val oneText = Regex("""TextCodec\.(decode|encode)\(""")

    @Test
    fun onlyTextCodecUsesTheOneTextCalls() {
        val root = mainSources()
        assertNotNull("main sources not found from ${System.getProperty("user.dir")}", root)
        val codec = File(root, "tools/text/TextCodec.kt").canonicalFile
        assertTrue("TextCodec.kt not found under $root", codec.isFile)
        val hits = ArrayList<String>()
        var files = 0
        root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            files++
            if (f.canonicalFile == codec) return@forEach
            f.readLines().forEachIndexed { i, line ->
                if (oneText.containsMatchIn(line)) hits += "${f.relativeTo(root).invariantSeparatorsPath}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("too few main sources scanned ($files)", files > 100)
        assertTrue("one-text TextCodec calls outside TextCodec.kt (use decodeAll / encodeAll):\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun thePatternMatchesTheOneTextCallsOnly() {
        assertTrue(oneText.containsMatchIn("val item = TextCodec.decode(data)"))
        assertTrue(oneText.containsMatchIn("val json = TextCodec.encode(item)"))
        assertTrue(!oneText.containsMatchIn("val items = TextCodec.decodeAll(data)"))
        assertTrue(!oneText.containsMatchIn("val json = TextCodec.encodeAll(listOf(item))"))
    }

    private fun mainSources(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        return null
    }
}
