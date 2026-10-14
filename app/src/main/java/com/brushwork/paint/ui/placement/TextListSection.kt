package com.brushwork.paint.ui.placement

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.core.Units
import com.brushwork.paint.tools.text.ListMarkers
import com.brushwork.paint.tools.text.TextEditorHost
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.ui.common.LabeledSlider
import com.brushwork.paint.ui.common.NumberAdjust
import com.brushwork.paint.ui.common.NumberField
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.SliderTyping
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.fonts.FontPickerSheet
import com.brushwork.paint.ui.theme.BrushworkColors
import java.text.BreakIterator
import kotlin.math.roundToInt

/** v1.9 (R7): the six bullet chips' glyphs, in order (their descriptions are [BULLET_DESCRIPTIONS]). */
internal val BULLET_GLYPHS: List<String> = listOf("•", "◦", "▪", "–", "★", "✓")

/** v1.9 (R7): the bullet chips' content descriptions, in [BULLET_GLYPHS] order. */
internal val BULLET_DESCRIPTIONS: List<String> = listOf(
    TextLabels19.BULLET_DOT, TextLabels19.BULLET_CIRCLE, TextLabels19.BULLET_SQUARE,
    TextLabels19.BULLET_DASH, TextLabels19.BULLET_STAR, TextLabels19.BULLET_CHECK,
)

/** v1.9 (R7): test tags of the “No list”, “Bullets” and “Numbering” chips (+ the [TextListType] name). */
const val LIST_TYPE_TAG_PREFIX = "v19a3.list.type."

/** v1.9 (R7): test tag of the collapsed “List settings” row. */
const val LIST_SETTINGS_TAG = "v19a3.list.settings"

/** v1.9 (R7): test tag of the “Other bullet…” field. */
const val OTHER_BULLET_TAG = "v19a3.list.otherBullet"

/** v1.9 (R7): test tag of the “Marker font” row. */
const val MARKER_FONT_TAG = "v19a3.list.markerFont"

/** The least size of the bullet and numbering chips (R7: 40 dp at least; 48 dp is a full finger). */
private val MarkerChipWidth = 48.dp

/** The least width of the “No list” / “Bullets” / “Numbering” chips, so they are as easy to hit. */
private val ListTypeChipWidth = 88.dp

/** The size the bullet glyphs are drawn at in their chips (R7: 20 sp at least). */
private val BulletGlyphSize = 22.sp

/** The bullet chips' line height: every glyph (from whichever font has it) gets a chip of one height. */
private val BulletLineHeight = 28.sp

/**
 * v1.9 (R7 a) “Lists” in the text sheet, after Spacing:
 *
 * - “No list”, “Bullets” and “Numbering” (wrapping chips). With “No list” the section is that one
 *   line.
 * - Bullets: six bullet chips (•, ◦, ▪, –, ★, ✓) drawn at [BulletGlyphSize], each described for
 *   a screen reader (“Bullet: dot” …, tags [V19Tags.BULLET_PREFIX] + index), and “Other bullet…”,
 *   a field that keeps the last character typed.
 * - Numbering: eight style chips (1. 1) (1) a. A. i. I. ①; tags [V19Tags.NUMBERING_PREFIX] + the
 *   style) and “First number”.
 * - With a list, a collapsed “List settings” row; opened, “Marker size” (50–200 %), “Marker
 *   thickness” (0–100 %), “Marker gap” (0–3 em) and “Marker font” (“Same as text”, or a font
 *   chosen in the font picker, [onPickMarkerFont] opens it: [MarkerFontPicker]).
 * - Vertical text and text on a path show only “Only horizontal text can have bullets or
 *   numbers”.
 *
 * Every change goes through [host] live, as part of the pending text edit: ✓ commits it in one
 * step, Cancel takes it back.
 */
@Composable
internal fun TextListSection(host: TextEditorHost, onPickMarkerFont: () -> Unit) {
    val item = host.item ?: return
    val spec = item.spec
    val list = spec.list
    SectionHeader(TextLabels19.LISTS)
    if (spec.vertical || item.path.isActive) {
        Text(TextLabels19.LISTS_HORIZONTAL_ONLY, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim, modifier = Modifier.padding(vertical = 2.dp))
        return
    }
    fun change(transform: (TextListSpec) -> TextListSpec) = host.updateSpec { it.copy(list = transform(it.list).sanitized()) }
    TextFlowChips(
        options = listOf(TextLabels19.NO_LIST, TextLabels19.BULLETS, TextLabels19.NUMBERING),
        selected = list.type.ordinal,
        onSelect = { i -> change { it.copy(type = TextListType.entries[i]) } },
        chipMinWidth = ListTypeChipWidth,
        tags = TextListType.entries.map { LIST_TYPE_TAG_PREFIX + it.name },
    )
    when (list.type) {
        TextListType.NONE -> return
        TextListType.BULLETS -> BulletChoices(list) { b -> change { it.copy(bullet = b) } }
        TextListType.NUMBERS -> {
            TextFlowChips(
                options = TextNumbering.entries.map { it.sample },
                selected = list.numbering.ordinal,
                onSelect = { i -> change { it.copy(numbering = TextNumbering.entries[i]) } },
                chipMinWidth = MarkerChipWidth,
                tags = TextNumbering.entries.map { V19Tags.NUMBERING_PREFIX + it.name },
            )
            NumberField(
                label = TextLabels19.FIRST_NUMBER,
                value = list.start.toDouble(),
                onValueChange = { v -> change { it.copy(start = v.roundToInt()) } },
                modifier = Modifier.fillMaxWidth(),
                decimals = 0,
                min = 0.0,
                max = TextListSpec.MAX_START.toDouble(),
                step = 1.0,
                adjust = NumberAdjust.NONE,
            )
        }
    }
    ListSettings(spec, ::change, onPickMarkerFont)
}

/** The six bullet chips and “Other bullet…” (see [TextListSection]); [onPick] gets the bullet. */
@Composable
private fun BulletChoices(list: TextListSpec, onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        BULLET_GLYPHS.forEachIndexed { i, glyph ->
            FilterChip(
                selected = list.bullet == glyph,
                onClick = { onPick(glyph) },
                label = { Text(glyph, fontSize = BulletGlyphSize, lineHeight = BulletLineHeight, maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = MarkerChipWidth - 16.dp)) },
                modifier = Modifier
                    .widthIn(min = MarkerChipWidth)
                    .semantics { contentDescription = BULLET_DESCRIPTIONS[i] }
                    .testTag(V19Tags.BULLET_PREFIX + i),
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = BrushworkColors.AccentDim, selectedLabelColor = Color.White),
            )
        }
    }
    // The field shows a bullet of its own (not one of the six); typing replaces it with the last
    // character typed, and emptying the field keeps the bullet until another is picked.
    val own = list.bullet.takeIf { it !in BULLET_GLYPHS }.orEmpty()
    var typed by remember(own) { mutableStateOf(own) }
    OutlinedTextField(
        value = typed,
        onValueChange = { v ->
            val g = lastGrapheme(v)
            typed = g
            if (g.isNotBlank()) onPick(g)
        },
        label = { Text(TextLabels19.OTHER_BULLET) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(OTHER_BULLET_TAG),
    )
}

/**
 * The collapsed “List settings” row and, opened, the marker's size, thickness, gap and font (see
 * [TextListSection]). Closed every time the sheet opens.
 */
@Composable
private fun ListSettings(spec: TextSpec, change: ((TextListSpec) -> TextListSpec) -> Unit, onPickMarkerFont: () -> Unit) {
    val list = spec.list
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = { open = !open })
            .semantics { stateDescription = if (open) StripLabels18.EXPANDED else StripLabels18.COLLAPSED }
            .testTag(LIST_SETTINGS_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(TextLabels19.LIST_SETTINGS, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = BrushworkColors.OnChromeDim)
    }
    if (!open) return
    LabeledSlider(
        label = TextLabels19.MARKER_SIZE,
        value = list.markerScale,
        onValueChange = { v -> change { it.copy(markerScale = v) } },
        valueRange = TextListSpec.MIN_MARKER_SCALE..TextListSpec.MAX_MARKER_SCALE,
        valueText = "${(list.markerScale * 100f).roundToInt()} %",
        typing = SliderTyping.Percent,
    )
    LabeledSlider(
        label = TextLabels19.MARKER_THICKNESS,
        value = list.markerWeight,
        onValueChange = { v -> change { it.copy(markerWeight = v) } },
        valueRange = 0f..TextListSpec.MAX_MARKER_WEIGHT,
        valueText = "${(list.markerWeight * 100f).roundToInt()} %",
        typing = SliderTyping.Percent,
    )
    LabeledSlider(
        label = TextLabels19.MARKER_GAP,
        value = list.gapEm,
        onValueChange = { v -> change { it.copy(gapEm = v) } },
        valueRange = 0f..TextListSpec.MAX_GAP_EM,
        valueText = Units.formatNumber(list.gapEm.toDouble(), 2) + " em",
        typing = SliderTyping(decimals = 2, suffix = "em"),
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onPickMarkerFont)
            .testTag(MARKER_FONT_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(TextLabels19.MARKER_FONT, style = MaterialTheme.typography.bodyMedium)
            Text(markerFontName(list), style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
        }
        if (!list.sameFontAsText) {
            TextButton(onClick = { change { it.copy(markerFont = null, markerFontId = null, markerFontName = null) } }) { Text(TextLabels19.SAME_AS_TEXT) }
        }
    }
}

/**
 * The font picker for the list's “Marker font” (v1.9, R7): the existing [FontPickerSheet] showing
 * the markers' font, a built-in or imported font picked becomes theirs. Shown by the text sheet
 * while [TextListSection] asks for it.
 */
@Composable
internal fun MarkerFontPicker(host: TextEditorHost, sample: String, onDismiss: () -> Unit) {
    val item = host.item ?: return
    FontPickerSheet(
        store = host.fontStore,
        spec = ListMarkers.markerFontSpec(item.spec),
        sample = sample,
        onPickBuiltIn = { f -> host.updateSpec { it.copy(list = it.list.copy(markerFont = f, markerFontId = null, markerFontName = null)) } },
        onPickImported = { f -> host.updateSpec { it.copy(list = it.list.copy(markerFontId = f.id, markerFontName = f.name).sanitized()) } },
        onFontsChanged = { host.onFontsChanged() },
        onDismiss = onDismiss,
    )
}

/** What the “Marker font” row says: “Same as text”, or the chosen font's name. */
internal fun markerFontName(list: TextListSpec): String = when {
    list.sameFontAsText -> TextLabels19.SAME_AS_TEXT
    list.markerFontId != null -> list.markerFontName ?: list.markerFont?.label ?: TextLabels19.SAME_AS_TEXT
    else -> list.markerFont?.label ?: TextLabels19.SAME_AS_TEXT
}

/** The last grapheme cluster of [s] (a letter with its accent, an emoji with its skin tone), or "" when [s] is empty. */
internal fun lastGrapheme(s: String): String {
    if (s.isEmpty()) return ""
    val it = BreakIterator.getCharacterInstance()
    it.setText(s)
    val end = it.last()
    val start = it.previous()
    return if (start == BreakIterator.DONE) s else s.substring(start, end)
}
