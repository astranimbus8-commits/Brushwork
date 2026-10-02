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
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Polyline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Straighten
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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
import com.brushwork.paint.ui.common.LocalSheetGroup
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.IncrementsSheet
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetHostState
import com.brushwork.paint.ui.common.SheetPill
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.layers.LayerDeleteUndo
import com.brushwork.paint.ui.layers.LayerListMath
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.selection.SelectionPanel
import com.brushwork.paint.ui.exchange.ExchangeHost
import com.brushwork.paint.ui.exchange.rememberExchangeUi
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.CoordinatePill
import com.brushwork.paint.ui.tools.ToolOptionsBar
import com.brushwork.paint.ui.vector.VectorObjectBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Sheets and dialogs the editor chrome can show (one at a time; tool sheets and the layers
 * window are separate).
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
 * The painting screen: canvas view, top bar + tool options, the brush slider bar above the
 * hotbar, the floating selection bar and layers window, menus and panels.
 *
 * Menus (every [com.brushwork.paint.ui.common.BwSheet]: the chrome panels and the tools' own
 * sheets) are non-modal panels drawn by a [SheetHost] above the hotbar. A touch on the canvas
 * minimizes the open one to a pill and goes on to the canvas (zoom, pan, move objects); the pill
 * restores it. The layers window is closed by a tap outside it (that tap does nothing else);
 * drawing and pinching outside it keep working with it open. Layers window and panels share the
 * bottom of the screen: an open panel hides the window (it comes back when the panel is closed,
 * not when the panel is minimized to use the canvas), and the Layers button minimizes the panels.
 * A panel's button brings that panel back on top, also when a tool's sheet covers it.
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
    // The layers window is not modal (the canvas stays usable), so it lives beside [panel]; it
    // hides while a panel is shown (see layersVisible).
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    // Typed brush size / opacity (tapping a value of the slider bar).
    var editingValue by rememberSaveable { mutableStateOf<SliderKind?>(null) }
    // The clipboard content whose paste bar the user hid (shown again for a new copy).
    var hiddenClipboard by remember { mutableStateOf<EditorController.ClipboardImage?>(null) }
    // The canvas is only needed from event handlers, so a plain holder (not state) is enough.
    val canvasRef = remember { arrayOfNulls<CanvasView>(1) }
    val closePanel = { panel = null }
    // The button of a panel that is already open (minimized to its pill, or covered by a tool's
    // sheet) brings it back on top, as it was.
    val openPanel = { p: EditorPanel -> if (panel == p) sheetHost.bringToFront(p) else panel = p }

    // The editor chrome is always dark: light system bar icons whatever the system theme
    // (edge-to-edge picks them from it), restored when the editor closes.
    val hostView = LocalView.current
    DisposableEffect(hostView) {
        val window = hostView.context.findActivity()?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, hostView) }
        val lightStatus = bars?.isAppearanceLightStatusBars ?: false
        val lightNav = bars?.isAppearanceLightNavigationBars ?: false
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = lightStatus
            bars?.isAppearanceLightNavigationBars = lightNav
        }
    }

    // Chrome sizes (px), reported to the canvas so the initial fit centers between them. The
    // bottom one is the slider bar + hotbar (never the floating ✓/✕ buttons, which come and go).
    var topChromePx by remember { mutableIntStateOf(0) }
    // The X / Y coordinate strip's part of the top chrome (not part of the fit inset).
    var stripPx by remember { mutableIntStateOf(0) }
    var bottomChromePx by remember { mutableIntStateOf(0) }
    var selectionBarPx by remember { mutableIntStateOf(0) }
    // The hotbar alone (panels sit right above it, over the slider bar) and everything stacked
    // at the bottom (chrome + floating ✓/✕ + a minimized panel's pill; messages go above it).
    var hotbarPx by remember { mutableIntStateOf(0) }
    var bottomStackPx by remember { mutableIntStateOf(0) }
    // Where the layers window is, so a tap on the chrome around it (not on it) closes it.
    val layersBounds = remember { WindowBounds() }

    // Undo/redo feedback (canvas taps and hotbar buttons; texts are kept while the chips fade out).
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
    // A layer deleted from the layers window (no confirmation): "Undo" right in the message.
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
            // The layers window only hides (see layersVisible): it is back once the filter is
            // applied or cancelled.
            editingValue = null
        }
    }
    // The layers window shows unless a panel is up (a minimized one's pill waits meanwhile), a
    // filter is previewed or the settings dialog is open.
    val layersVisible = layersOpen && session == null && !sheetHost.hasExpanded && panel != EditorPanel.SETTINGS
    // Back stops a cancellable operation; otherwise it waits for the operation to finish.
    BackHandler(enabled = busy != null) { controller.busyCancel?.invoke() }
    BackHandler(enabled = busy == null && session != null) { session?.cancel() }
    // The layers window and the panels are not dialogs: Back closes them before leaving the
    // editor (the window first, it is the one on screen; then the newest panel, minimized or not).
    BackHandler(enabled = busy == null && session == null && layersVisible) { layersOpen = false }
    BackHandler(enabled = busy == null && session == null && !layersVisible && !sheetHost.isEmpty) {
        controller.endCanvasGesture()
        sheetHost.dismissTop()
    }
    val closeLayers: () -> Unit = remember { { layersOpen = false } }
    // A touch on the canvas while a panel is up: the panel folds into its pill so the canvas can
    // be used (the touch goes on to the canvas). The layers window hidden under the panel is
    // closed too, or it would pop up in its place. The panel's own minimize button does the same.
    val onCanvasTouch: () -> Unit = remember(sheetHost) {
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

    Box(
        Modifier
            .fillMaxSize()
            .background(BrushworkColors.CanvasBackdrop)
            .onGloballyPositioned { layersBounds.root = it }
            // A tap on the chrome around the layers window that no control used closes it
            // (taps on the canvas are handled by the canvas: see onOutsideTap below).
            .unusedTaps(enabled = layersVisible, exclude = layersBounds::rect, onTap = closeLayers),
    ) {
        // ------------------------------------------------------------ canvas (full bleed)
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
                    // With the layers window open, a tap on the canvas only closes it.
                    v.onOutsideTap = if (layersVisible) closeLayers else null
                    v.setMirrored(controller.viewMirrored)
                    // The X / Y strip is excluded: it comes and goes without refitting the canvas (V11).
                    val strip = if (session == null) stripPx else 0
                    v.setFitInsets(0f, (topChromePx - strip).coerceAtLeast(0).toFloat(), 0f, bottomChromePx.toFloat())
                },
                onRelease = { v -> if (canvasRef[0] === v) canvasRef[0] = null },
            )
        }

        // ------------------------------------------------------------ top chrome
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { topChromePx = it.height }) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(BrushworkColors.Chrome)
                    .blockCanvasTouches()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                controller.docVersion // size changes (canvas resize) refresh the subtitle
                val doc = controller.doc
                EditorTopBar(
                    title = doc.name,
                    subtitle = "${doc.width} × ${doc.height} px",
                    onBack = onExit,
                    actions = listOf(
                        // Vector mode (v1.5): on while the active layer is a vector layer. Filters
                        // moved into the tools grid.
                        BarAction("Vector", EditorIcons.Vector, selected = vectorMode, enabled = docActionsEnabled) {
                            controller.endCanvasGesture()
                            controller.toggleVectorMode()
                        },
                        BarAction("Selection", Icons.Filled.SelectAll, enabled = docActionsEnabled) { openPanel(EditorPanel.SELECTION) },
                        BarAction("Canvas", Icons.Filled.AspectRatio, enabled = docActionsEnabled) { openPanel(EditorPanel.CANVAS) },
                        BarAction("Ruler", Icons.Filled.Straighten, selected = rulerOn) { openPanel(EditorPanel.RULER) },
                        BarAction("Grid", Icons.Filled.GridOn, selected = gridOn) { openPanel(EditorPanel.GRID) },
                        BarAction("Stabilizer", Icons.Filled.Draw, selected = stabilizerOn) { openPanel(EditorPanel.STABILIZER) },
                    ),
                    menu = listOf(
                        MenuEntry(if (hasSelection) "Copy selection" else "Copy layer", Icons.Filled.ContentCopy, enabled = docActionsEnabled) {
                            controller.endCanvasGesture()
                            controller.copySelection()
                        },
                        MenuEntry("Paste", Icons.Filled.ContentPaste, enabled = docActionsEnabled && clipboard != null) {
                            controller.endCanvasGesture()
                            controller.paste()
                        },
                        MenuEntry("Import picture", Icons.Filled.AddPhotoAlternate, enabled = docActionsEnabled, dividerBefore = true) { launchImport() },
                        MenuEntry("Import SVG or PDF…", Icons.Filled.FileOpen, enabled = docActionsEnabled) {
                            controller.endCanvasGesture()
                            exchange.requestImport()
                        },
                        MenuEntry("Export PNG", Icons.Filled.SaveAlt, enabled = docActionsEnabled) { requestExport(ExportFormat.PNG) },
                        MenuEntry("Export JPG", Icons.Filled.Image, enabled = docActionsEnabled) { requestExport(ExportFormat.JPEG) },
                        MenuEntry("Export SVG…", Icons.Filled.Polyline, enabled = docActionsEnabled) {
                            controller.endCanvasGesture()
                            exchange.requestExport(VectorFormat.SVG)
                        },
                        MenuEntry("Export PDF…", Icons.Filled.PictureAsPdf, enabled = docActionsEnabled) {
                            controller.endCanvasGesture()
                            exchange.requestExport(VectorFormat.PDF)
                        },
                        MenuEntry("Share", Icons.Filled.Share, enabled = docActionsEnabled) { actions.share() },
                        MenuEntry("Flip view", Icons.Filled.Flip, checked = controller.viewMirrored, dividerBefore = true) {
                            controller.viewMirrored = !controller.viewMirrored
                        },
                        MenuEntry("Fit to screen", Icons.Filled.FitScreen) { canvasRef[0]?.fitToScreen() },
                        MenuEntry("100% (actual pixels)", Icons.Filled.CenterFocusStrong) { canvasRef[0]?.actualPixels() },
                        MenuEntry("Reset rotation", Icons.Filled.CropRotate) { canvasRef[0]?.resetRotation() },
                        MenuEntry("Save now", Icons.Filled.Save, dividerBefore = true) {
                            onSaveNow()
                            controller.toast("Saved")
                        },
                        MenuEntry("Settings", Icons.Filled.Settings) { openPanel(EditorPanel.SETTINGS) },
                    ),
                )
            }
            // Tool options act on the document; the tool is not usable while a filter is previewed.
            if (session == null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(BrushworkColors.Chrome.copy(alpha = 0.82f))
                        .blockCanvasTouches()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                ) {
                    ToolOptionsBar(controller, Modifier.fillMaxWidth())
                }
                // X / Y of what the tool is placing (v1.5). Part of the top chrome (overlays stay
                // below it) but left out of the canvas fit inset, so it never moves the canvas.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { stripPx = it.height }
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
                ) {
                    // v1.6: the X / Y pill (area G; the foundation stub shows the v1.5 strip).
                    CoordinatePill(controller)
                }
            }
        }

        val topDp = with(density) { topChromePx.toDp() }
        val bottomDp = with(density) { bottomChromePx.toDp() }
        val selectionBarDp = if (selectionBarVisible || objectBarVisible) with(density) { selectionBarPx.toDp() } + 6.dp else 0.dp

        // ------------------------------------------------------------ bottom chrome
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomStackPx = it.height },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // A minimized panel waits here, above the ✓ / ✕ (not beside: no room on a phone).
            if (!layersVisible) {
                SheetPill(
                    sheetHost,
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                )
            }
            if (pendingWork && !layersVisible) {
                PendingWorkBar(controller, tool, Modifier.padding(bottom = 12.dp))
            }
            if (session != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { bottomChromePx = it.height }
                        .background(BrushworkColors.Chrome)
                        .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
                ) {
                    FilterSessionPanel(session, Modifier.fillMaxWidth())
                }
            } else {
                Column(Modifier.fillMaxWidth().onSizeChanged { bottomChromePx = it.height }) {
                    // Brush size + opacity directly above the hotbar.
                    BrushSliderBar(
                        controller = controller,
                        leftHanded = prefs.leftHanded,
                        onDragChange = { draggingSlider = it },
                        onEditValue = { kind -> controller.endCanvasGesture(); editingValue = kind },
                    )
                    Hotbar(
                        controller = controller,
                        onToolPicker = { openPanel(EditorPanel.TOOLS) },
                        onBrushPanel = { openPanel(EditorPanel.BRUSH) },
                        onColorPanel = { openPanel(EditorPanel.COLOR) },
                        onLayersPanel = {
                            if (layersVisible) {
                                layersOpen = false
                            } else {
                                // Open panels make room (minimized, not closed).
                                sheetHost.minimize()
                                layersOpen = true
                            }
                        },
                        layersOpen = layersVisible,
                        onHistory = showFeedback,
                        modifier = Modifier.onSizeChanged { hotbarPx = it.height },
                    )
                }
            }
        }

        // ------------------------------------------------------------ selection actions
        if (objectBarVisible) {
            // Selected vector objects get their own bar in the selection bar's place (v1.5).
            VectorObjectBar(
                controller,
                Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(top = topDp + 6.dp, start = 8.dp, end = 8.dp)
                    .onSizeChanged { selectionBarPx = it.height },
            )
        } else if (selectionBarVisible) {
            SelectionActionBar(
                controller = controller,
                onMore = { openPanel(EditorPanel.SELECTION) },
                onHide = if (hasSelection) null else ({ hiddenClipboard = controller.clipboard }),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(top = topDp + 6.dp, start = 8.dp, end = 8.dp)
                    .onSizeChanged { selectionBarPx = it.height },
            )
        }

        // ------------------------------------------------------------ layers window (non-modal)
        if (layersVisible) {
            // Only the window itself takes touches: the canvas around it keeps working.
            Box(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(top = topDp + selectionBarDp + 8.dp, bottom = bottomDp + 8.dp, end = 8.dp),
            ) {
                // v1.6 sizing contract: the host sizes the layer window, LayersPanel fills it (area E
                // sets the ibisPaint 382 × 520; until then the v1.5 size, shrunk to the room here).
                val config = LocalConfiguration.current
                val window = LayerListMath.windowSize(config.screenWidthDp, config.screenHeightDp)
                LayersPanel(
                    controller = controller,
                    onDismiss = { layersOpen = false },
                    onImportPicture = { launchImport() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(window.width.dp, window.height.dp)
                        .onGloballyPositioned { layersBounds.window = it },
                    onLayerDeleted = onLayerDeleted,
                    onOpenPanel = openPanel,
                )
            }
            if (pendingWork) {
                // The window covers the middle of the bottom: the ✓/✕ move to the free left edge.
                PendingWorkBar(
                    controller, tool,
                    Modifier
                        .align(Alignment.BottomStart)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .padding(start = 10.dp, bottom = bottomDp + 12.dp),
                    vertical = true,
                )
            }
        }

        // ------------------------------------------------------------ panels (non-modal)
        // Right above the hotbar (over the slider bar), or above the filter panel.
        val panelBottom = with(density) { (if (session != null || hotbarPx == 0) bottomChromePx else hotbarPx).toDp() }
        SheetHost(sheetHost, bottomInset = panelBottom, modifier = Modifier.padding(top = topDp), onMinimize = onCanvasTouch)

        // ------------------------------------------------------------ transient feedback
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = topDp + selectionBarDp + 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(visible = tapVisible, enter = fadeIn(), exit = fadeOut()) { InfoChip(tapText) }
            AnimatedVisibility(visible = gestureVisible && !tapVisible, enter = fadeIn(), exit = fadeOut()) {
                val deg = gestureInfo.rotation.roundToInt()
                InfoChip(SliderMath.formatZoom(gestureInfo.zoom) + if (deg != 0) "  ·  $deg°" else "")
            }
        }
        draggingSlider?.let { kind ->
            SliderPreview(controller, kind, zoom = canvasRef[0]?.zoom ?: 1f, modifier = Modifier.align(Alignment.Center))
        }
        // Messages must not land on the layers window or a panel (a snackbar would cover their
        // controls and take their taps): while one is open they show near the top, under the tap
        // feedback chip. Otherwise above everything at the bottom (✓ / ✕, a panel's pill).
        val snackbarPlacement = if (layersVisible || sheetHost.hasExpanded) {
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(top = topDp + selectionBarDp + 52.dp)
        } else {
            Modifier.align(Alignment.BottomCenter).padding(bottom = maxOf(bottomDp, with(density) { bottomStackPx.toDp() }) + 8.dp)
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
            EditorPanel.TOOLS -> ToolPickerSheet(controller, closePanel, onOpenFilters = { openPanel(EditorPanel.FILTERS) })
            EditorPanel.BRUSH -> BrushPanel(controller, closePanel)
            EditorPanel.COLOR -> ColorPickerPanel(controller, closePanel)
            EditorPanel.FILTERS -> FilterBrowser(controller, closePanel)
            EditorPanel.SELECTION -> SelectionPanel(controller, closePanel)
            EditorPanel.CANVAS -> CanvasAdjustDialog(controller, closePanel)
            EditorPanel.RULER -> RulerPanel(controller, closePanel)
            EditorPanel.GRID -> GridPanel(controller, closePanel)
            EditorPanel.STABILIZER -> StabilizerPanel(controller, closePanel)
            EditorPanel.SETTINGS -> EditorSettingsDialog(prefs, closePanel, onCanvasChanged = { controller.invalidateDoc(null) })
            EditorPanel.INCREMENTS -> IncrementsSheet(controller, closePanel)
            null -> {}
        }
    }
    editingValue?.let { kind -> BrushValueDialog(controller, kind) { editingValue = null } }
    ExchangeHost(exchange)
}

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
 * that didn't start inside [exclude] (bounds in this node's coordinates). Observes only: every
 * touch still reaches whatever is under it.
 */
internal fun Modifier.unusedTaps(enabled: Boolean, exclude: () -> Rect?, onTap: () -> Unit): Modifier {
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
            if (up != null && !up.isConsumed && exclude()?.contains(down.position) != true) onTap()
        }
    }
}

