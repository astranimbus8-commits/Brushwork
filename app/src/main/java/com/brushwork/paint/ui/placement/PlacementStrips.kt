@file:Suppress("UNUSED_PARAMETER")

package com.brushwork.paint.ui.placement

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.tools.strip.StripSpec

// The Transform, Text and Frame divider strips (v1.8, design §3.9 and §4.6; area G): each
// builder returns its converted spec (Transform: [transformSpec]).

@Composable
internal fun transformStrip(tool: TransformTool, c: EditorController): StripSpec = transformSpec(tool)

@Composable
internal fun textStrip(tool: TextTool, c: EditorController): StripSpec = StripSpec.legacy { TextToolOptions(tool) }

@Composable
internal fun frameDividerStrip(tool: FrameDividerTool, c: EditorController): StripSpec = StripSpec.legacy { FrameDividerOptions(tool) }
