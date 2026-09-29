package com.brushwork.paint.segmentation

/**
 * Class ids of the bundled scene parser (Autoseg-EdgeTPU, ADE20K reduced to 32 classes: the 31
 * most frequent SceneParsing classes in objectInfo150 order, everything else = 0) and the mapping
 * from [SmartTarget]s to class sets.
 */
object SceneClasses {
    const val COUNT = 32

    const val OTHER = 0
    const val WALL = 1
    const val BUILDING = 2
    const val SKY = 3
    const val TREE = 5
    const val WINDOWPANE = 9
    const val GRASS = 10
    const val PERSON = 13
    const val DOOR = 15
    const val PLANT = 18
    const val WATER = 22
    const val HOUSE = 26
    const val SEA = 27
    const val FIELD = 30

    /** Human-readable label per class id (debugging / logs). */
    val LABELS: List<String> = listOf(
        "other", "wall", "building", "sky", "floor", "tree", "ceiling", "road", "bed", "windowpane",
        "grass", "cabinet", "sidewalk", "person", "earth", "door", "table", "mountain", "plant", "curtain",
        "chair", "car", "water", "painting", "sofa", "shelf", "house", "sea", "mirror", "rug",
        "field", "armchair",
    )

    /** Classes that make up [target] (empty for SUBJECT/BACKGROUND, which are not scene classes). */
    fun classesFor(target: SmartTarget): IntArray = when (target) {
        SmartTarget.SKY -> intArrayOf(SKY)
        SmartTarget.NATURE -> intArrayOf(TREE, GRASS, PLANT, FIELD)
        SmartTarget.BUILDINGS -> intArrayOf(BUILDING, HOUSE)
        SmartTarget.PEOPLE -> intArrayOf(PERSON)
        SmartTarget.WATER -> intArrayOf(WATER, SEA)
        SmartTarget.SUBJECT, SmartTarget.BACKGROUND -> IntArray(0)
    }

    /**
     * Classes that belong to [target] only where they touch its primary classes: windows and
     * doors are part of a building facade, but not when they are seen indoors.
     */
    fun attachedClassesFor(target: SmartTarget): IntArray = when (target) {
        SmartTarget.BUILDINGS -> intArrayOf(WINDOWPANE, DOOR)
        else -> IntArray(0)
    }

    /** True if [target] is answered by the scene parser. */
    fun isSceneTarget(target: SmartTarget): Boolean = classesFor(target).isNotEmpty()

    /**
     * Binary mask (1f/0f) of [target] over a [w]x[h] class map. Attached classes (see
     * [attachedClassesFor]) are included per 8-connected component when any pixel of the
     * component is 8-adjacent to a primary-class pixel.
     */
    fun targetMask(classes: ByteArray, w: Int, h: Int, target: SmartTarget): FloatArray {
        require(classes.size == w * h)
        val primary = lookup(classesFor(target))
        val attached = lookup(attachedClassesFor(target))
        val out = FloatArray(w * h)
        var anyAttached = false
        for (i in classes.indices) {
            val c = classes[i].toInt() and 0xFF
            if (c < COUNT) {
                if (primary[c]) out[i] = 1f else if (attached[c]) anyAttached = true
            }
        }
        if (!anyAttached) return out
        val isAttached = BooleanArray(w * h) { val c = classes[it].toInt() and 0xFF; c < COUNT && attached[c] }
        val lab = Regions.label(isAttached, w, h, eightConnected = true)
        val touches = BooleanArray(lab.count + 1)
        for (y in 0 until h) for (x in 0 until w) {
            val id = lab.ids[y * w + x]
            if (id == 0 || touches[id]) continue
            loop@ for (dy in -1..1) {
                val ny = y + dy
                if (ny < 0 || ny >= h) continue
                for (dx in -1..1) {
                    val nx = x + dx
                    if (nx < 0 || nx >= w) continue
                    if (out[ny * w + nx] == 1f && lab.ids[ny * w + nx] == 0) { touches[id] = true; break@loop }
                }
            }
        }
        for (i in out.indices) {
            val id = lab.ids[i]
            if (id != 0 && touches[id]) out[i] = 1f
        }
        return out
    }

    private fun lookup(ids: IntArray): BooleanArray = BooleanArray(COUNT).also { t -> ids.forEach { t[it] = true } }
}
