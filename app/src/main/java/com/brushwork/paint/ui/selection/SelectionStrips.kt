@file:Suppress("UNUSED_PARAMETER")

package com.brushwork.paint.ui.selection

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.ObjectSelectTool
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.tools.strip.StripSpec

// The selection tools' strips (v1.8, design §3.9 and §4.6; area C converts them). Foundation
// stubs: the v1.7 strips; Magnetic lasso and Color select (new tools) show nothing yet.

@Composable
internal fun fillStrip(tool: FillTool, c: EditorController): StripSpec = StripSpec.legacy { FillOptions(tool) }

@Composable
internal fun eyedropperStrip(tool: EyedropperTool, c: EditorController): StripSpec = StripSpec.legacy { EyedropperOptions(tool) }

@Composable
internal fun magicWandStrip(tool: MagicWandTool, c: EditorController): StripSpec = StripSpec.legacy { MagicWandOptions(tool) }

@Composable
internal fun lassoStrip(tool: LassoTool, c: EditorController): StripSpec = StripSpec.legacy { LassoOptions(tool) }

@Composable
internal fun marqueeStrip(tool: MarqueeTool, c: EditorController): StripSpec = StripSpec.legacy { MarqueeOptions(tool) }

@Composable
internal fun objectSelectStrip(tool: ObjectSelectTool, c: EditorController): StripSpec = StripSpec.legacy { ObjectSelectOptions(tool) }

@Composable
internal fun magneticLassoStrip(tool: MagneticLassoTool, c: EditorController): StripSpec = StripSpec.EMPTY

@Composable
internal fun colorSelectStrip(tool: ColorSelectTool, c: EditorController): StripSpec = StripSpec.EMPTY
