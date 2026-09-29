package com.brushwork.paint.ui.filters

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.FilterSession

// STUB - replaced by the filter-UI module.
/** Categorized, searchable list of all filters; picking one calls controller.startFilter(). */
@Composable
fun FilterBrowser(controller: EditorController, onDismiss: () -> Unit) {}

/** Parameter controls + Apply/Cancel for the running filter session (bottom of the editor). */
@Composable
fun FilterSessionPanel(session: FilterSession, modifier: Modifier = Modifier) {}