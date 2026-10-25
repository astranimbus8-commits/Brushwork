package com.brushwork.paint.ui.engine

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.tiles.SlabSwapStore

/**
 * v1.10 (F1, J32, design 2.7 and 2.8; signature frozen, A10 body): the engine's chrome over the
 * editor (`EditorScreen`): the ring while a layer is made ready (`EditorController.activating`),
 * the low-storage message, the frame metrics. F1: nothing.
 *
 * A10 step 3: the session's swap store. Once the editor shows a session that runs at FULL, a
 * [SlabSwapStore] becomes `EngineConfig.swapStore` ([SlabSwapStore.installIfFull]; at CLASSIC and
 * TILES, with a store already installed, or under Robolectric nothing happens); the session's end
 * (`EngineConfig.closeSession`) closes it and deletes its directory. Installed here and not in
 * `EditorSession` (region `v110-A10 session`) until A11's merge (design 4.0, `$Sequential`).
 */
@Composable
fun EngineEditorChrome(controller: EditorController) {
    val context = LocalContext.current
    LaunchedEffect(controller) { SlabSwapStore.installIfFull(context.applicationContext) }
}
