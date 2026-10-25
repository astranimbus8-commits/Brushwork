package com.brushwork.paint.ui.engine

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.check.CheckNotices
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.10 (F1, J32, design 2.11; signature frozen, A10 body): the gallery's engine notice (shown
 * once, an OK button only, which calls [onOk]), drawn by `GalleryNotices` for
 * `GalleryNotice.Engine`. F1: nothing (no notice is ever raised before A10).
 *
 * A10: a card like the crash card, full width, with [noticeText] (one text per reason, each ending
 * with `EngineLabels110.SETTINGS_PATH`) and one button. The notice counts as shown when the card
 * first draws it ([CheckNotices.markShown]), so it never comes back, OK or not.
 */
@Composable
fun EngineNoticeCard(notice: EngineNotice, onOk: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(notice) { CheckNotices.markShown(context.applicationContext, notice) }
    Surface(
        color = BrushworkColors.ChromeHigh,
        contentColor = BrushworkColors.OnChrome,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(noticeText(notice), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = onOk,
                    modifier = Modifier.heightIn(min = 40.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("OK", color = BrushworkColors.Accent)
                }
            }
        }
    }
}

/**
 * The notice's text (design 2.11 and 2.12): Classic for memory, Classic for slow compositing, TILES
 * (low or slow storage), or the low-memory closings.
 */
internal fun noticeText(notice: EngineNotice): String = when (notice) {
    EngineNotice.LowMemoryClosings -> EngineLabels110.LMK_NOTICE
    is EngineNotice.CheckChose -> when {
        notice.level == EngineLevel.TILES -> EngineLabels110.CHECK_TILES
        notice.reason == CheckReasons.SLOW_COMPOSITING -> EngineLabels110.CHECK_CLASSIC_SLOW
        else -> EngineLabels110.CHECK_CLASSIC_MEMORY
    }
}
