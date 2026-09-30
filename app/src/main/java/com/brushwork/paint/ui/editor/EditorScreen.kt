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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoFilter
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.ui.assist.GridPanel
import com.brushwork.paint.ui.assist.RulerPanel
import com.brushwork.paint.ui.assist.StabilizerPanel
import com.brushwork.paint.ui.brush.BrushPanel
import com.brushwork.paint.ui.canvas.CanvasAdjustDialog
import com.brushwork.paint.ui.color.ColorPickerPanel
import com.brushwork.paint.ui.filters.FilterBrowser
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.selection.SelectionPanel
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.tools.ToolOptionsBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Sheets and dialogs the editor can show (one at a time; the layers window is separate). */
enum class EditorPanel { TOOLS, BRUSH, COLOR, FILTERS, SELECTION, CANVAS, RULER, GRID, STABILIZER, SETTINGS }

/** Panels that edit the document, the selection, the active layer or the tool: closed while a filter is previewed. */
private val PANELS_BLOCKED_BY_FILTER = setOf(
    EditorPanel.TOOLS, EditorPanel.BRUSH, EditorPanel.COLOR,
    EditorPanel.FILTERS, EditorPanel.SELECTION, EditorPanel.CANVAS,
)

/**
 * The painting screen: canvas view, top bar + tool options, the brush slider bar above the
 * hotbar, the floating selection bar and layers window, menus and panels.
 */
@Composable
fun EditorScreen(controller: EditorController, onExit: () -> Unit, onSaveNow: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember(controller) { EditorPrefs(controller.settings) }
    val actions = remember(controller, context) { EditorActions(controller, context) }
    var panel by rememberSaveable { mutableStateOf<EditorPanel?>(null) }
    // The layers window is not modal (the canvas stays usable), so it lives beside [panel]; it
    // hides while a sheet or dialog is up and comes back when that closes.
    var layersOpen by rememberSaveable { mutableStateOf(false) }
    // Typed brush size / opacity (tapping a value of the slider bar).
    var editingValue by rememberSaveable { mutableStateOf<SliderKind?>(null) }
    // The clipboard content whose paste bar the user hid (shown again for a new copy).
    var hiddenClipboard by remember { mutableStateOf<EditorController.ClipboardImage?>(null) }
    // The canvas is only needed from event handlers, so a plain holder (not state) is enough.
    val canvasRef = remember { arrayOfNulls<CanvasView>(1) }
    val closePanel = { panel = null }

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
    var bottomChromePx by remember { mutableIntStateOf(0) }
    var selectionBarPx by remember { mutableIntStateOf(0) }

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

    // ---------------------------------------------------------------- snackbar messages
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
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
    // Back stops a cancellable operation; otherwise it waits for the operation to finish.
    BackHandler(enabled = busy != null) { controller.busyCancel?.invoke() }
    BackHandler(enabled = busy == null && session != null) { session?.cancel() }
    // The layers window is not a dialog: Back closes it before leaving the editor.
    BackHandler(enabled = busy == null && session == null && layersOpen && panel == null) { layersOpen = false }

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
    val layersVisible = layersOpen && session == null && panel == null
    // The selection bar steps aside for the user's tool work in progress (its ✓/✕ come first:
    // a paste being placed, curve points...), filters, long operations and the selection menu
    // itself. The transform tool's own untouched lift doesn't count: selecting it to move the
    // selection keeps Copy / Deselect at hand.
    val selectionBarVisible = session == null && busy == null && !toolHasUserChanges && panel != EditorPanel.SELECTION &&
        (hasSelection || (clipboard != null && clipboard !== hiddenClipboard))

    Box(Modifier.fillMaxSize().background(BrushworkColors.CanvasBackdrop)) {
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
                    v.setMirrored(controller.viewMirrored)
                    v.setFitInsets(0f, topChromePx.toFloat(), 0f, bottomChromePx.toFloat())
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
                        BarAction("Filters", Icons.Filled.PhotoFilter, enabled = docActionsEnabled) { panel = EditorPanel.FILTERS },
                        BarAction("Selection", Icons.Filled.SelectAll, enabled = docActionsEnabled) { panel = EditorPanel.SELECTION },
                        BarAction("Canvas", Icons.Filled.AspectRatio, enabled = docActionsEnabled) { panel = EditorPanel.CANVAS },
                        BarAction("Ruler", Icons.Filled.Straighten, selected = rulerOn) { panel = EditorPanel.RULER },
                        BarAction("Grid", Icons.Filled.GridOn, selected = gridOn) { panel = EditorPanel.GRID },
                        BarAction("Stabilizer", Icons.Filled.Draw, selected = stabilizerOn) { panel = EditorPanel.STABILIZER },
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
                        MenuEntry("Export PNG", Icons.Filled.SaveAlt, enabled = docActionsEnabled) { requestExport(ExportFormat.PNG) },
                        MenuEntry("Export JPG", Icons.Filled.Image, enabled = docActionsEnabled) { requestExport(ExportFormat.JPEG) },
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
                        MenuEntry("Settings", Icons.Filled.Settings) { panel = EditorPanel.SETTINGS },
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
            }
        }

        val topDp = with(density) { topChromePx.toDp() }
        val bottomDp = with(density) { bottomChromePx.toDp() }
        val selectionBarDp = if (selectionBarVisible) with(density) { selectionBarPx.toDp() } + 6.dp else 0.dp

        // ------------------------------------------------------------ bottom chrome
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                        onToolPicker = { panel = EditorPanel.TOOLS },
                        onBrushPanel = { panel = EditorPanel.BRUSH },
                        onColorPanel = { panel = EditorPanel.COLOR },
                        onLayersPanel = { layersOpen = !layersVisible },
                        layersOpen = layersVisible,
                        onHistory = showFeedback,
                    )
                }
            }
        }

        // ------------------------------------------------------------ selection actions
        if (selectionBarVisible) {
            SelectionActionBar(
                controller = controller,
                onMore = { panel = EditorPanel.SELECTION },
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
                LayersPanel(
                    controller = controller,
                    onDismiss = { layersOpen = false },
                    onImportPicture = { launchImport() },
                    modifier = Modifier.align(Alignment.BottomEnd),
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
        // Messages must not land on the layers window (a snackbar would cover its action row and
        // take its taps): while it is open they show near the top, under the tap feedback chip.
        val snackbarPlacement = if (layersVisible) {
            Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(top = topDp + selectionBarDp + 52.dp)
        } else {
            Modifier.align(Alignment.BottomCenter).padding(bottom = bottomDp + 8.dp)
        }
        SnackbarHost(snackbar, modifier = snackbarPlacement) { data ->
            Snackbar(data, containerColor = BrushworkColors.ChromeHigh, contentColor = BrushworkColors.OnChrome, actionColor = BrushworkColors.Accent)
        }

        // ------------------------------------------------------------ busy scrim (blocks input)
        if (busy != null) BusyOverlay(busy, progress = { controller.busyProgress }, onCancel = controller.busyCancel)
    }

    // ---------------------------------------------------------------- panels
    when (panel) {
        EditorPanel.TOOLS -> ToolPickerSheet(controller, closePanel)
        EditorPanel.BRUSH -> BrushPanel(controller, closePanel)
        EditorPanel.COLOR -> ColorPickerPanel(controller, closePanel)
        EditorPanel.FILTERS -> FilterBrowser(controller, closePanel)
        EditorPanel.SELECTION -> SelectionPanel(controller, closePanel)
        EditorPanel.CANVAS -> CanvasAdjustDialog(controller, closePanel)
        EditorPanel.RULER -> RulerPanel(controller, closePanel)
        EditorPanel.GRID -> GridPanel(controller, closePanel)
        EditorPanel.STABILIZER -> StabilizerPanel(controller, closePanel)
        EditorPanel.SETTINGS -> EditorSettingsDialog(prefs, closePanel)
        null -> {}
    }
    editingValue?.let { kind -> BrushValueDialog(controller, kind) { editingValue = null } }
}

