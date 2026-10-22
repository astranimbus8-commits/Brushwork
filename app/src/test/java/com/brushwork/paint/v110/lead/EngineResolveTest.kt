package com.brushwork.paint.v110.lead

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.storage.engine.EngineCheckResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.10, J13, J15, I31, I32; lead, frozen): [EngineConfig.resolve] follows the
 * section 2.10 table (another phone's check, or none, makes CLASSIC and FULL act as AUTOMATIC;
 * unknown memory is always CLASSIC), [EngineConfig.deviceKey] rounds `totalMem` to 256 MiB,
 * [EngineConfig.openSession] fixes the level, flags, budgets, `LayerBudget.current` and residency
 * for the session (F1: [ClassicResidency] at every level outside test mode), [EngineConfig.closeSession]
 * puts the gallery's back (twice is once), and [EngineConfig.applyIdle] sets the gallery's
 * budgets, applying them at once only between sessions.
 */
@RunWith(RobolectricTestRunner::class)
class EngineResolveTest {
    private val fields = listOf("idleLayerBudget", "idleBudgets", "budget")
    private val saved = HashMap<String, Any?>()
    private lateinit var savedCurrent: LayerBudget

    private fun field(name: String) = EngineConfig::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before
    fun setUp() {
        EngineConfig.closeSession()
        for (f in fields) saved[f] = field(f).get(null)
        savedCurrent = LayerBudget.current
    }

    @After
    fun tearDown() {
        System.clearProperty("brushwork.engineMode")
        EngineConfig.closeSession()
        for (f in fields) field(f).set(null, saved[f])
        LayerBudget.current = savedCurrent
        EngineConfig.residency = ClassicResidency
    }

    private fun check(level: EngineLevel, key: String = KEY, below: Int? = null, above: Boolean = false) = EngineCheckResult(
        appVersionCode = 110, deviceKey = key, ranAtMillis = 0L, durationMs = 0L, level = level, reason = "",
        totalMem = 8L shl 30, availMem = 4L shl 30, threshold = 0L, lowRam = false, is64Bit = true, javaMax = 512L shl 20,
        nativeProbeBytes = 0L, deflateMBs = 0f, inflateMBs = 0f, ratio = 0f, writeMBs = 0f, readMBs = 0f, freeBytes = 0L,
        composite64Ms = 0f, belowCacheMinLayers = below, aboveProjection = above,
    )

    private fun resolve(mode: LayerStorageMode, check: EngineCheckResult?, free: Long = 64L shl 30, known: Boolean = true) =
        EngineConfig.resolve(mode, check, KEY, free, known)

    @Test
    fun theResolveTable() {
        val modes = LayerStorageMode.entries
        // Unknown memory (I32): CLASSIC whatever the mode and the check.
        for (m in modes) for (c in listOf(null, check(EngineLevel.FULL), check(EngineLevel.TILES, OTHER))) {
            assertEquals("unknown memory, $m", EngineLevel.CLASSIC, resolve(m, c, known = false))
        }
        // This phone's check.
        val ownFull = check(EngineLevel.FULL)
        val ownTiles = check(EngineLevel.TILES)
        val ownClassic = check(EngineLevel.CLASSIC)
        assertEquals(EngineLevel.CLASSIC, resolve(LayerStorageMode.CLASSIC, ownFull))
        assertEquals(EngineLevel.FULL, resolve(LayerStorageMode.FULL, ownClassic))
        assertEquals(EngineLevel.FULL, resolve(LayerStorageMode.FULL, ownTiles, free = 2L shl 30))
        assertEquals("FULL under 2 GiB free at open", EngineLevel.TILES, resolve(LayerStorageMode.FULL, ownFull, free = (2L shl 30) - 1))
        assertEquals(EngineLevel.FULL, resolve(LayerStorageMode.AUTOMATIC, ownFull))
        assertEquals(EngineLevel.TILES, resolve(LayerStorageMode.AUTOMATIC, ownTiles, free = 0L))
        assertEquals(EngineLevel.CLASSIC, resolve(LayerStorageMode.AUTOMATIC, ownClassic))
        // Another phone's check: CLASSIC and FULL are treated as AUTOMATIC (its level).
        val otherTiles = check(EngineLevel.TILES, OTHER)
        for (m in modes) assertEquals("another phone's check, $m", EngineLevel.TILES, resolve(m, otherTiles))
        assertEquals(EngineLevel.CLASSIC, resolve(LayerStorageMode.FULL, check(EngineLevel.CLASSIC, OTHER)))
        // No check: AUTOMATIC with no check is CLASSIC.
        for (m in modes) assertEquals("no check, $m", EngineLevel.CLASSIC, resolve(m, null))
    }

    @Test
    fun theDeviceKeyRoundsTotalMemTo256MiB() {
        val mib = 1L shl 20
        assertEquals("Pixel 7/8192MiB", EngineConfig.deviceKey("Pixel 7", 8192 * mib))
        assertEquals("Pixel 7/8192MiB", EngineConfig.deviceKey("Pixel 7", 8192 * mib - 127 * mib))
        assertEquals("Pixel 7/7936MiB", EngineConfig.deviceKey("Pixel 7", 8192 * mib - 129 * mib))
        assertEquals("Pixel 7/8192MiB", EngineConfig.deviceKey("Pixel 7", 8192 * mib + 127 * mib))
        assertEquals("A phone reporting a little less than 4 GiB", "SM-A105/3584MiB", EngineConfig.deviceKey("SM-A105", 3_890_000_000L))
        assertEquals("X/0MiB", EngineConfig.deviceKey("X", 0L))
    }

    @Test
    fun aSessionFixesTheEngineAndClosingPutsTheGallerysBack() {
        assumeTrue("outside test mode", EngineConfig.testMode() == null)
        val idle = field("idleLayerBudget").get(null)
        val ledger = EngineConfig.budget.ledger
        val budgets = Budgets(512L shl 20, 1L shl 30, known = true, stepDown = 0)

        EngineConfig.openSession(EngineLevel.TILES, budgets, check(EngineLevel.TILES, below = 8, above = true))
        assertEquals(EngineLevel.TILES, EngineConfig.level)
        assertEquals(budgets, EngineConfig.budget.budgets)
        assertSame("one ledger for the process", ledger, EngineConfig.budget.ledger)
        assertNull("the below-cache threshold is Classic's", EngineConfig.strokeBelowMinLayers)
        assertTrue(EngineConfig.aboveProjection)
        assertFalse(EngineConfig.swap)
        assertTrue(LayerBudget.current is LayerBudgetV110)
        assertSame("F1: Classic residency outside test mode", ClassicResidency, EngineConfig.residency)
        assertSame(NoSwap, EngineConfig.swapStore)

        EngineConfig.openSession(EngineLevel.CLASSIC, budgets, check(EngineLevel.CLASSIC, below = 8, above = true))
        assertEquals(EngineLevel.CLASSIC, EngineConfig.level)
        assertEquals(8, EngineConfig.strokeBelowMinLayers)
        assertFalse("no projection in Classic", EngineConfig.aboveProjection)

        EngineConfig.openSession(EngineLevel.FULL, budgets, null)
        assertTrue(EngineConfig.swap)
        assertFalse("no check: no projection", EngineConfig.aboveProjection)

        EngineConfig.closeSession()
        assertEquals(EngineLevel.CLASSIC, EngineConfig.level)
        assertNull(EngineConfig.strokeBelowMinLayers)
        assertFalse(EngineConfig.aboveProjection)
        assertFalse(EngineConfig.swap)
        assertSame(idle, LayerBudget.current)
        assertSame(ClassicResidency, EngineConfig.residency)
        assertSame(ledger, EngineConfig.budget.ledger)
        val after = EngineConfig.budget
        EngineConfig.closeSession()
        assertSame("closing twice is closing once", after, EngineConfig.budget)
        assertSame(idle, LayerBudget.current)
    }

    @Test
    fun applyIdleAppliesBetweenSessionsOnly() {
        assumeTrue("outside test mode", EngineConfig.testMode() == null)
        val gallery = Budgets(256L shl 20, 512L shl 20, known = true, stepDown = 1)
        EngineConfig.applyIdle(LayerStorageMode.AUTOMATIC, check(EngineLevel.TILES), gallery)
        assertEquals(gallery, EngineConfig.budget.budgets)
        val idle = LayerBudget.current
        assertTrue(idle is LayerBudgetV110)

        val session = Budgets(512L shl 20, 1L shl 30, known = true, stepDown = 0)
        EngineConfig.openSession(EngineLevel.TILES, session, null)
        val inSession = LayerBudget.current
        val later = Budgets(128L shl 20, 256L shl 20, known = false, stepDown = 0)
        EngineConfig.applyIdle(LayerStorageMode.CLASSIC, null, later)
        assertEquals("a session's budgets stay", session, EngineConfig.budget.budgets)
        assertSame(inSession, LayerBudget.current)

        EngineConfig.closeSession()
        assertEquals("the gallery's latest budgets", later, EngineConfig.budget.budgets)
        assertTrue(LayerBudget.current is LayerBudgetV110)
        assertFalse(LayerBudget.current === idle)
    }

    @Test
    fun theTestModeSetsTheLevelAndTheChaosResidency() {
        System.setProperty("brushwork.engineMode", "tiles")
        assertEquals(EngineLevel.TILES, EngineConfig.testMode())
        EngineConfig.openSession(EngineLevel.CLASSIC, Budgets.v19(512L shl 20), null)
        assertEquals("the test level wins", EngineLevel.TILES, EngineConfig.level)
        assertTrue(EngineConfig.residency is ChaosResidency)
        System.setProperty("brushwork.engineMode", " Classic ")
        assertEquals(EngineLevel.CLASSIC, EngineConfig.testMode())
        System.setProperty("brushwork.engineMode", "nonsense")
        assertNull(EngineConfig.testMode())
        System.clearProperty("brushwork.engineMode")
        EngineConfig.closeSession()
        assertEquals(EngineLevel.CLASSIC, EngineConfig.level)
        assertSame(ClassicResidency, EngineConfig.residency)
    }

    private companion object {
        const val KEY = "Pixel 7/8192MiB"
        const val OTHER = "SM-A105/3584MiB"
    }
}
