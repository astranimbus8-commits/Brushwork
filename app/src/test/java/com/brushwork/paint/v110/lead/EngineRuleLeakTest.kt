package com.brushwork.paint.v110.lead

import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.budget.AndroidExitInfo
import com.brushwork.paint.engine.budget.AndroidMemoryProbe
import com.brushwork.paint.engine.budget.AndroidStorageProbe
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.16 and 3.3; lead, frozen): [EngineRule] installs the level, probes, swap store
 * and strict facade it is given, puts everything back after the test (also after a failing one),
 * and fails the first test that finds state an earlier test left behind, naming it, then repairs
 * it so the next test passes. The methods run in name order: `a` and `b` leave nothing behind
 * through a rule, `c` leaks on purpose outside one, `d` is the next test the leak would hit.
 * Each test drives its rule by hand (never two rules nested, which the rule forbids).
 */
@RunWith(RobolectricTestRunner::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EngineRuleLeakTest {
    private fun run(rule: EngineRule, name: String, body: () -> Unit) {
        rule.apply(
            object : Statement() {
                override fun evaluate() = body()
            },
            Description.createTestDescription(EngineRuleLeakTest::class.java, name),
        ).evaluate()
    }

    /** The level a test sees without a rule: `-PengineMode`'s, else CLASSIC. */
    private val runLevel: EngineLevel get() = EngineConfig.testMode() ?: EngineLevel.CLASSIC

    private fun assertBaseline(where: String) {
        assertEquals("$where: level", runLevel, EngineConfig.level)
        assertSame("$where: memory probe", AndroidMemoryProbe, EngineConfig.memoryProbe)
        assertSame("$where: storage probe", AndroidStorageProbe, EngineConfig.storageProbe)
        assertSame("$where: exit info", AndroidExitInfo, EngineConfig.exitInfo)
        assertSame("$where: swap", NoSwap, EngineConfig.swapStore)
        assertFalse("$where: strict facade", PixelFacade.strict)
    }

    @Test
    fun a_installsEverythingItIsGivenAndPutsItBack() {
        val modeBefore = System.getProperty(EngineRule.MODE)
        val currentBefore = LayerBudget.current
        val memory = FakeMemoryProbe(MemoryPreset.T606)
        val storage = FakeStorageProbe()
        val exits = FakeExitInfo()
        val swap = FakeSwapStore()
        var ran = false
        run(EngineRule(EngineLevel.FULL, memory = memory, storage = storage, exits = exits, swap = swap, strict = true), "full") {
            ran = true
            assertEquals(EngineLevel.FULL, EngineConfig.level)
            assertEquals("full", System.getProperty(EngineRule.MODE))
            assertSame(memory, EngineConfig.memoryProbe)
            assertSame(storage, EngineConfig.storageProbe)
            assertSame(exits, EngineConfig.exitInfo)
            assertSame(swap, EngineConfig.swapStore)
            assertTrue("FULL under the rule parks like unit-full", EngineConfig.residency is ChaosResidency)
            assertTrue(LayerBudget.current is LayerBudgetV110)
            assertTrue(PixelFacade.strict)
        }
        assertTrue(ran)
        assertTrue("the rule's swap store is closed with its session", swap.closed)
        assertEquals("property", modeBefore, System.getProperty(EngineRule.MODE))
        assertSame("LayerBudget.current", currentBefore, LayerBudget.current)
        assertBaseline("after a FULL rule")
    }

    @Test
    fun b_classicUnderAnyRunLevelAndRestoredAfterAFailure() {
        try {
            run(EngineRule(EngineLevel.CLASSIC), "classic") {
                assertEquals(EngineLevel.CLASSIC, EngineConfig.level)
                assertSame("CLASSIC under the rule, whatever -PengineMode", ClassicResidency, EngineConfig.residency)
                EngineConfig.memoryProbe = FakeMemoryProbe(MemoryPreset.GB3)
                throw IllegalStateException("the test fails")
            }
            fail("the failure reaches the caller")
        } catch (e: IllegalStateException) {
            assertEquals("the test fails", e.message)
        }
        assertBaseline("after a failing CLASSIC rule")
    }

    @Test
    fun c_aLeakOutsideARuleFailsTheNextRuleOnceAndIsRepaired() {
        // Make sure the fork has its first record before the leak (an earlier test may already have made it).
        run(EngineRule(EngineLevel.TILES), "record") {}
        val leaked = FakeMemoryProbe(MemoryPreset.GB8)
        EngineConfig.memoryProbe = leaked
        var ran = false
        try {
            run(EngineRule(EngineLevel.TILES), "after the leak") { ran = true }
            fail("a leaked probe fails the next rule")
        } catch (e: AssertionError) {
            assertTrue(e.message, e.message!!.contains("EngineConfig.memoryProbe"))
            assertTrue(e.message, e.message!!.contains("after the leak"))
        }
        assertFalse("the test body does not run after a leak", ran)
        assertSame("repaired to the first record", AndroidMemoryProbe, EngineConfig.memoryProbe)
        run(EngineRule(EngineLevel.TILES), "after the repair") { ran = true }
        assertTrue("one leak fails one test", ran)
    }

    @Test
    fun d_theNextTestFindsTheForkAsRecorded() {
        assertBaseline("on entry")
        var ran = false
        run(EngineRule(EngineLevel.CLASSIC, memory = FakeMemoryProbe(MemoryPreset.UNKNOWN)), "next") { ran = true }
        assertTrue(ran)
        assertBaseline("after")
    }

    @Test
    fun e_anOpenEditorSessionIsNotALeak() {
        // A test that never closes its EditorSession leaves a LayerBudgetV110 over unknown memory: v1.9's numbers.
        val before = LayerBudget.current
        EngineConfig.openSession(runLevel, com.brushwork.paint.engine.budget.Budgets.v19(Runtime.getRuntime().maxMemory()), null)
        try {
            var ran = false
            run(EngineRule(EngineLevel.CLASSIC), "after an open session") { ran = true }
            assertTrue(ran)
        } finally {
            EngineConfig.closeSession()
            LayerBudget.current = before
        }
    }
}
