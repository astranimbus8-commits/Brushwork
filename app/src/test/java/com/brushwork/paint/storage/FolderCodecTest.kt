package com.brushwork.paint.storage

import com.brushwork.paint.model.FolderSpec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 F1 (item 8, §4.2): a folder's `passThrough` is written even at its default (the I13
 * exception: `FolderSpec` exists only in format-3 files, and a later change of the default must
 * never silently change a saved folder), whatever the Json's `encodeDefaults`; the folder fields of
 * a layer entry are written only for folders and children.
 */
class FolderCodecTest {
    private val noDefaults = Json { encodeDefaults = false }

    @Test
    fun passThroughIsWrittenAtItsDefault() {
        assertEquals("{\"passThrough\":true}", ProjectFormat.json.encodeToString(FolderSpec.serializer(), FolderSpec()))
        assertEquals("{\"passThrough\":true}", noDefaults.encodeToString(FolderSpec.serializer(), FolderSpec()))
        assertEquals("{\"passThrough\":false}", noDefaults.encodeToString(FolderSpec.serializer(), FolderSpec(passThrough = false)))
        // Older or hand-written data without the field reads as the default.
        assertEquals(FolderSpec(), ProjectFormat.json.decodeFromString(FolderSpec.serializer(), "{}"))
        assertEquals(FolderSpec(passThrough = false), ProjectFormat.json.decodeFromString(FolderSpec.serializer(), "{\"passThrough\":false,\"later\":1}"))
    }

    @Test
    fun folderFieldsAreWrittenOnlyWhenUsed() {
        val props = ProjectFormat.defaultProps("Folder 1")
        val plain = ProjectFormat.json.encodeToString(LayerEntryDto.serializer(), LayerEntryDto(1, "Layer 1", props, file = "layer_1_r1.bin"))
        for (k in listOf("parentId", "folder", "folderOpen", "array")) assertFalse(k, plain.contains("\"$k"))
        val folder = LayerEntryDto(2, "Folder 1", props, file = "", parentId = 5, folder = FolderSpec(), folderOpen = false)
        val text = ProjectFormat.json.encodeToString(LayerEntryDto.serializer(), folder)
        assertTrue(text, text.endsWith(",\"parentId\":5,\"folder\":{\"passThrough\":true},\"folderOpen\":false}"))
        assertEquals(folder, ProjectFormat.json.decodeFromString(LayerEntryDto.serializer(), text))
        val open = ProjectFormat.json.encodeToString(LayerEntryDto.serializer(), folder.copy(parentId = 0, folderOpen = true))
        assertTrue(open, open.endsWith(",\"folder\":{\"passThrough\":true}}"))
    }
}
