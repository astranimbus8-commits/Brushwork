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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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

/** Sheets and dialogs the editor can show (one at a time). */
enum class EditorPanel { TOOLS, BRUSH, COLOR, LAYERS, FILTERS, SELECTION, CANVAS, RULER, GRID, STABILIZER, SETTINGS }

/** The painting screen: canvas view, hotbar, top bar, side sliders, menus, panels. */
@Composable
fun EditorScreen(controller: EditorController, onExit: () -> Unit, onSaveNow: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember(controller) { EditorPrefs(controller.settings) }
    val actions = remember(controller, context) { EditorActions(controller, context) }
    var panel by rememberSaveable { mutableStateOf<EditorPanel?>(null) }
    // The canvas is only needed from event handlers, so a plain holder (not state) is enough.
    val canvasRef = remember { arrayOfNulls<CanvasView>(1) }
    val closePanel = { panel = null }

    // Chrome sizes (px), reported to the canvas so the initial fit centers between them.
    var topChromePx by remember { mutableIntStateOf(0) }
    var bottomChromePx by remember { mutableIntStateOf(0) }

    // Undo/redo tap feedback (texts are kept while the chips fade out).
    var tapText by remember { mutableStateOf("") }
    var tapVisible by remember { mutableStateOf(false) }
    var tapSerial by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapSerial) {
        if (tapSerial > 0) { delay(1200); tapVisible = false }
    }
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

    val session = controller.filterSession
    val busy = controller.busyMessage
    LaunchedEffect(session) { if (session != null && panel == EditorPanel.FILTERS) panel = null }
    // Back stops a cancellable operation; otherwise it waits for the operation to finish.
    BackHandler(enabled = busy != null) { controller.busyCancel?.invoke() }
    BackHandler(enabled = busy == null && session != null) { session?.cancel() }

    Box(Modifier.fillMaxSize().background(BrushworkColors.CanvasBackdrop)) {
        // ------------------------------------------------------------ canvas (full bleed)
        key(controller) {
            AndroidView(
                factory = { ctx -> CanvasView(ctx, controller).also { canvasRef[0] = it } },
                modifier = Modifier.fillMaxSize(),
                update = { v ->
                    v.onTapAction = { text -> tapText = text; tapVisible = true; tapSerial++ }
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
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
            ) {
                controller.docVersion // size changes (canvas resize) refresh the subtitle
                val doc = controller.doc
                EditorTopBar(
                    title = doc.name,
                    subtitle = "${doc.width} × ${doc.height} px",
                    onBack = onExit,
                    actions = listOf(
                        BarAction("Filters", Icons.Filled.PhotoFilter) { panel = EditorPanel.FILTERS },
                        BarAction("Selection", Icons.Filled.SelectAll) { panel = EditorPanel.SELECTION },
                        BarAction("Canvas", Icons.Filled.AspectRatio) { panel = EditorPanel.CANVAS },
                        BarAction("Ruler", Icons.Filled.Straighten, selected = controller.ruler.enabled) { panel = EditorPanel.RULER },
                        BarAction("Grid", Icons.Filled.GridOn, selected = controller.grid.enabled) { panel = EditorPanel.GRID },
                        BarAction("Stabilizer", Icons.Filled.Draw, selected = controller.stabilizer.mode != StabilizerMode.OFF) { panel = EditorPanel.STABILIZER },
                    ),
                    menu = listOf(
                        MenuEntry("Import picture", Icons.Filled.AddPhotoAlternate) { launchImport() },
                        MenuEntry("Export PNG", Icons.Filled.SaveAlt) { requestExport(ExportFormat.PNG) },
                        MenuEntry("Export JPG", Icons.Filled.Image) { requestExport(ExportFormat.JPEG) },
                        MenuEntry("Share", Icons.Filled.Share) { actions.share() },
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
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(BrushworkColors.Chrome.copy(alpha = 0.82f))
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                ToolOptionsBar(controller, Modifier.fillMaxWidth())
            }
        }

        val topDp = with(density) { topChromePx.toDp() }
        val bottomDp = with(density) { bottomChromePx.toDp() }

        // ------------------------------------------------------------ side sliders
        if (session == null) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(top = topDp + 8.dp, bottom = bottomDp + 76.dp)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            ) {
                // Two sliders + labels + eyedropper need ~130dp besides the tracks.
                val stacked = (maxHeight - 130.dp) / 2
                val sideBySide = stacked < 90.dp
                val length = if (sideBySide) (maxHeight - 90.dp).coerceIn(60.dp, 200.dp) else stacked.coerceAtMost(190.dp)
                SideSliders(
                    controller = controller,
                    sliderLength = length,
                    sideBySide = sideBySide,
                    onDragChange = { draggingSlider = it },
                    modifier = Modifier
                        .align(if (prefs.leftHanded) Alignment.CenterEnd else Alignment.CenterStart)
                        .padding(horizontal = 6.dp),
                )
            }
        }

        // ------------------------------------------------------------ bottom chrome
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            val tool = controller.currentTool
            if (session == null && tool.hasPendingWork) {
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
                Hotbar(
                    controller = controller,
                    onToolPicker = { panel = EditorPanel.TOOLS },
                    onBrushPanel = { panel = EditorPanel.BRUSH },
                    onColorPanel = { panel = EditorPanel.COLOR },
                    onLayersPanel = { panel = EditorPanel.LAYERS },
                    modifier = Modifier.onSizeChanged { bottomChromePx = it.height },
                )
            }
        }

        // ------------------------------------------------------------ transient feedback
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = topDp + 12.dp),
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
        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomDp + 8.dp),
        ) { data ->
            Snackbar(data, containerColor = BrushworkColors.ChromeHigh, contentColor = BrushworkColors.OnChrome, actionColor = BrushworkColors.Accent)
        }

        // ------------------------------------------------------------ busy scrim (blocks input)
        if (busy != null) BusyOverlay(busy, controller.busyProgress, onCancel = controller.busyCancel)
    }

    // ---------------------------------------------------------------- panels
    when (panel) {
        EditorPanel.TOOLS -> ToolPickerSheet(controller, closePanel)
        EditorPanel.BRUSH -> BrushPanel(controller, closePanel)
        EditorPanel.COLOR -> ColorPickerPanel(controller, closePanel)
        EditorPanel.LAYERS -> LayersPanel(controller, closePanel, onImportPicture = { panel = null; launchImport() })
        EditorPanel.FILTERS -> FilterBrowser(controller, closePanel)
        EditorPanel.SELECTION -> SelectionPanel(controller, closePanel)
        EditorPanel.CANVAS -> CanvasAdjustDialog(controller, closePanel)
        EditorPanel.RULER -> RulerPanel(controller, closePanel)
        EditorPanel.GRID -> GridPanel(controller, closePanel)
        EditorPanel.STABILIZER -> StabilizerPanel(controller, closePanel)
        EditorPanel.SETTINGS -> EditorSettingsDialog(prefs, closePanel)
        null -> {}
    }
}
