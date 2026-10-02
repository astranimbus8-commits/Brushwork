package com.brushwork.paint.tools.text.frames

import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditListener
import com.brushwork.paint.EditorController
import com.brushwork.paint.LayerListEvent
import com.brushwork.paint.LayerListListener
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.text.TextCodec

/**
 * Keeps linked text stories whole (v1.6, §3.6c; area D): `controller.textThreads`, created and
 * registered by the controller at init as an [EditListener] AND a [LayerListListener], so it works
 * before the Text frames tool exists.
 *
 * The contract D implements behind this stub:
 * - REMOVED / MERGED frame layers, a CONTENT edit that cleared a frame's `textData`, or a change of
 *   a frame's wrap source: the story re-flows into the frames left, INSIDE the triggering step
 *   ([EditorController.amendLastStep], I2). Text is never lost while one frame remains.
 * - DUPLICATED frame layers: the copy becomes an unlinked plain fixed-box text of its slice, in
 *   the duplicate's step.
 * - Its own re-flows (label "Re-flow text") and frames whose computed item equals the stored one
 *   are ignored, so delivery converges within 2 of the controller's 4 rounds.
 * - Undo and redo never re-flow (the controller doesn't report them).
 *
 * Foundation stub: listens and does nothing; only [isFrame] is real.
 */
class TextThreads(private val c: EditorController) : EditListener, LayerListListener {
    override fun onEdited(e: EditEvent) {}

    override fun onLayerList(e: LayerListEvent) {}

    /**
     * Re-flows story [storyId] into its frames inside the current step ([EditorController.amendLastStep]);
     * false when nothing changed. Frames whose wrap outline is stale first get their picture's
     * current outline (`c.textWrap.contours`): `TextWrapReflow` hands every re-traced frame
     * here instead of re-rendering it alone. Stub: false.
     */
    fun reflowStory(storyId: Long): Boolean = false

    /**
     * Switches to the Text frames tool with frame [layer] selected (its story editor open when
     * [openEditor]); false if [layer] is not a frame. The Text tool's `editLayer` and the layer
     * window's "Edit text" call it first. Stub: false.
     */
    fun openForEditing(layer: Layer, openEditor: Boolean): Boolean = false

    /** Real: [layer]'s text data is a threaded item (a frame of a linked story). */
    fun isFrame(layer: Layer): Boolean = TextCodec.decode(layer.textData)?.thread?.isOn == true
}
