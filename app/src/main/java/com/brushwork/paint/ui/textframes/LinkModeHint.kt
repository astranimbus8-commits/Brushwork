package com.brushwork.paint.ui.textframes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * The hint chip of link mode (v1.6, §3.6a; area D): an out-port is loaded, so the next frame
 * drawn — or the text box tapped — continues the story. "Cancel link" (or a tap on empty canvas)
 * unloads it. The button comes first: on a 360–392 dp phone the long hint runs past the strip's
 * edge, and only the hint may be cut.
 */
@Composable
fun LinkModeHint(onCancel: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StripButton(Icons.Filled.Close, "Cancel link", onClick = onCancel)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(BrushworkColors.AccentDim)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Filled.Link, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(LINK_HINT, style = MaterialTheme.typography.bodySmall, color = Color.White, maxLines = 1)
        }
    }
}

/** What link mode asks for (§3.6a). */
const val LINK_HINT = "Draw the next frame, or tap a text box"
