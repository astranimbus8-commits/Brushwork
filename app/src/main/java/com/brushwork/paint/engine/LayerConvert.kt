package com.brushwork.paint.engine

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VectorContent

/**
 * v1.9 (design 2.4.7; owner A4; called by A4, A5 and A9): a legacy shape layer or an empty plain
 * layer becoming a vector layer in place, extracted from `EditorController.convertToVectorLayer`
 * (which keeps its signature, checks and messages and converts through [convert], so its result
 * and its undo step are those of v1.8). [toVectorAction] gives the same conversion to a caller
 * that folds it into its own step (S1 "Add shape", "Merge down", an erase on a shape layer).
 */
object LayerConvert {

    /**
     * The vector content equal to a legacy shape layer (one VShape with its style and opacity;
     * v1.8 I15: its live copies stay its copies); null for any other layer or a shape that does not
     * decode. Pure.
     */
    fun shapeToVector(layer: Layer): VectorContent? =
        if (!layer.isShapeLayer) null else EditorController.shapeLayerVector(layer)

    /**
     * Converts [layer] in place without an undo step and returns the action that undoes it, for the
     * caller's own step (S1 "Add shape", "Merge down", an erase on a shape layer). The name changes
     * only when [name] is given. Null (nothing changed) when [layer] is not in the document, is
     * already a vector layer, or is neither a shape layer that decodes nor an empty plain layer
     * (`EditorController.isEmptyPlainLayer`). Whether the layer may be edited (locked, hidden) is
     * the caller's check, as is committing pending tool work first. [label] names the inner
     * actions (v1.8: the caller's step label). Call it inside the caller's `editScope` (or
     * `groupUndo`), so the layer's edit event is delivered with the caller's step.
     */
    fun toVectorAction(
        c: EditorController,
        layer: Layer,
        name: String? = null,
        label: String = "Convert to vector layer",
    ): UndoAction? {
        if (c.doc.indexOf(layer) < 0 || layer.isVectorLayer) return null
        val content = when {
            layer.isShapeLayer -> shapeToVector(layer) ?: return null
            c.isEmptyPlainLayer(layer) -> VectorContent.EMPTY
            else -> return null
        }
        val actions = ArrayList<UndoAction>(2)
        convert(c, layer, content, name, label) { actions += it }
        return when (actions.size) {
            0 -> null
            1 -> actions[0]
            else -> CompositeAction(label, actions)
        }
    }

    /**
     * The conversion itself, as v1.8's `convertToVectorLayer` did it inside its `groupUndo`: the
     * rename to [name] (when given) as a layer-properties action, then [content] replacing the
     * text and shape data as a data action (an edit of the layer's content). Each action goes to
     * [record] in that order (`EditorController.pushUndo` there: the same two pushes as v1.8).
     */
    internal fun convert(
        c: EditorController,
        layer: Layer,
        content: VectorContent,
        name: String?,
        label: String,
        record: (UndoAction) -> Unit,
    ) {
        if (name != null) c.applyLayerProps(layer, layer.props().copy(name = name), label, record)
        val before = layer.dataSnapshot()
        val after = before.copy(text = null, shape = null, vector = content)
        // v1.8 (setLayerData): a data edit whose mask spec is unchanged is a CONTENT edit.
        c.storeData(layer, before, after, label, EditTarget.CONTENT, record)
    }
}
