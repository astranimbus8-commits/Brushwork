package com.brushwork.paint.ui.common

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalView

/**
 * v1.8 polish (request items 8 and 9): the view inside which an icon-only control shows its name
 * on a long press ([iconLongPressLabel]); null: nowhere. [IconLongPressScope] provides it: the
 * options strip's cells (row 1 and the More row, `StripHost`). A popup or a dialog opened from a
 * cell composes in another view: its controls show no bubble there (the bubble is placed by the
 * editor's root, which a popup's bounds are not relative to). A sheet raised from a cell is
 * composed by `SheetHost`, which resets it: no bubble there either.
 */
val LocalIconLongPressView = staticCompositionLocalOf<View?> { null }

/** [content] whose icon-only controls ([ToolIconButton], [iconLongPressLabel]) show their name on a long press. */
@Composable
fun IconLongPressScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalIconLongPressView provides LocalView.current, content = content)
}

/**
 * [longPressLabel] ([label] in a bubble, nothing fired) for an icon-only control inside an
 * [IconLongPressScope] and in that scope's own window; nothing in a popup or a dialog, nor
 * outside a scope.
 */
fun Modifier.iconLongPressLabel(label: String): Modifier = composed {
    val scope = LocalIconLongPressView.current
    if (scope != null && scope === LocalView.current) Modifier.longPressLabel(label) else Modifier
}
