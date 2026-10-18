package com.brushwork.paint.ui.common

/*
 * v1.9 (design 2.3.6, rule L, I10): every new visible text and handle of 1.9. A label is used
 * through its constant, never retyped (LabelsV19Test, rule 2); the texts are frozen for the areas
 * (a new label is the lead's). Reused texts stay where they are.
 */

object LabelsV19 {
    /**
     * Equal to an existing literal of the main sources on purpose (rule 2), each on another screen.
     * F1a adds two with their twins: [CurveLabels19.LAYER_BASE] equals `CurveGenLabels18.CURVES`
     * (the curve generator, another screen; a layer name is user data), and [KernLabels19.AUTO]
     * the content-aware fill's sampling choice (`ContentAwareFillSheet.kt`, another screen).
     */
    val SHARED: Set<String> = setOf(PointLabels19.AMOUNT, StabilityLabels19.COPY, StabilityLabels19.SHARE,
        LayerLabels19.DUPLICATE, CurveLabels19.OWN_LAYER, KernLabels19.OFF, CurveLabels19.LAYER_BASE, KernLabels19.AUTO)
}

object PointLabels19 {      // R18; R1 and R2 reuse the one-point labels, plus FACTOR
    /** R1: the value box while a group drag runs (%s = the factor, "1.25"). */
    const val FACTOR = "×%s"
    const val BEVEL = "Bevel points"; const val AMOUNT = "Amount"; const val SEGMENTS = "Segments"
    const val FEWER_SEGMENTS = "Fewer segments"; const val MORE_SEGMENTS = "More segments"
    const val BEVEL_SHARP_ONLY = "Bevel works on sharp corners"
    const val BEVEL_TOO_MANY = "Bevel would make more than %d points"
}

object CurveLabels19 {      // R14
    /** Equal to the Shape tool's existing switch (ShapePointOptions.kt:411): same meaning, other tool. */
    const val OWN_LAYER = "Editable (own layer)"
    const val OWN_LAYER_DESC = "New curves on a pixel layer go into a vector layer of their own, so they stay editable"
    /**
     * Base name of the layer it makes ("Curves 2"): user data like v1.8's "Vector N". F1a: the
     * constant of [CurveGenLabels18.CURVES], not a second literal (LabelsV18Test rule 2); in SHARED.
     */
    const val LAYER_BASE = CurveGenLabels18.CURVES
    /** The toast when a tap reopens a line in its own tool (%s = "Polyline", "Curve" or "Path"). */
    const val SWITCHED_TO = "Switched to %s to edit this line"
}

object PlaceLabels19 {      // R14, the Text tool's More row and the Shape cog
    const val NEW_LAYER_EACH_TIME = "New layer each time"
    const val NEW_LAYER_EACH_TIME_DESC = "Every new one gets a layer of its own"
    const val SHAPE_EDITABLE_DESC = "New shapes stay editable: they join the selected shape layer, or get a layer of their own"
}

object TextLabels19 {       // R4, R6, R7, R12, R14, C13
    const val JUSTIFY = "Justify lines"; const val JUSTIFY_LAST = "Justify the last line too"
    const val JUSTIFY_COLUMNS = "Justify columns"; const val JUSTIFY_LAST_COLUMN = "Justify the last column too"
    /** R4: the caption before the align buttons while justify is on (also their stateDescription prefix). */
    const val LAST_LINE = "Last line:"; const val LAST_COLUMN = "Last column:"
    const val WORDS = "3 words"
    const val LISTS = "Lists"; const val NO_LIST = "No list"; const val BULLETS = "Bullets"; const val NUMBERING = "Numbering"
    /** R7: the collapsed group of the marker rows, shown after a list is picked. */
    const val LIST_SETTINGS = "List settings"
    const val FIRST_NUMBER = "First number"; const val MARKER_SIZE = "Marker size"; const val MARKER_THICKNESS = "Marker thickness"
    const val MARKER_GAP = "Marker gap"; const val MARKER_FONT = "Marker font"; const val SAME_AS_TEXT = "Same as text"
    const val OTHER_BULLET = "Other bullet…"
    /** R7: the bullet chips' content descriptions, in chip order (the glyphs are visible text). */
    const val BULLET_DOT = "Bullet: dot"; const val BULLET_CIRCLE = "Bullet: circle"; const val BULLET_SQUARE = "Bullet: square"
    const val BULLET_DASH = "Bullet: dash"; const val BULLET_STAR = "Bullet: star"; const val BULLET_CHECK = "Bullet: check"
    const val LISTS_HORIZONTAL_ONLY = "Only horizontal text can have bullets or numbers"
    const val ADD_POINTS = "Add points"; const val EDIT_AS_PATH = "Edit as path"; const val EDIT_AS_POLYLINE = "Edit as polyline"
    /** R12: ✓'s label while nodes are edited, and the toast when node editing starts. */
    const val DONE_POINTS = "Done editing points"
    const val NODES_STARTED = "Editing the path's points: tap ✓ when done"
    const val POINT_COUNT = "Points: %d"
    /** F1a: not the design's wording, which held the node bar's Add point button text (rule 4). */
    const val NODES_HINT = "Drag a point to move it. New points go after the selected point."
    const val NODES_MAX = "A text path holds up to 200 points"
    const val LAYER_FULL = "A text layer holds up to 64 texts. This one starts a new layer."
    /**
     * A3 (lead, at its merge): Duplicate of the open text refused in a full layer. A copy never
     * starts a new layer, so [LAYER_FULL]'s second sentence would be wrong; worded apart from it
     * (rule 4: neither holds the other).
     */
    const val LAYER_FULL_NO_COPY = "A text layer holds up to 64 texts: no room for a copy"
    /**
     * A3 (lead, at its merge): a text among others of its layer cannot start to wrap, nor be
     * duplicated while it wraps. Not A3's "Only a text alone …": the Text strip's letter-scale
     * state "On" would sit inside it (rule 4).
     */
    const val WRAP_ALONE_ONLY = "A text that shares its layer cannot wrap around a picture"
    /** A3 (lead, at its merge): `TextFrameTool.join` refuses a text among others of its layer. */
    const val ALONE_ONLY = "Only a text alone in its layer can be linked"
    /** A3 (lead, at its merge): the in-tool step of a dragged path node ("Undo: " + this). */
    const val MOVE_POINT = "Move point"
    /** C13: v1.8's rasterize toast for a layer with several texts (%s = layer name, %d = count). */
    const val RASTERIZED_TEXTS = "\"%s\" is now a regular layer (its %d texts can no longer be edited; undo to get it back)"
}

object KernLabels19 {       // R5, under the v1.7 "Font kerning" switch, which "Off" drives
    /** Equal to "Off" of the Text strip and sheets (TextWrapSheet.kt:87 and others): in SHARED. */
    const val OFF = "Off"
    const val PAIR_KERNING = "Pair kerning"; const val AUTO = "Auto"; const val OPTICAL = "Optical"; const val METRICS = "Metrics"
    const val AUTO_NOTE = "The font's own pairs, in every script"
    /** F1a: not the design's wording, which held the dialog's Spacing header (rule 4). */
    const val OPTICAL_NOTE = "Measured from the letter shapes; for fonts without good pairs"
    const val METRICS_NOTE = "The font's own pairs between Latin, Greek and Cyrillic letters only"
}

object FilterLabels19 {     // R8 (R10 reuses "Blend mode" and "Opacity")
    const val RESET_ONE = "Reset %s"
    const val CHANGE_STEP = "Change %s"
    const val SESSION_AT_START = "Back at the start: Undo again closes the effect"
}

object StabilityLabels19 {  // R9, R13
    const val CRASH_TITLE = "Brushwork closed unexpectedly"; const val CRASH_SAVED = "A report was saved on this phone."
    const val CRASH_OOM = "The phone ran out of memory."; const val COPY = "Copy"; const val SHARE = "Share"; const val DISMISS = "Dismiss"
    const val REPORT_COPIED = "Report copied"
    const val DIAGNOSTICS = "Diagnostics"; const val CRASH_REPORTS = "Crash reports (%d)"; const val DELETE_REPORTS = "Delete all reports"
    /** Per-row buttons of the Diagnostics list (%s = the report's time, "12 Oct 14:03"), so no two read alike. */
    const val COPY_REPORT = "Copy report %s"; const val SHARE_REPORT = "Share report %s"; const val SHARE_ALL = "Share all"
    /**
     * A7a (lead, at its merge): the report kinds as the Diagnostics rows and the reports' "Summary"
     * name them (`ReportText.kindTitle`; "Crash: IllegalStateException"). Not on a rule-4 screen:
     * a row shows the composed summary, never a kind alone.
     */
    const val KIND_CRASH = "Crash"; const val KIND_HANDLED = "Handled error"; const val KIND_OUT_OF_MEMORY = "Out of memory"
    const val KIND_FREEZE = "Freeze"; const val KIND_NATIVE = "Native crash"; const val KIND_ANR = "Not responding"
    const val KIND_LOW_MEMORY_KILL = "Closed by Android for memory"
    /** A7a (lead, at its merge): a report's first line, and the title of its clip and of its share. */
    const val REPORT_TITLE = "Brushwork crash report"
    /** A7b: a DeferredStep wait that hit its 1.5 s limit (%s = the action's name). */
    const val TOO_SLOW = "%s took too long and was cancelled"
    const val SHOW_FRAME_TIMES = "Show frame times"
    const val ERROR_TOAST = "Something went wrong (%s). Your artwork is safe."
    const val UNDO_TRIMMED = "Memory is low: older undo steps were cleared"
    const val NO_MEMORY_FOR = "Not enough memory for %s"
    /** Not "Fast": it would sit inside "Fast adjustment preview" on the same screen (D17). */
    const val DRAWING_PREVIEW = "Drawing preview"; const val FASTER = "Faster"; const val EXACT_PIXELS = "Exact pixels"
    /** F1a: lowercase, so the note does not hold the [FASTER] button text (rule 4). */
    const val DRAWING_PREVIEW_NOTE = "The faster preview shows the stroke at screen resolution while your finger is down. The saved stroke is always exact."
    const val FAST_ADJUST_NOTE = "Shows adjustments, masks and effects at a lower resolution while you drag"
}

object EraseLabels19 {      // R11
    const val TURN_INTO_PIXELS = "Turn into pixels"
    const val PIXELS_TITLE = "Turn the layer into pixels?"
    const val PIXELS_BODY = "Its shapes and lines stop being editable, and the eraser erases pixels. Undo turns it back."
    const val PIXELS_CONFIRM = "Erase pixels"
    /**
     * The note of the "Partial" mode. F1a review: "Splits", not the design's "Cuts", so no new text
     * holds the selection bar's "Cut" (fact V12, lead ruling 3: a note is reworded, not paired).
     */
    const val PARTIAL_NOTE = "Splits lines, shapes and fills where it passes; the pieces stay editable"
    const val WITH_CURVE = "Erase with curve"; const val WITH_POLYLINE = "Erase with polyline"
    const val WITH_PATH = "Erase with path"; const val WITH_SHAPE = "Erase with shape"
    /** Slot 1 while erase mode is on (v1.8's "Eraser on: switch to <tool>" pattern). */
    const val ERASING_CURVE = "Erasing with curve: switch to painting"; const val ERASING_POLYLINE = "Erasing with polyline: switch to painting"
    const val ERASING_PATH = "Erasing with path: switch to painting"; const val ERASING_SHAPE = "Erasing with shape: switch to painting"
    /** The row-1 mode cell of the four tools while erasing a vector or shape layer (%s = the mode's label). */
    const val MODE_CELL = "Erase mode: %s"
    const val INSTEAD_OF_PAINT = "Erase instead of paint"
    const val STEP_LINE = "Erase line"; const val STEP_FILL = "Erase fill"; const val STEP_PATH = "Erase path"; const val STEP_SHAPE = "Erase shape"
    const val TOO_COMPLEX = "Some shapes were too complex to cut. They were not changed."
    /**
     * The toast when erased objects with array copies are baked first. F1a review: not the design's
     * "Cut objects with …" (fact V12), and not "Objects …", which would hold the eraser's "Object".
     */
    const val COPIES_BAKED = "Each copy of an erased object is now a separate piece"
}

object LayerLabels19 {      // R14, R19
    /** Equal to the object bar's and the gallery's "Duplicate" (other screens); tests use the tag. */
    const val DUPLICATE = "Duplicate"; const val DUPLICATE_DESC = "Duplicate the selection in this layer"
    /** Not "Duplicate selection": that is v1.6's step for a new layer from the selected pixels. */
    const val DUPLICATE_STEP = "Duplicate in layer"
    /** The toast after a selection-bar Duplicate of vector objects or texts. */
    const val DUPLICATED = "Made %d copies in this layer"
    /** A4 (lead, at its merge): the toast when the selection holds nothing of the active layer (%s = its name). */
    const val NOTHING_TO_DUPLICATE = "The selection holds nothing of \"%s\" to duplicate"
    /** R19: the dialog before a merge of two data layers into pixels (%s = a WHY_* text). */
    const val MERGE_TITLE = "Merge into pixels?"
    const val MERGE_BODY = "These layers cannot stay editable: %s. The result will be pixels. Undo brings them back."
    const val MERGE_CONFIRM = "Merge as pixels"
    const val KEEP_EDITABLE = "Keep editable"; const val KEEP_EDITABLE_NOTE = "Overlapping see-through parts may look a little different"
    /** The confirmation toast after "Merge as pixels". */
    const val MERGED_AS_PIXELS = "Merged as pixels: %s"
    const val WHY_MASK = "a layer has a mask"; const val WHY_BLEND = "a layer has a blend mode"
    const val WHY_CLIP = "a layer is clipped"; const val WHY_COPIES = "a layer has copies"
    const val WHY_KINDS = "the layers are of different kinds"
    const val WHY_FRAMES = "a text is a frame of a story or wraps around a picture"
    const val WHY_OVERLAP = "a see-through layer has overlapping objects"
    const val WHY_TOO_MANY = "the layers hold more than 64 texts"
    /** A4 (lead, at its merge): two text layers of different opacities (one layer has one opacity). */
    const val WHY_OPACITY = "the layers have different opacities"
}

object CanvasLabels19 {     // R15
    const val PAPER = "Paper"
    /** The one-line caption of the selected choice. */
    const val WHITE_NOTE = "Erasing makes holes"; const val TRANSPARENT_NOTE = "No background"
    const val PAPER_NOTE = "Erasing shows the paper"
    /** The SVG and PDF export switch. */
    const val INCLUDE_PAPER = "Include paper"
}

object BackupLabels19 {     // R16
    const val BACKUP = "Backup"; const val FOLDER = "Backup folder…"; const val NOW = "Back up now"
    const val RESTORE = "Restore artworks…"; const val SAVE_ALL = "Save all artworks as a file…"; const val OPEN_FILE = "Open a backup file…"
    const val BANNER_NOT_SET = "Keep your artworks safe if the app is uninstalled: choose a backup folder"
    const val CHOOSE_FOLDER = "Choose backup folder"; const val LATER = "Later"
    const val UNAVAILABLE = "Backup folder unavailable"; const val CHOOSE_AGAIN = "Choose again"
    const val RESTORE_TITLE = "Restore artworks from a backup"; const val RESTORE_CONFIRM = "Restore %d artworks"
    const val RESTORED_COUNT = "Restored %d artworks"; const val CHANGE_FOLDER = "Change folder"
    const val BACKED_UP = "Backed up %s"; const val NOT_SET = "Not set"; const val USES = "Uses %s"
    const val ALSO_SETTINGS = "Also restore settings, brushes and palettes"
    const val RESTORED_SUFFIX = " (restored)"; const val PROGRESS = "Backing up %d of %d…"
    const val PICK_HINT = "Pick or create a folder such as Documents/Brushwork (not Download)"
    /** The restore offer when the picked folder already holds a backup index. */
    const val FOUND = "This folder has %d artworks from a backup"
    const val RESTORE_THEM = "Restore them"; const val BACK_UP_HERE = "Back up here without restoring"
    /** The restore list. */
    const val ALREADY = "Already in the gallery"; const val DELETED_SECTION = "Deleted in the last 30 days"
}

object V19Tags {
    const val GROUP_THICKNESS = "v19.points.groupThickness"; const val GROUP_WEIGHT = "v19.points.groupWeight"
    const val BEVEL = "v19.strip.bevel"; const val BEVEL_POPUP = "v19.bevel.popup"
    const val KERN_PREFIX = "v19.kern."             // + "OFF", "AUTO", "OPTICAL", "METRICS_LATIN"
    const val BULLET_PREFIX = "v19.list.bullet."    // + index 0..5
    const val NUMBERING_PREFIX = "v19.list.num."    // + TextNumbering name
    const val ERASE_CHOOSER = "v19.erase.chooser"; const val ERASE_MODE_PREFIX = "v19.erase.mode."   // + VectorEraseMode name
    const val ERASE_PIXELS = "v19.erase.pixels"; const val ERASE_MODE_CELL = "v19.erase.modeCell"
    const val ERASE_SLOT = "v19.erase.slot"; const val FILTER_RESET_PREFIX = "v19.filter.reset."   // + param key
    const val TEXT_PATH_NODES = "v19.textpath.nodes"; const val TEXT_DUPLICATE = "v19.text.duplicate"
    const val NEW_LAYER_EACH_TIME = "v19.place.newLayerEachTime"   // the Text More-row toggle and the Shape cog row
    const val CRASH_CARD = "v19.crash.card"; const val BACKUP_BANNER = "v19.backup.banner"
    const val RESTORE_OFFER = "v19.backup.restoreOffer"
    const val NEW_CANVAS_PREFIX = "v19.newcanvas."   // + "WHITE", "TRANSPARENT", "CUSTOM", "PAPER"
    const val DUPLICATE = "v19.selection.duplicate"; const val MERGE_DIALOG = "v19.merge.dialog"
    const val FRAME_TIMES = "v19.frametimes"
}
