package com.brushwork.paint.ui.engine

import androidx.compose.runtime.Composable
import com.brushwork.paint.engine.EngineNotice

/**
 * v1.10 (F1, J32, design 2.11; signature frozen, A10 body): the gallery's engine notice (shown
 * once, an OK button only, which calls [onOk]), drawn by `GalleryNotices` for
 * `GalleryNotice.Engine`. F1: nothing (no notice is ever raised before A10).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun EngineNoticeCard(notice: EngineNotice, onOk: () -> Unit) {}
