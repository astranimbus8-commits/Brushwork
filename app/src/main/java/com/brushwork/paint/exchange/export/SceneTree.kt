package com.brushwork.paint.exchange.export

/**
 * v1.7 (item 8): the pictures of [layers] and of their children at every level (each mask's
 * picture and every picture item), for the writers' progress.
 */
internal fun sceneImageCount(layers: List<SceneLayer>): Int =
    layers.sumOf { l -> (if (l.mask?.image != null) 1 else 0) + l.items.count { it is SceneItem.Image } + sceneImageCount(l.children) }

/**
 * v1.7 (item 8): every picture of the scene, the folders' layers included (the payload's own
 * pictures too). [ExportScene.imageCount] counts the top level only.
 */
internal val ExportScene.treeImageCount: Int get() = payloadImages.size + sceneImageCount(layers)
