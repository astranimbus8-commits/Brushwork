package com.brushwork.paint.vector.lift

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.transform.ObjectLiftProvider
import com.brushwork.paint.tools.transform.RefusingLiftProvider

/**
 * The Transform tool on vector layers (v1.5 §4.9, owned by A2): lifts objects (the object
 * selection, else what the pixel selection touches, else all) instead of pixels. Reached through
 * `VectorLayers.liftProvider`. Foundation (F1): [RefusingLiftProvider], so Transform lifts pixels
 * as on raster layers.
 */
object VectorLift {
    fun provider(c: EditorController): ObjectLiftProvider = RefusingLiftProvider
}
