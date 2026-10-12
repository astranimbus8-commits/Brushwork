package com.brushwork.paint.storage

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.PaperSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v1.9 F1a (R15, design 2.3.3): [PaperSpec] round-trips through `project.json`
 * ([ProjectFileDto.paper]) and the exchange payload ([BrushworkPayload.paper]) in each of its
 * forms. A new [Document] has no paper. JVM (the repository's copy is
 * `PaperRepositoryRobolectricTest`).
 */
class PaperV19Test {
    @Test
    fun theDefaultsAreWhiteAndNone() {
        assertEquals(0xFFFFFFFF.toInt(), PaperSpec().color)
        assertNull(Document("d", "d", 10, 10).paper)
        assertNull(ProjectFileDto(id = "p", name = "P", width = 10, height = 10).paper)
        assertNull(BrushworkPayload(width = 10, height = 10).paper)
    }

    @Test
    fun aPaperRoundTripsThroughProjectJson() {
        for (paper in listOf(PaperSpec(), PaperSpec(0xFFF4ECD8.toInt()), null)) {
            val dto = ProjectFileDto(id = "p", name = "P", width = 10, height = 10, paper = paper)
            val json = ProjectFormat.json.encodeToString(ProjectFileDto.serializer(), dto)
            assertEquals("$paper", dto, ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), json))
        }
    }

    @Test
    fun aPaperRoundTripsThroughThePayload() {
        for (paper in listOf(PaperSpec(), PaperSpec(0xFF102030.toInt()), null)) {
            val p = BrushworkPayload(width = 64, height = 48, paper = paper)
            assertEquals("$paper: JSON", paper, Payload.fromJson(Payload.toJson(p)).paper)
            assertEquals("$paper: deflated", paper, Payload.fromDeflated(Payload.deflated(p)).paper)
            assertEquals("$paper: base64", paper, Payload.fromBase64(Payload.toBase64(p)).paper)
            assertEquals("$paper: the version stays 1", 1, Payload.fromJson(Payload.toJson(p)).version)
        }
    }
}
