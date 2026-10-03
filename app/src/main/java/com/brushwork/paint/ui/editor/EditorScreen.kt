package com.brushwork.paint.ui.editor

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Numbers
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DesignServices
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.PendingImports
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.ui.assist.GridPanel
import com.brushwork.paint.ui.assist.RulerPanel
import com.brushwork.paint.ui.assist.StabilizerPanel
import com.brushwork.paint.ui.brush.BrushPanel
import com.brushwork.paint.ui.canvas.CanvasAdjustDialog
import com.brushwork.paint.ui.color.ColorPickerPanel
import com.brushwork.paint.ui.common.IncrementsSheet
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.LocalSheetGroup
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetHostState
import com.brushwork.paint.ui.common.SheetPill
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.editor.chrome.BottomBar
import com.brushwork.paint.ui.editor.chrome.ChromeGlyphs
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.editor.chrome.ChromeLayout.TopSlot
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.OptionsStripPanel
import com.brushwork.paint.ui.editor.chrome.ToolMenuPanel
import com.brushwork.paint.ui.editor.chrome.TopButton
import com.brushwork.paint.ui.editor.chrome.TopRow
import com.brushwork.paint.ui.exchange.ExchangeHost
import com.brushwork.paint.ui.exchange.rememberExchangeUi
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.layers.LayerDeleteUndo
import com.brushwork.paint.ui.layers.LayerListMath
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.selection.SelectionPanel
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.CoordinatePill
import com.brushwork.paint.ui.vector.VectorObjectBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sheets and dialogs the editor chrome can show (one at a time; tool sheets, the tool menu and
 * the layer window are separate). [TOOLS] opens the tool menu (v1.6: it replaced the Tools sheet).
 */
enum class EditorPanel {
    TOOLS, BRUSH, COLOR, FILTERS, SELECTION, CANVAS, RULER, GRID, STABILIZER, SETTINGS,

    /** v1.6: More › Increments… (the increment steps and switch; IncrementsSheet, area G). */
    INCREMENTS,
}

/** Panels that edit the document, the selection, the active layer or the tool: closed while a filter is previewed. */
private val PANELS_BLOCKED_BY_FILTER = setOf(
    EditorPanel.TOOLS, EditorPanel.BRUSH, EditorPanel.COLOR,
    EditorPanel.FILTERS, EditorPanel.SELECTION, EditorPanel.CANVAS,
)

/**
 * The painting screen in ibisPaint's layout (v1.6 §3.7): the canvas full bleed on the light
 * surround; the top row of circles (undo, redo, Vector, Selection, Stabilizer, Grid, Ruler, More
 * options) with the floating options strip under it and the X / Y pill under that; the brush
 * slider rows and the bottom bar (eraser switch, tool menu, size disc, colour, hide interface,
 * layers, back) at the bottom; the tool menu, the layer window, the selection / object bar,
 * menus and panels floating over the canvas.
 *
 * Menus (every [com.brushwork.paint.ui.common.BwSheet]: the chrome panels and the tools' own
 * sheets) are non-modal panels drawn by a [SheetHost] on the bottom bar. A touch on the canvas
 * minimizes the open one to a pill and goes on to the canvas (zoom, pan, move objects); the pill
 * restores it. The tool menu and the layer window close on a tap outside them (that tap does
 * nothing else); drawing and pinching beside the layer window keep working with it open. Layer
 * window and panels share the bottom of the screen: an open panel hides the window (it comes back
 * when the panel is closed, not when the panel is minimized to use the canvas), and the layers
 * button minimizes the panels. A panel's button brings that panel back on top, also when a
 * tool's sheet covers it. "Hide interface" hides the top row, options strip, pill, selection bar
 * and slider rows (not the bottom bar nor ✓ / ✕) without refitting the canvas.
 */
@Composable
fun EditorScreen(controller: EditorController, onExit: () -> Unit, onSaveNow: () -> Unit) {
    val sheetHost = rememberSheetHostState()
    // v1.6: the shared number controls reach the increments service through LocalIncrements.
    CompositionLocalProvider(LocalSheetHost provides sheetHost, LocalIncrements provides controller.increments) {
        EditorScreenContent(controller, sheetHost, onExit, onSaveNow)
    }
}

@Composable
private fun EditorScreenContent(controller: EditorController, sheetHost: SheetHostState, onExit: () -> Unit, onSaveNow: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember(controller) { EditorPrefs(controller.settings) }
    val actions = remember(controller, context) { EditorActions(controller, context) }
    var panel by rememberSaveable { mutableStateOf<EditorPanel?>(null) }
    // The layer window is not modal (the canvas stays usable), so it lives beside [panel]; it
    // hides while a panel is shown (see layersVisible).
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    // The ibisPaint tool menu (replaces the v1.5 Tools sheet).
    var toolMenuOpen by rememberSaveable { mutableStateOf(false) }
    // "Hide interface" (bottom bar slot 5): a session state, like ibisPaint's.
    var interfaceHidden by rememberSaveable { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    // Typed brush size / opacity (tapping a value of the slider rows).
    var editingValue by rememberSaveable { mutableStateOf<SliderKind?>(null) }
    // The clipboard content whose paste bar the user hid (shown again for a new copy).
    var hiddenClipboard by remember { mutableStateOf<EditorController.ClipboardImage?>(null) }
    // The canvas is only needed from event handlers, so a plain holder (not state) is enough.
    val canvasRef = remember { arrayOfNulls<CanvasView>(1) }
    val closePanel = { panel = null }
    // The button of a panel that is already open (minimized to its pill, or covered by a tool's
    // sheet) brings it back on top, as it was. TOOLS opens the tool menu.
    val openPanel = { p: EditorPanel ->
        if (p == EditorPanel.TOOLS) {
            sheetHost.minimize()
            layersOpen = false
            toolMenuOpen = true
        } else {
            toolMenuOpen = false
            if (panel == p) sheetHost.bringToFront(p) else panel = p
        }
    }

    // The canvas surround is light: dark status bar icons (v1.6 §3.7.1); the navigation bar is
    // black with light icons. Restored when the editor closes.
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val window = hostView.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, hostView) }
        val lightStatus = bars?.isAppearanceLightStatusBars ?: false
        val lightNav = bars?.isAppearanceLightNavigationBars ?: false
        bars?.isAppearanceLightStatusBars = true
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = lightStatus
            bars?.isAppearanceLightNavigationBars = lightNav
        }
    }

    // Where the floating windows are, so a tap on the chrome around them (not on them) closes them.
    val layersBounds = remember { WindowBounds() }
    val menuBounds = remember { WindowBounds() }
    val floatingRects: () -> List<Rect> = remember { { listOfNotNull(layersBounds.rect(), menuBounds.rect()) } }
    // The X / Y pill's height (0 when the tool places nothing): the selection bar goes under it.
    var pillPx by remember { mutableIntStateOf(0) }
    var selectionBarPx by remember { mutableIntStateOf(0) }
    // The filter panel replaces the slider rows and the bottom bar while a filter is previewed.
    var sessionPanelPx by remember { mutableIntStateOf(0) }

    // Undo/redo feedback (canvas taps and the top row's buttons; texts are kept while the chips fade out).
    var tapText by remember { mutableStateOf("") }
    var tapVisible by remember { mutableStateOf(false) }
    var tapSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapSerial) {
        if (tapSerial > 0) { delay(1200); tapVisible = false }
    }
    val showFeedback: (String) -> Unit = { text -> tapText = text; tapVisible = true; tapSerial++ }
    // Zoom/rotation readout while pinching, lingering briefly afterwards.
    var gestureInfo by remember { mutableStateOf(ViewGestureInfo(1f, 0f)) }
    var gestureActive by remember { mutableStateOf(false) }
    var gestureVisible by remember { mutableStateOf(false) }
    var gestureSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(gestureActive, gestureSerial) {
        if (!gestureActive && gestureSerial > 0) { delay(700); gestureVisible = false }
    }
    var draggingSlider by remember { mutableStateOf<SliderKind?>(null) }

    // ---------------------------------------------------------------- import / export launchers
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) actions.importPicture(uri)
    }
    val launchImport = {
        try {
            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            controller.toast("No app available to pick pictures")
        }
    }
    var pendingExport by rememberSaveable { mutableStateOf<ExportFormat?>(null) }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val format = pendingExport
        pendingExport = null
        if (format != null) {
            if (granted) actions.exportToGallery(format) else controller.toast("Storage permission is needed to save to the gallery")
        }
    }
    val requestExport = { format: ExportFormat ->
        val needsPermission = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) {
            pendingExport = format
            storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            actions.exportToGallery(format)
        }
    }

    // ---------------------------------------------------------------- SVG / PDF exchange (v1.5)
    val exchange = rememberExchangeUi(controller)
    // A file the gallery's "New from SVG or PDF" handed over for this artwork is imported once.
    LaunchedEffect(controller.doc.id) {
        PendingImports.take(controller.doc.id)?.let(exchange::importUri)
    }

    // ---------------------------------------------------------------- snackbar messages
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // Problems found while opening (unreadable vector or mask data...), shown once.
    LaunchedEffect(controller) {
        val warnings = controller.doc.loadWarnings
        if (warnings.isNotEmpty()) {
            val text = warnings.joinToString("\n")
            warnings.clear()
            scope.launch { snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Long) }
        }
    }
    val message = controller.message
    LaunchedEffect(message) {
        if (message != null) {
            controller.message = null
            // Show in a separate coroutine: clearing the message restarts this effect.
            if (snackbar.currentSnackbarData?.visuals?.message != message) {
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    snackbar.showSnackbar(message, duration = SnackbarDuration.Short)
                }
            }
        }
    }
    // A layer deleted from the layer window (no confirmation): "Undo" right in the message.
    val onLayerDeleted: (Layer) -> Unit = remember(controller) {
        { layer ->
            val undoCount = controller.undoManager.undoCount
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                // An action label would make the message stay until dismissed: keep it brief.
                val result = snackbar.showSnackbar("Layer deleted", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed && LayerDeleteUndo.canUndo(controller, layer, undoCount)) {
                    controller.endCanvasGesture()
                    controller.undo()
                }
            }
        }
    }

    // Only the on/off flags are shown here; the settings objects change on every ruler drag or
    // grid slider move, which must not recompose the whole screen.
    val rulerOn by remember(controller) { derivedStateOf { controller.ruler.enabled } }
    val gridOn by remember(controller) { derivedStateOf { controller.grid.enabled } }
    val stabilizerOn by remember(controller) { derivedStateOf { controller.stabilizer.mode != StabilizerMode.OFF } }

    val session = controller.filterSession
    val busy = controller.busyMessage
    // While a filter is previewed, everything that changes the document, the selection or the
    // active layer (or exports without the preview) waits until it is applied or cancelled.
    val docActionsEnabled = session == null
    LaunchedEffect(session) {
        if (session != null) {
            if (panel in PANELS_BLOCKED_BY_FILTER) panel = null
            toolMenuOpen = false
            // The layer window only hides (see layersVisible): it is back once the filter is
            // applied or cancelled.
            editingValue = null
        }
    }
    // The layer window shows unless a panel is up (a minimized one's pill waits meanwhile), a
    // filter is previewed or the settings dialog is open.
    val layersVisible = layersOpen && session == null && !sheetHost.hasExpanded && panel != EditorPanel.SETTINGS
    // A panel shown from outside the menu (an options strip chip opens its tool's sheet, e.g. the
    // Shape tool's "Settings") closes the menu: it would stay under the panel, and Back would
    // close it instead of the panel on top. (The menu's own cells close it before they open one.)
    val toolMenuVisible = toolMenuOpen && session == null && !sheetHost.hasExpanded
    LaunchedEffect(sheetHost.hasExpanded) { if (sheetHost.hasExpanded) toolMenuOpen = false }
    // Back stops a cancellable operation; otherwise it waits for the operation to finish.
    BackHandler(enabled = busy != null) { controller.busyCancel?.invoke() }
    BackHandler(enabled = busy == null && session != null) { session?.cancel() }
    // The layer window, the tool menu and the panels are not dialogs: Back closes them before
    // leaving the editor (the newest first: the tool menu, the window, then the newest panel).
    BackHandler(enabled = busy == null && session == null && !layersVisible && !sheetHost.isEmpty) {
        controller.endCanvasGesture()
        sheetHost.dismissTop()
    }
    BackHandler(enabled = busy == null && session == null && layersVisible) { layersOpen = false }
    BackHandler(enabled = busy == null && toolMenuVisible) { toolMenuOpen = false }
    val closeFloating: () -> Unit = remember { { layersOpen = false; toolMenuOpen = false } }
    // A touch on the canvas while a panel is up: the panel folds into its pill so the canvas can
    // be used (the touch goes on to the canvas). The layer window hidden under the panel is
    // closed too, or it would pop up in its place. The tool menu closes on any canvas touch.
    val onCanvasTouch: () -> Unit = remember(sheetHost) {
        {
            toolMenuOpen = false
            if (sheetHost.hasExpanded) {
                sheetHost.minimize()
                layersOpen = false
            }
        }
    }
    val onMinimizePanel: () -> Unit = remember(sheetHost) {
        {
            if (sheetHost.hasExpanded) {
                sheetHost.minimize()
                layersOpen = false
            }
        }
    }

    val tool = controller.currentTool
    // Derived: the tools back these flags with state that changes on every move of a drag (a
    // transform handle, a curve anchor, a shape edge); reading it here directly would recompose
    // the whole screen for each touch sample instead of only when a flag flips.
    val pendingWork by remember(controller) {
        derivedStateOf { controller.filterSession == null && controller.currentTool.hasPendingWork }
    }
    val toolHasUserChanges by remember(controller) { derivedStateOf { controller.currentTool.hasUserChanges } }
    val undoEnabled by remember(controller) {
        derivedStateOf { controller.canUndo || controller.currentTool.hasUserChanges || controller.filterSession != null }
    }
    val redoEnabled by remember(controller) { derivedStateOf { canRedoNow(controller) } }
    val clipboard = controller.clipboard
    // A new copy shows the paste bar again (and the hidden one's pixels aren't kept alive here).
    LaunchedEffect(clipboard) { if (clipboard !== hiddenClipboard) hiddenClipboard = null }
    val hasSelection = controller.selection != null
    // Derived: both read layersVersion, which changes with every committed edit; only the flags
    // flipping may recompose the whole screen.
    val objectsSelected by remember(controller) { derivedStateOf { controller.vectors.selectedIds.isNotEmpty() } }
    val vectorMode by remember(controller) { derivedStateOf { controller.isVectorMode } }
    // The selection bar steps aside for the user's tool work in progress (its ✓/✕ come first:
    // a paste being placed, curve points...), filters, long operations and the selection menu
    // itself. The transform tool's own untouched lift doesn't count: selecting it to move the
    // selection keeps Copy / Deselect at hand.
    val selectionBarVisible = session == null && busy == null && !toolHasUserChanges &&
        (panel != EditorPanel.SELECTION || sheetHost.minimized) &&
        (hasSelection || (clipboard != null && clipboard !== hiddenClipboard))
    // Selected vector objects (v1.5): their bar takes the selection bar's place.
    val objectBarVisible = objectsSelected && session == null && busy == null

    // ---------------------------------------------------------------- system insets (dp)
    val layoutDirection = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val statusDp = with(density) { safe.getTop(this).toDp() }.value
    val navDp = with(density) { WindowInsets.navigationBars.getBottom(this).toDp() }.value
    val insetLeftDp = with(density) { safe.getLeft(this, layoutDirection).toDp() }.value
    val insetRightDp = with(density) { safe.getRight(this, layoutDirection).toDp() }.value
    val horizontalSafe = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(IbisColors.Surround)
            .onGloballyPositioned { layersBounds.root = it; menuBounds.root = it }
            // A tap on the chrome around the layer window or the tool menu that no control used
            // closes them (taps on the canvas are handled by the canvas: see onOutsideTap below).
            .unusedTaps(
                enabled = layersVisible || toolMenuVisible,
                exclude = floatingRects,
                onTap = closeFloating,
            ),
    ) {
        val screenW = maxWidth.value
        val screenH = maxHeight.value
        val contentW = (screenW - insetLeftDp - insetRightDp).coerceAtLeast(1f)
        val topSpec = ChromeLayout.topRow(contentW)
        val pillTop = ChromeLayout.pillTop(statusDp)
        val pillDp = with(density) { pillPx.toDp() }.value
        val selectionBarTop = ChromeLayout.selectionBarTop(statusDp, pillDp)
        val selectionBarDp = with(density) { selectionBarPx.toDp() }.value
        val sessionPanelDp = with(density) { sessionPanelPx.toDp() }.value
        val layerWindow = ChromeLayout.layerWindow(contentW, screenH, statusDp, navDp)
        // Size over opacity on a phone; side by side in one row on a wide screen.
        val sliderRows = ChromeLayout.sliderRowCount(contentW)

        // ------------------------------------------------------------ canvas (full bleed)
        val fitTopPx = with(density) { ChromeLayout.fitInsetTop(statusDp).dp.toPx() }
        val fitBottomPx = if (session != null) sessionPanelPx.toFloat() else with(density) { ChromeLayout.fitInsetBottom(navDp, sliderRows).dp.toPx() }
        val outsideTap = if (layersVisible || toolMenuVisible) closeFloating else null
        key(controller) {
            AndroidView(
                factory = { ctx -> CanvasView(ctx, controller).also { canvasRef[0] = it } },
                modifier = Modifier.fillMaxSize(),
                update = { v ->
                    v.onTapAction = showFeedback
                    v.onViewGesture = { info ->
                        if (info != null) {
                            gestureInfo = info; gestureActive = true; gestureVisible = true
                        } else {
                            gestureActive = false; gestureSerial++
                        }
                    }
                    v.onTouchDown = onCanvasTouch
                    // With the layer window or the tool menu open, a tap on the canvas only closes them.
                    v.onOutsideTap = outsideTap
                    v.setMirrored(controller.viewMirrored)
                    // Constant bands (V11): the pill, the selection bar and "hide interface" never move the canvas.
                    v.setFitInsets(0f, fitTopPx, 0f, fitBottomPx)
                },
                onRelease = { v -> if (canvasRef[0] === v) canvasRef[0] = null },
            )
        }

        // ------------------------------------------------------------ top row + options strip
        val topButtons = listOf(
            TopButton(TopSlot.UNDO, "Undo", Icons.AutoMirrored.Filled.Undo, enabled = undoEnabled) {
                controller.endCanvasGesture()
                showFeedback(HistoryLabels.performUndo(controller))
            },
            TopButton(TopSlot.REDO, "Redo", Icons.AutoMirrored.Filled.Redo, enabled = redoEnabled) {
                controller.endCanvasGesture()
                showFeedback(HistoryLabels.performRedo(controller))
            },
            // Vector mode (v1.5): on while the active layer is a vector layer.
            TopButton(TopSlot.VECTOR, "Vector", ChromeGlyphs.ToggleSwitch, on = vectorMode, enabled = docActionsEnabled) {
                controller.endCanvasGesture()
                controller.toggleVectorMode()
            },
            TopButton(TopSlot.SELECTION, "Selection", ChromeGlyphs.DashedRect, enabled = docActionsEnabled) { openPanel(EditorPanel.SELECTION) },
            TopButton(TopSlot.STABILIZER, "Stabilizer", Icons.Outlined.PanTool, on = stabilizerOn) { openPanel(EditorPanel.STABILIZER) },
            TopButton(TopSlot.GRID, "Grid", ChromeGlyphs.SquareCircle, on = gridOn) { openPanel(EditorPanel.GRID) },
            TopButton(TopSlot.RULER, "Ruler", Icons.Outlined.DesignServices, on = rulerOn) { openPanel(EditorPanel.RULER) },
            // The menu drops over the canvas: the tool menu closes first (its cells would repeat
            // the menu's "Settings", I10). The layer window stays, as it does for every chrome
            // button: "Fit to screen" or "Paste" with the layers in view. Its own "Import picture"
            // button is then the only one: the menu leaves that entry out (moreMenuEntries).
            TopButton(TopSlot.MORE, "More options", Icons.Outlined.Image) { controller.endCanvasGesture(); toolMenuOpen = false; moreOpen = true },
        )
        controller.docVersion // size changes (canvas resize) refresh the More menu's header
        val doc = controller.doc
        val moreEntries = moreMenuEntries(
            controller = controller,
            hasSelection = hasSelection,
            docActionsEnabled = docActionsEnabled,
            canPaste = clipboard != null,
            layerWindowShown = layersVisible,
            onImportPicture = { launchImport() },
            onImportVector = { controller.endCanvasGesture(); exchange.requestImport() },
            onExport = requestExport,
            onExportVector = { f -> controller.endCanvasGesture(); exchange.requestExport(f) },
            onShare = { actions.share() },
            onCanvas = { openPanel(EditorPanel.CANVAS) },
            canvasRef = canvasRef,
            onIncrements = { openPanel(EditorPanel.INCREMENTS) },
            onSaveNow = { onSaveNow(); controller.toast("Saved") },
            onSettings = { openPanel(EditorPanel.SETTINGS) },
        )
        AnimatedVisibility(
            visible = !interfaceHidden,
            enter = fadeIn(tween(IbisDims.HideInterfaceFadeMs)),
            exit = fadeOut(tween(IbisDims.HideInterfaceFadeMs)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
                TopRow(
                    spec = topSpec,
                    buttons = topButtons,
                    moreOpen = moreOpen,
                    onMoreOpenChange = { moreOpen = it },
                    moreHeader = "${doc.name} · ${doc.width} × ${doc.height} px",
                    moreEntries = moreEntries,
                )
                // Tool options act on the document; the tool is not usable while a filter is previewed.
                if (session == null) {
                    Spacer(Modifier.height(ChromeLayout.optionsStripGap.dp))
                    OptionsStripPanel(controller, Modifier.fillMaxWidth().padding(horizontal = IbisDims.OptionsStripSide))
                }
            }
        }

        // ------------------------------------------------------------ X / Y pill (area G)
        if (session == null) {
            AnimatedVisibility(
                visible = !interfaceHidden,
                enter = fadeIn(tween(IbisDims.HideInterfaceFadeMs)),
                exit = fadeOut(tween(IbisDims.HideInterfaceFadeMs)),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(horizontalSafe)
                    .padding(top = pillTop.dp, start = IbisDims.PillStart, end = IbisDims.PillStart),
            ) {
                // Left out of the canvas fit inset, so it never moves the canvas.
                Box(Modifier.testTag(ChromeTags.PILL_SLOT).onSizeChanged { pillPx = it.height }) { CoordinatePill(controller) }
            }
        }

        // ------------------------------------------------------------ selection actions
        val selectionModifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(horizontalSafe)
            .padding(top = selectionBarTop.dp, start = 8.dp, end = 8.dp)
        AnimatedVisibility(
            visible = !interfaceHidden && (objectBarVisible || selectionBarVisible),
            enter = fadeIn(tween(IbisDims.HideInterfaceFadeMs)),
            exit = fadeOut(tween(IbisDims.HideInterfaceFadeMs)),
            modifier = selectionModifier,
        ) {
            if (objectBarVisible) {
                // Selected vector objects get their own bar in the selection bar's place (v1.5).
                VectorObjectBar(controller, Modifier.testTag(ChromeTags.SELECTION_BAR).onSizeChanged { selectionBarPx = it.height })
            } else {
                SelectionActionBar(
                    controller = controller,
                    onMore = { openPanel(EditorPanel.SELECTION) },
                    onHide = if (hasSelection) null else ({ hiddenClipboard = controller.clipboard }),
                    modifier = Modifier.testTag(ChromeTags.SELECTION_BAR).onSizeChanged { selectionBarPx = it.height },
                )
            }
        }

        // ------------------------------------------------------------ bottom: slider rows + bottom bar
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (session != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { sessionPanelPx = it.height }
                        .background(BrushworkColors.Chrome)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                ) {
                    FilterSessionPanel(session, Modifier.fillMaxWidth())
                }
            } else {
                AnimatedVisibility(
                    visible = !interfaceHidden,
                    enter = fadeIn(tween(IbisDims.HideInterfaceFadeMs)),
                    exit = fadeOut(tween(IbisDims.HideInterfaceFadeMs)),
                ) {
                    BrushSliderRows(
                        controller = controller,
                        leftHanded = prefs.leftHanded,
                        onDragChange = { draggingSlider = it },
                        onEditValue = { kind -> controller.endCanvasGesture(); editingValue = kind },
                        modifier = Modifier.windowInsetsPadding(horizontalSafe).testTag(ChromeTags.SLIDER_ROWS),
                        oneRow = sliderRows == 1,
                    )
                }
                Box(Modifier.fillMaxWidth().background(IbisColors.BottomBar).windowInsetsPadding(horizontalSafe)) {
                    BottomBar(
                        controller = controller,
                        toolMenuOpen = toolMenuVisible,
                        onToolMenu = {
                            controller.endCanvasGesture()
                            if (toolMenuOpen) toolMenuOpen = false else openPanel(EditorPanel.TOOLS)
                        },
                        onBrushPanel = { openPanel(EditorPanel.BRUSH) },
                        onColorPanel = { openPanel(EditorPanel.COLOR) },
                        interfaceHidden = interfaceHidden,
                        onToggleInterface = { interfaceHidden = !interfaceHidden },
                        layersOpen = layersVisible,
                        onLayers = {
                            if (layersVisible) {
                                layersOpen = false
                            } else {
                                // Open panels make room (minimized, not closed); the tool menu closes.
                                sheetHost.minimize()
                                toolMenuOpen = false
                                layersOpen = true
                            }
                        },
                        onBack = onExit,
                        modifier = Modifier.testTag(ChromeTags.BOTTOM_BAR),
                    )
                }
                // The navigation bar: black, with light icons.
                Spacer(Modifier.fillMaxWidth().background(Color.Black).windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }

        // ------------------------------------------------------------ tool menu (non-modal)
        if (toolMenuVisible) {
            ToolMenuPanel(
                controller = controller,
                maxHeight = ChromeLayout.toolMenuMaxHeight(screenH, statusDp, navDp).dp,
                onPickTool = { id ->
                    controller.endCanvasGesture()
                    controller.selectTool(id)
                    toolMenuOpen = false
                },
                onFilters = { controller.endCanvasGesture(); openPanel(EditorPanel.FILTERS) },
                onCanvas = { controller.endCanvasGesture(); openPanel(EditorPanel.CANVAS) },
                onSettings = { openPanel(EditorPanel.SETTINGS) },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(horizontalSafe)
                    .padding(start = IbisDims.ToolMenuStart, bottom = (navDp + IbisDims.BottomBarHeight.value + IbisDims.ToolMenuAboveBar.value).dp)
                    .testTag(ChromeTags.TOOL_MENU)
                    .onGloballyPositioned { menuBounds.window = it },
            )
        }

        // ------------------------------------------------------------ layer window (non-modal)
        if (layersVisible) {
            if (!layerWindow.short) {
                // v1.6 sizing contract: the host sizes the layer window (ibisPaint's 382 × 520 at
                // x 5, its bottom on the bottom bar's top), LayersPanel fills it.
                LayersPanel(
                    controller = controller,
                    onDismiss = { layersOpen = false },
                    onImportPicture = { launchImport() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = (insetLeftDp + layerWindow.x).dp, y = layerWindow.top.dp)
                        .size(layerWindow.width.dp, layerWindow.height.dp)
                        .testTag(ChromeTags.LAYER_WINDOW)
                        .onGloballyPositioned { layersBounds.window = it },
                    onLayerDeleted = onLayerDeleted,
                    onOpenPanel = openPanel,
                )
            } else {
                // Short screens (a phone in landscape): the v1.5 side-by-side window, bottom-right.
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(horizontalSafe)
                        .padding(
                            top = (ChromeLayout.topRowBottom(statusDp) + 8f).dp,
                            bottom = (ChromeLayout.fitInsetBottom(navDp, sliderRows) + 8f).dp,
                            end = 8.dp,
                        ),
                ) {
                    val window = LayerListMath.windowSize(screenW.roundToInt(), screenH.roundToInt())
                    LayersPanel(
                        controller = controller,
                        onDismiss = { layersOpen = false },
                        onImportPicture = { launchImport() },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(window.width.dp, window.height.dp)
                            .testTag(ChromeTags.LAYER_WINDOW)
                            .onGloballyPositioned { layersBounds.window = it },
                        onLayerDeleted = onLayerDeleted,
                        onOpenPanel = openPanel,
                    )
                }
            }
        }

        // ------------------------------------------------------------ ✓ / ✕ (v1.6 §3.7.9)
        val sliderRowsDp = if (interfaceHidden) 0f else sliderRows * IbisDims.SliderRowHeight.value
        val pendingBottom = navDp + IbisDims.BottomBarHeight.value + sliderRowsDp + IbisDims.PendingBarGap.value
        if (pendingWork) {
            when {
                layersVisible && layerWindow.short -> PendingWorkBar(
                    controller, tool,
                    Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(horizontalSafe)
                        .padding(start = 10.dp, bottom = (ChromeLayout.fitInsetBottom(navDp, sliderRows) + 12f).dp)
                        .testTag(ChromeTags.PENDING_BAR),
                    vertical = true,
                )
                // Above the layer window's top-right corner.
                layersVisible -> PendingWorkBar(
                    controller, tool,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = (screenW - insetLeftDp - layerWindow.x - layerWindow.width).coerceAtLeast(0f).dp,
                            bottom = (screenH - layerWindow.top + IbisDims.PendingBarGap.value).dp,
                        )
                        .testTag(ChromeTags.PENDING_BAR),
                )
                // Right-aligned beside the tool menu.
                toolMenuVisible -> PendingWorkBar(
                    controller, tool,
                    Modifier
                        .align(Alignment.BottomEnd)
                        .windowInsetsPadding(horizontalSafe)
                        .padding(end = IbisDims.PendingBarGap, bottom = pendingBottom.dp)
                        .testTag(ChromeTags.PENDING_BAR),
                )
                else -> PendingWorkBar(
                    controller, tool,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = pendingBottom.dp)
                        .testTag(ChromeTags.PENDING_BAR),
                )
            }
        }
        // A minimized panel waits above the ✓ / ✕ (or above the slider rows), not while the
        // layer window or the tool menu is open (it would sit on their lower part).
        val pillBottom = when {
            session != null -> sessionPanelDp + 10f
            pendingWork -> pendingBottom + PendingButtonSize.value + IbisDims.PendingBarGap.value
            else -> pendingBottom
        }
        if (!layersVisible && !toolMenuVisible) {
            SheetPill(
                sheetHost,
                Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(horizontalSafe)
                    .padding(start = 12.dp, end = 12.dp, bottom = pillBottom.dp),
            )
        }

        // ------------------------------------------------------------ panels (non-modal)
        // On the bottom bar (over the slider rows), or above the filter panel.
        val panelBottom = if (session != null) sessionPanelDp else navDp + IbisDims.BottomBarHeight.value
        SheetHost(sheetHost, bottomInset = panelBottom.dp, modifier = Modifier.padding(top = ChromeLayout.topRowBottom(statusDp).dp), onMinimize = onMinimizePanel)

        // ------------------------------------------------------------ transient feedback (the InfoChip slot)
        val chipTop = when {
            interfaceHidden -> statusDp + IbisDims.PendingBarGap.value
            (objectBarVisible || selectionBarVisible) && selectionBarDp > 0f -> selectionBarTop + selectionBarDp + 6f
            pillDp > 0f -> pillTop + pillDp + 6f
            else -> pillTop
        }
        FeedbackChips(
            controller = controller,
            tapVisible = tapVisible,
            tapText = { tapText },
            gestureVisible = gestureVisible,
            gestureInfo = { gestureInfo },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = chipTop.dp),
        )
        draggingSlider?.let { kind ->
            SliderPreview(controller, kind, zoom = canvasRef[0]?.zoom ?: 1f, modifier = Modifier.align(Alignment.Center))
        }
        // Messages must not land on the layer window, the tool menu or a panel (a snackbar would
        // cover their controls and take their taps): while one is open they show near the top,
        // under the feedback chip. Otherwise above everything at the bottom (✓ / ✕, a panel's pill).
        val snackbarPlacement = if (layersVisible && !layerWindow.short) {
            // Right above the layer window (and its ✓ / ✕), never on it.
            val above = screenH - layerWindow.top + IbisDims.PendingBarGap.value + if (pendingWork) PendingButtonSize.value + IbisDims.PendingBarGap.value else 0f
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(horizontalSafe)
                .padding(bottom = above.dp)
        } else if (layersVisible || sheetHost.hasExpanded || toolMenuVisible) {
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(horizontalSafe)
                .padding(top = (chipTop + 40f).dp)
        } else {
            val above = pillBottom + if (sheetHost.minimized) PILL_ROOM else 0f
            Modifier.align(Alignment.BottomCenter).padding(bottom = (above + 8f).dp)
        }
        SnackbarHost(snackbar, modifier = snackbarPlacement) { data ->
            Snackbar(data, containerColor = BrushworkColors.ChromeHigh, contentColor = BrushworkColors.OnChrome, actionColor = BrushworkColors.Accent)
        }

        // ------------------------------------------------------------ busy scrim (blocks input)
        if (busy != null) BusyOverlay(busy, progress = { controller.busyProgress }, onCancel = controller.busyCancel)
    }

    // ---------------------------------------------------------------- panels
    // Grouped by panel, so the panel's button brings it (and the sheets it opened) back on top.
    CompositionLocalProvider(LocalSheetGroup provides panel) {
        when (panel) {
            // v1.6: the tool menu replaced the Tools sheet (openPanel(TOOLS) opens it).
            EditorPanel.TOOLS -> LaunchedEffect(Unit) { panel = null; openPanel(EditorPanel.TOOLS) }
            EditorPanel.BRUSH -> BrushPanel(controller, closePanel)
            EditorPanel.COLOR -> ColorPickerPanel(controller, closePanel)
            EditorPanel.FILTERS -> FilterBrowser(controller, closePanel)
            EditorPanel.SELECTION -> SelectionPanel(controller, closePanel)
            EditorPanel.CANVAS -> CanvasAdjustDialog(controller, closePanel)
            EditorPanel.RULER -> RulerPanel(controller, closePanel)
            EditorPanel.GRID -> GridPanel(controller, closePanel)
            EditorPanel.STABILIZER -> StabilizerPanel(controller, closePanel)
            EditorPanel.SETTINGS -> EditorSettingsDialog(prefs, closePanel, onCanvasChanged = { controller.invalidateDoc(null) }, controller = controller)
            EditorPanel.INCREMENTS -> IncrementsSheet(controller, closePanel)
            null -> {}
        }
    }
    editingValue?.let { kind -> BrushValueDialog(controller, kind) { editingValue = null } }
    ExchangeHost(exchange)
}

/** Room a minimized panel's pill takes above the bottom items (its 44 dp row and a gap). */
private const val PILL_ROOM = 52f

/**
 * The InfoChip slot (v1.6 §3.7.9): the increments readout of a stepped gesture ("+30 px", "120 %",
 * "45°") first, else the undo / redo feedback, else the zoom / rotation of a pinch. Its own
 * composable: the readout and the pinch values change during a drag, and reading them here keeps
 * those changes from recomposing the whole screen (the top row, every tool's options strip…).
 */
@Composable
private fun FeedbackChips(
    controller: EditorController,
    tapVisible: Boolean,
    tapText: () -> String,
    gestureVisible: Boolean,
    gestureInfo: () -> ViewGestureInfo,
    modifier: Modifier,
) {
    val readout = controller.increments.readout
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedVisibility(visible = readout != null, enter = fadeIn(), exit = fadeOut()) {
            // Keeps showing the last text while it fades out (a plain holder: no state write here).
            val last = remember { arrayOf("") }
            if (readout != null) last[0] = readout
            InfoChip(readout ?: last[0])
        }
        AnimatedVisibility(visible = tapVisible && readout == null, enter = fadeIn(), exit = fadeOut()) { InfoChip(tapText()) }
        AnimatedVisibility(visible = gestureVisible && !tapVisible && readout == null, enter = fadeIn(), exit = fadeOut()) {
            val info = gestureInfo()
            val deg = info.rotation.roundToInt()
            InfoChip(SliderMath.formatZoom(info.zoom) + if (deg != 0) "  ·  $deg°" else "")
        }
    }
}

/**
 * The More menu's entries (v1.6 §3.7.6; the v1.5 overflow labels unchanged, plus Canvas… and
 * Increments…). The narrow-screen top-row circles that fold into it come first (TopRow adds them).
 * While the layer window is shown ([layerWindowShown]) "Import picture" is left out: the window has
 * the same button, and two controls never share a label (I10).
 */
private fun moreMenuEntries(
    controller: EditorController,
    hasSelection: Boolean,
    docActionsEnabled: Boolean,
    canPaste: Boolean,
    layerWindowShown: Boolean,
    onImportPicture: () -> Unit,
    onImportVector: () -> Unit,
    onExport: (ExportFormat) -> Unit,
    onExportVector: (VectorFormat) -> Unit,
    onShare: () -> Unit,
    onCanvas: () -> Unit,
    canvasRef: Array<CanvasView?>,
    onIncrements: () -> Unit,
    onSaveNow: () -> Unit,
    onSettings: () -> Unit,
): List<MenuEntry> = listOfNotNull(
    MenuEntry(if (hasSelection) "Copy selection" else "Copy layer", Icons.Filled.ContentCopy, enabled = docActionsEnabled) {
        controller.endCanvasGesture()
        controller.copySelection()
    },
    MenuEntry("Paste", Icons.Filled.ContentPaste, enabled = docActionsEnabled && canPaste) {
        controller.endCanvasGesture()
        controller.paste()
    },
    if (layerWindowShown) null else MenuEntry("Import picture", Icons.Filled.AddPhotoAlternate, enabled = docActionsEnabled, dividerBefore = true) { onImportPicture() },
    MenuEntry("Import SVG or PDF…", Icons.Filled.FileOpen, enabled = docActionsEnabled, dividerBefore = layerWindowShown) { onImportVector() },
    MenuEntry("Export PNG", Icons.Filled.SaveAlt, enabled = docActionsEnabled) { onExport(ExportFormat.PNG) },
    MenuEntry("Export JPG", Icons.Filled.Image, enabled = docActionsEnabled) { onExport(ExportFormat.JPEG) },
    MenuEntry("Export SVG…", Icons.Filled.Polyline, enabled = docActionsEnabled) { onExportVector(VectorFormat.SVG) },
    MenuEntry("Export PDF…", Icons.Filled.PictureAsPdf, enabled = docActionsEnabled) { onExportVector(VectorFormat.PDF) },
    MenuEntry("Share", Icons.Filled.Share, enabled = docActionsEnabled) { onShare() },
    MenuEntry("Canvas…", Icons.Filled.AspectRatio, enabled = docActionsEnabled, dividerBefore = true) { onCanvas() },
    MenuEntry("Flip view", Icons.Filled.Flip, checked = controller.viewMirrored, dividerBefore = true) {
        controller.viewMirrored = !controller.viewMirrored
    },
    MenuEntry("Fit to screen", Icons.Filled.FitScreen) { canvasRef[0]?.fitToScreen() },
    MenuEntry("100% (actual pixels)", Icons.Filled.CenterFocusStrong) { canvasRef[0]?.actualPixels() },
    MenuEntry("Reset rotation", Icons.Filled.CropRotate) { canvasRef[0]?.resetRotation() },
    MenuEntry("Increments…", Icons.Filled.Numbers, checked = controller.increments.enabled, dividerBefore = true) { onIncrements() },
    MenuEntry("Save now", Icons.Filled.Save) { onSaveNow() },
    MenuEntry("Settings", Icons.Filled.Settings) { onSettings() },
)

/** Where a floating window is, relative to the editor's root box (both set by onGloballyPositioned). */
internal class WindowBounds {
    var root: LayoutCoordinates? = null
    var window: LayoutCoordinates? = null

    /** The window's bounds in the root's coordinates, or null when it isn't on screen. */
    fun rect(): Rect? {
        val r = root ?: return null
        val w = window ?: return null
        if (!r.isAttached || !w.isAttached) return null
        return r.localBoundingBoxOf(w)
    }
}

/**
 * Calls [onTap] for a quick one-finger tap that nothing under it used — the background of the
 * chrome or a label, not a button, a slider or the canvas (they consume their touches) — and
 * that didn't start inside one of the [exclude] rectangles (bounds in this node's coordinates).
 * Observes only: every touch still reaches whatever is under it.
 */
internal fun Modifier.unusedTaps(enabled: Boolean, exclude: () -> List<Rect>, onTap: () -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(exclude, onTap) {
        awaitEachGesture {
            // Final pass: the controls under the finger have had their say.
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (down.isConsumed) return@awaitEachGesture
            val slop = viewConfiguration.touchSlop
            val up = withTimeoutOrNull(TouchGestureClassifier.LONG_PRESS_TIMEOUT_MS) {
                var lifted: PointerInputChange? = null
                while (lifted == null) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    // A second finger, or moving away: not a tap.
                    val change = event.changes.singleOrNull() ?: break
                    if (change.id != down.id || (change.position - down.position).getDistance() > slop) break
                    if (!change.pressed) lifted = change
                }
                lifted
            }
            if (up != null && !up.isConsumed && exclude().none { it.contains(down.position) }) onTap()
        }
    }
}
