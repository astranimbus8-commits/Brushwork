package com.brushwork.paint.v110.lead

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 F1 (design 2.10, 2.12 and rule K; lead): the two engine records F1 adds are written as
 * rule K says, whatever the encoder's `encodeDefaults` (A10 writes them): every optional field of
 * [EngineCheckResult] and every field of [EngineState] only when it differs from its default
 * (`@EncodeDefault(NEVER)`), and [EngineCheckResult.schema] always, so a newer-schema file is told
 * apart from this one (section 2.10). Both round trip. JVM.
 */
class EngineRecordsEncodeTest {
    private val encoders = listOf(
        "encodeDefaults" to Json { encodeDefaults = true },
        "plain" to Json,
    )

    private val reader = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private fun result() = EngineCheckResult(
        appVersionCode = 110, deviceKey = "Pixel 7/8192MiB", ranAtMillis = 1L, durationMs = 2L, level = EngineLevel.TILES,
        reason = "slow or full storage", totalMem = 8L shl 30, availMem = 4L shl 30, threshold = 3L, lowRam = false,
        is64Bit = true, javaMax = 512L shl 20, nativeProbeBytes = 4L, deflateMBs = 5f, inflateMBs = 6f, ratio = 7f,
        writeMBs = 8f, readMBs = 9f, freeBytes = 10L, composite64Ms = 11f,
    )

    private val optional = listOf("belowCacheMinLayers", "aboveProjection", "stepDown", "lmkDeathsAt", "provisional", "m2Outcome", "inconclusiveRuns")

    @Test
    fun aCheckResultAtItsDefaultsWritesTheSchemaAndNoOptionalKey() {
        val r = result()
        for ((name, json) in encoders) {
            val text = json.encodeToString(EngineCheckResult.serializer(), r)
            val keys = json.parseToJsonElement(text).jsonObject
            assertEquals("$name: the schema is always written", 1, keys.getValue("schema").jsonPrimitive.int)
            for (k in optional) assertFalse("$name: $k at its default is not written", k in keys)
            assertTrue("$name: a required field is written", "deviceKey" in keys)
            assertEquals("$name: round trip", r, reader.decodeFromString(EngineCheckResult.serializer(), text))
        }
    }

    @Test
    fun aCheckResultWritesEveryOptionalFieldThatIsSet() {
        val r = result().copy(
            belowCacheMinLayers = 8, aboveProjection = true, stepDown = 1, lmkDeathsAt = listOf(5L, 6L),
            provisional = true, m2Outcome = "MARGIN_LIMITED", inconclusiveRuns = 2,
        )
        for ((name, json) in encoders) {
            val text = json.encodeToString(EngineCheckResult.serializer(), r)
            val keys = json.parseToJsonElement(text).jsonObject
            for (k in optional) assertTrue("$name: $k set is written", k in keys)
            assertEquals("$name: round trip", r, reader.decodeFromString(EngineCheckResult.serializer(), text))
        }
        // A newer file keeps its schema when read here, so the reader can refuse it.
        val newer = Json.encodeToString(EngineCheckResult.serializer(), result().copy(schema = 2))
        assertEquals(2, reader.decodeFromString(EngineCheckResult.serializer(), newer).schema)
    }

    @Test
    fun anEngineStateAtItsDefaultsWritesNothing() {
        for ((name, json) in encoders) {
            assertEquals("$name: defaults", "{}", json.encodeToString(EngineState.serializer(), EngineState()))
            val set = EngineState(engineCheckPending = true, lastExitSeen = 3L, slowSwapReads = true)
            val text = json.encodeToString(EngineState.serializer(), set)
            assertEquals("$name: three keys", setOf("engineCheckPending", "lastExitSeen", "slowSwapReads"), json.parseToJsonElement(text).jsonObject.keys)
            assertEquals("$name: round trip", set, reader.decodeFromString(EngineState.serializer(), text))
        }
    }
}
