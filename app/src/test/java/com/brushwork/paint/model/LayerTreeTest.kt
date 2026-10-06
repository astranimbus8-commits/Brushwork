package com.brushwork.paint.model

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 F1 (I11): [LayerTree] over flat layer lists. Every list of up to 6 layers with up to 2
 * folders and every parent assignment is checked against an independent reference (parents are
 * folders above their children, each folder's descendants are exactly the layers directly below
 * it, depth ≤ 8); every query and plan is checked on the valid ones, and [LayerTree.sanitize]
 * repairs every invalid one. (Robolectric: a [Layer] holds a Bitmap.)
 */
@RunWith(RobolectricTestRunner::class)
class LayerTreeTest {
    private val root = Layer.ROOT_ID
    private val px: Bitmap by lazy { BitmapUtils.createLayerBitmap(1, 1) }

    private fun layer(id: Long, parent: Long = root) = Layer(id, "L$id", px).apply { parentId = parent }
    private fun folder(id: Long, parent: Long = root) = Layer.newFolder(id, "F$id").apply { parentId = parent }

    // ------------------------------------------------------------------ the reference

    private fun indexById(ls: List<Layer>): Map<Long, Int> = ls.withIndex().associate { it.value.id to it.index }

    /** Parents nearest first, following ids (valid only when parents lie above). */
    private fun refAncestors(ls: List<Layer>, i: Int): List<Int> {
        val idx = indexById(ls)
        val out = ArrayList<Int>()
        var p = ls[i].parentId
        while (p != root) {
            val j = idx.getValue(p)
            out += j
            p = ls[j].parentId
        }
        return out
    }

    private fun refDescendants(ls: List<Layer>, f: Int): Set<Int> =
        ls.indices.filter { it != f && f in refAncestors(ls, it) }.toSet()

    private fun refValid(ls: List<Layer>): Boolean {
        val idx = indexById(ls)
        if (idx.size != ls.size) return false
        if (ls.count { it.isFolder } > LayerTree.MAX_FOLDERS) return false
        for ((i, l) in ls.withIndex()) {
            if (l.parentId == root) continue
            val p = idx[l.parentId] ?: return false
            if (!ls[p].isFolder || p <= i) return false
        }
        for ((f, l) in ls.withIndex()) {
            if (!l.isFolder) continue
            val d = refDescendants(ls, f)
            if (d != (f - d.size until f).toSet()) return false
            if (refAncestors(ls, f).size >= LayerTree.MAX_DEPTH) return false
        }
        return true
    }

    /** Every list of [n] layers (ids 1..n) with at most 2 folders and every parent assignment. */
    private fun allLists(n: Int): Sequence<List<Layer>> = sequence {
        for (mask in 0 until (1 shl n)) {
            if (Integer.bitCount(mask) > 2) continue
            val folderIds = (0 until n).filter { mask and (1 shl it) != 0 }.map { it + 1L }
            val choices = listOf(root) + folderIds
            val total = Math.pow(choices.size.toDouble(), n.toDouble()).toInt()
            for (code in 0 until total) {
                var c = code
                val ls = (0 until n).map { i ->
                    val parent = choices[c % choices.size]
                    c /= choices.size
                    val id = i + 1L
                    if (id in folderIds) folder(id, parent) else layer(id, parent)
                }
                yield(ls)
            }
        }
    }

    private fun applied(plan: LayerTree.Plan): List<Layer> {
        // Fresh layers so the plan's parents do not leak into the original list.
        return plan.order.mapIndexed { i, l -> (if (l.isFolder) folder(l.id) else layer(l.id)).apply { parentId = plan.parents[i] } }
    }

    private fun parentsOf(ls: List<Layer>) = ls.map { it.parentId }

    // ------------------------------------------------------------------ exhaustive

    @Test
    fun checkAgreesWithTheReferenceAndSanitizeRepairsEveryList() {
        var valid = 0
        var invalid = 0
        for (n in 1..6) for (ls in allLists(n)) {
            val ok = refValid(ls)
            assertEquals("check of ${describe(ls)}", ok, LayerTree.check(ls) == null)
            val copy = ls.toMutableList()
            val before = parentsOf(ls)
            val warnings = LayerTree.sanitize(copy)
            if (ok) {
                valid++
                assertTrue(warnings.isEmpty())
                assertEquals(ls, copy)
                assertEquals(before, parentsOf(copy))
            } else {
                invalid++
                assertEquals(listOf("The folder structure was repaired"), warnings)
                assertNull("sanitized ${describe(copy)}", LayerTree.check(copy))
                assertTrue(refValid(copy))
                assertEquals(ls.toSet(), copy.toSet())
            }
        }
        assertTrue("valid $valid, invalid $invalid", valid > 100 && invalid > 5000)
    }

    @Test
    fun queriesAgreeWithTheReferenceOnEveryValidTree() {
        for (n in 1..6) for (ls in allLists(n)) {
            if (!refValid(ls)) continue
            for (i in ls.indices) {
                val anc = refAncestors(ls, i)
                assertEquals(anc, LayerTree.ancestors(ls, i))
                assertEquals(anc.size, LayerTree.depth(ls, i))
                assertEquals(anc.firstOrNull() ?: -1, LayerTree.parentOf(ls, i))
                val d = if (ls[i].isFolder) refDescendants(ls, i).size else 0
                assertEquals(d, LayerTree.descendantCount(ls, i))
                assertEquals((i - d)..i, LayerTree.block(ls, i))
                if (ls[i].isFolder) {
                    val kids = ls.indices.filter { ls[it].parentId == ls[i].id }
                    assertEquals(kids, LayerTree.children(ls, i))
                } else {
                    assertTrue(LayerTree.children(ls, i).isEmpty())
                }
            }
            assertEquals(ls.indices.filter { ls[it].parentId == root }, LayerTree.children(ls, -1))
            // The top-level units tile the list, bottom first.
            val units = LayerTree.units(ls, ls.indices, root)
            assertEquals(ls.indices.toList(), units.flatMap { it.toList() })
        }
    }

    @Test
    fun movedBlockGivesValidTreesAndRefusesOnlyInvalidOnes() {
        var moves = 0
        for (n in 1..5) for (ls in allLists(n)) {
            if (!refValid(ls)) continue
            val parents = listOf(root) + ls.filter { it.isFolder }.map { it.id }
            for (from in ls.indices) for (to in ls.indices) for (np in parents) {
                val plan = LayerTree.movedBlock(ls, from, to, np)
                val b = LayerTree.block(ls, from)
                val size = b.last - b.first + 1
                // The candidate, built independently: the block out, then in so its top is at `to`.
                val moving = ls.subList(b.first, b.last + 1)
                val rest = ls.filterIndexed { i, _ -> i !in b }
                val top = to.coerceIn(size - 1, ls.lastIndex)
                val order = rest.subList(0, top - size + 1) + moving + rest.subList(top - size + 1, rest.size)
                val cand = order.map { l -> (if (l.isFolder) folder(l.id) else layer(l.id)).apply { parentId = if (l === ls[from]) np else l.parentId } }
                val intoItself = moving.any { it.id == np }
                if (intoItself || !refValid(cand)) {
                    assertNull("move $from -> $to into $np of ${describe(ls)}", plan)
                } else {
                    assertNotNull("move $from -> $to into $np of ${describe(ls)}", plan)
                    plan!!
                    assertEquals(order, plan.order)
                    assertEquals(top, plan.active)
                    assertSame(ls[from], plan.order[plan.active])
                    assertEquals(parentsOf(cand), plan.parents.toList())
                    moves++
                }
                // The original list is never touched.
                assertNull(LayerTree.check(ls))
            }
        }
        assertTrue(moves > 1000)
    }

    @Test
    fun putIntoFolderAboveAndTakeOutAreInverses() {
        var puts = 0
        for (n in 1..6) for (ls in allLists(n)) {
            if (!refValid(ls)) continue
            for (i in ls.indices) {
                val above = ls.indices.firstOrNull { f ->
                    ls[f].isFolder && ls[f].parentId == ls[i].parentId && LayerTree.block(ls, f).first == i + 1
                }
                val put = LayerTree.putIntoFolderAbove(ls, i)
                if (above == null) {
                    assertNull("put $i of ${describe(ls)}", put)
                } else {
                    val cand = ls.mapIndexed { k, l -> (if (l.isFolder) folder(l.id) else layer(l.id)).apply { parentId = if (k == i) ls[above].id else l.parentId } }
                    if (!refValid(cand)) {
                        assertNull(put)
                        continue
                    }
                    assertNotNull("put $i of ${describe(ls)}", put)
                    put!!
                    puts++
                    assertEquals(ls, put.order)
                    assertEquals(i, put.active)
                    assertEquals(parentsOf(cand), put.parents.toList())
                    // Swiping left again restores the tree: the layer is the folder's bottom child.
                    val after = applied(put)
                    val back = LayerTree.takeOutOfFolder(after, i)
                    assertNotNull(back)
                    assertEquals(parentsOf(ls), back!!.parents.toList())
                }
                val out = LayerTree.takeOutOfFolder(ls, i)
                val p = LayerTree.parentOf(ls, i)
                val isBottomChild = p >= 0 && LayerTree.block(ls, i).first == LayerTree.block(ls, p).first
                assertEquals("take out $i of ${describe(ls)}", isBottomChild, out != null)
                if (out != null) {
                    assertEquals(ls, out.order)
                    val cand = ls.mapIndexed { k, l -> (if (l.isFolder) folder(l.id) else layer(l.id)).apply { parentId = if (k == i) ls[p].parentId else l.parentId } }
                    assertTrue(refValid(cand))
                    assertEquals(parentsOf(cand), out.parents.toList())
                }
            }
        }
        assertTrue(puts > 100)
    }

    // ------------------------------------------------------------------ depth, count, repairs

    /** Folders 1..[k] each inside the one above (folder k is the deepest), plus a layer inside folder k. */
    private fun chain(k: Int): MutableList<Layer> {
        val out = ArrayList<Layer>()
        out += layer(100, k.toLong())
        for (id in k downTo 1) out += folder(id.toLong(), if (id == 1) root else id - 1L)
        return out
    }

    @Test
    fun foldersNestEightDeepButNotNine() {
        val eight = chain(8)
        assertNull(LayerTree.check(eight))
        assertEquals(8, LayerTree.depth(eight, 0))
        assertEquals(7, LayerTree.depth(eight, 1))
        // A ninth folder inside the deepest one is refused.
        val nine = chain(9)
        assertNotNull(LayerTree.check(nine))
        assertEquals(listOf("The folder structure was repaired"), LayerTree.sanitize(nine))
        assertNull(LayerTree.check(nine))
        // Folder 9 moved up to the deepest allowed level (beside folder 8, inside folder 7); its layer stays in it.
        assertEquals(7L, nine.single { it.id == 9L }.parentId)
        assertEquals(9L, nine.single { it.id == 100L }.parentId)
        assertTrue(nine.all { l -> LayerTree.depth(nine, nine.indexOf(l)) <= 8 })
        // Moving a folder into the deepest folder is refused; a plain layer may go there.
        val withFolder = chain(8).apply { add(0, folder(50, root)) }
        assertNull(LayerTree.check(withFolder))
        assertNull(LayerTree.movedBlock(withFolder, 0, 1, 8L))
        val withLayer = chain(8).apply { add(0, layer(50, root)) }
        assertNotNull(LayerTree.movedBlock(withLayer, 0, 1, 8L))
        // ...and so is swiping a folder into the deepest folder.
        val swipe = chain(8).apply { add(0, folder(50, 7L)) }
        assertNull(LayerTree.check(swipe))
        assertNull(LayerTree.putIntoFolderAbove(swipe, 0))
    }

    @Test
    fun atMostSixtyFourFolders() {
        val ls = MutableList(65) { folder(it + 1L) }
        assertEquals("65 folders (at most 64)", LayerTree.check(ls))
        assertNull(LayerTree.check(ls.subList(0, 64)))
        // The topmost extra folder is ungrouped: its child moves up a level, the entry goes.
        val m = (ls.subList(0, 64) + layer(200, 65L) + ls[64]).toMutableList()
        assertEquals(listOf("The folder structure was repaired"), LayerTree.sanitize(m))
        assertEquals(65, m.size)
        assertTrue(m.none { it.id == 65L })
        assertEquals(root, m.single { it.id == 200L }.parentId)
        assertNull(LayerTree.check(m))
    }

    @Test
    fun sanitizeBreaksCyclesAndForwardParents() {
        // Each folder names the other: the parent below the child goes.
        val cycle = mutableListOf(folder(1, 2), folder(2, 1))
        LayerTree.sanitize(cycle)
        assertEquals(listOf(1L, 2L), cycle.map { it.id })
        assertEquals(listOf(2L, root), parentsOf(cycle))
        // A layer naming itself, an unknown id and a raster layer go to the top level.
        val odd = mutableListOf(folder(1, 1), layer(2, 99), layer(3, 4), layer(4))
        LayerTree.sanitize(odd)
        assertEquals(listOf(root, root, root, root), parentsOf(odd))
        assertEquals(listOf(1L, 2L, 3L, 4L), odd.map { it.id })
    }

    @Test
    fun sanitizeMakesBlocksContiguousKeepingTheOrder() {
        // L1 in F3, but L2 (top level) sits between them.
        val ls = mutableListOf(layer(1, 3), layer(2), folder(3), layer(4))
        assertNotNull(LayerTree.check(ls))
        LayerTree.sanitize(ls)
        assertEquals(listOf(2L, 1L, 3L, 4L), ls.map { it.id })
        assertNull(LayerTree.check(ls))
    }

    @Test
    fun sanitizeLeavesListsWithoutFoldersAlone() {
        // v1.6 lists, even with a repeated id (I13: no new warning, no reorder).
        val ls = mutableListOf(layer(1), layer(2), layer(2), layer(3))
        val copy = ls.toList()
        assertEquals("duplicate layer id 2", LayerTree.check(ls))
        assertTrue(LayerTree.sanitize(ls).isEmpty())
        assertEquals(copy, ls)
    }

    // ------------------------------------------------------------------ the rest of the API

    @Test
    fun showsAsIsOnlyUnderPlainFolders() {
        val l = layer(1, 2)
        val f = folder(2)
        val ls = listOf(l, f, layer(3))
        assertTrue(LayerTree.showsAsIs(ls, 0))
        assertTrue(LayerTree.showsAsIs(ls, 2))
        f.opacity = 0.5f
        assertFalse(LayerTree.showsAsIs(ls, 0))
        f.opacity = 1f
        f.visible = false
        assertFalse(LayerTree.showsAsIs(ls, 0))
        assertFalse(LayerTree.shownByAncestors(ls, 0))
        assertTrue(LayerTree.shownByAncestors(ls, 1)) // its own eye is not read
        f.visible = true
        f.folder = FolderSpec(passThrough = false)
        assertFalse(LayerTree.showsAsIs(ls, 0))
        f.folder = FolderSpec()
        f.clipping = true
        assertFalse(LayerTree.showsAsIs(ls, 0))
        f.clipping = false
        ls[2].clipping = true // clips to the folder: the folder is a clip base
        assertFalse(LayerTree.showsAsIs(ls, 0))
        ls[2].clipping = false
        assertTrue(LayerTree.showsAsIs(ls, 0))
        f.locked = true
        assertTrue(LayerTree.lockedByAncestor(ls, 0))
        assertFalse(LayerTree.lockedByAncestor(ls, 1))
    }

    @Test
    fun unitsListTheSiblingBlocksOfALevel() {
        // L1 L2 in F3; F3 and L4 in F5; L6 at the top.
        val ls = listOf(layer(1, 3), layer(2, 3), folder(3, 5), layer(4, 5), folder(5), layer(6))
        assertNull(LayerTree.check(ls))
        assertEquals(listOf(0..4, 5..5), LayerTree.units(ls, ls.indices, root))
        assertEquals(listOf(0..2, 3..3), LayerTree.units(ls, 0..3, 5L))
        assertEquals(listOf(0..0, 1..1), LayerTree.units(ls, 0..1, 3L))
        assertEquals(listOf(2, 3), LayerTree.children(ls, 4))
    }

    @Test
    fun insertedPutsTheBlockTopInTheParent() {
        val ls = listOf(layer(1, 2), folder(2), layer(3))
        val block = listOf(layer(10, 11), folder(11, 77))
        val plan = LayerTree.inserted(ls, 1, block, 2L)
        assertEquals(listOf(1L, 10L, 11L, 2L, 3L), plan.order.map { it.id })
        assertEquals(listOf(2L, 11L, 2L, root, root), plan.parents.toList())
        assertEquals(2, plan.active)
        assertNull(LayerTree.check(applied(plan)))
        // The block's own layers are not touched by the plan.
        assertEquals(77L, block[1].parentId)
    }

    @Test
    fun documentEffectiveVisibilityAndLock() {
        val doc = Document("t", "T", 8, 8)
        val l = layer(1, 2)
        val f = folder(2)
        val top = layer(3)
        doc.layers += listOf(l, f, top)
        assertTrue(doc.hasFolders)
        assertEquals(2, doc.pixelLayerCount)
        assertEquals(listOf(l, top), doc.pixelLayers.toList())
        assertTrue(doc.effectiveVisible(l))
        f.visible = false
        assertFalse(doc.effectiveVisible(l))
        assertFalse(doc.effectiveVisible(f))
        assertTrue(doc.effectiveVisible(top))
        assertFalse(doc.effectiveLocked(l))
        f.locked = true
        assertTrue(doc.effectiveLocked(l))
        assertFalse(doc.effectiveLocked(top))
        top.locked = true
        assertTrue(doc.effectiveLocked(top))
    }

    @Test
    fun selectionIdsAreNeverReused() {
        val doc = Document("t", "T", 8, 8)
        assertEquals(1L, doc.newSelectionId())
        assertEquals(2L, doc.newSelectionId())
        doc.ensureNextSelectionIdAbove(10)
        assertEquals(11L, doc.newSelectionId())
        doc.ensureNextSelectionIdAbove(3)
        assertEquals(12L, doc.nextSelectionId)
    }

    @Test
    fun foldersShareOneImmutableBitmapThatIsNeverRecycled() {
        val a = Layer.newFolder(1, "A")
        val b = Layer.newFolder(2, "B", FolderSpec(passThrough = false))
        assertSame(Layer.FOLDER_BITMAP, a.bitmap)
        assertSame(a.bitmap, b.bitmap)
        assertFalse(Layer.FOLDER_BITMAP.isMutable)
        assertTrue(a.isFolder)
        assertFalse(b.folder!!.passThrough)
        assertTrue(a.folderOpen)
        a.recycleBitmaps()
        assertFalse(Layer.FOLDER_BITMAP.isRecycled)
        Layer.FOLDER_BITMAP.recycleUnlessShared()
        assertFalse(Layer.FOLDER_BITMAP.isRecycled)
        try {
            a.paintTarget
            throw AssertionError("a folder has no paint target")
        } catch (e: IllegalStateException) {
            // expected
        }
        val raster = layer(3).also { it.bitmap = BitmapUtils.createLayerBitmap(2, 2) }
        val pixels = raster.bitmap
        raster.recycleBitmaps()
        assertTrue(pixels.isRecycled)
    }

    private fun describe(ls: List<Layer>) = ls.joinToString(" ") { (if (it.isFolder) "F" else "L") + it.id + "^" + it.parentId }
}
