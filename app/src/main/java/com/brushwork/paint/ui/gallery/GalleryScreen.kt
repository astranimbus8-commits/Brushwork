package com.brushwork.paint.ui.gallery

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.ProjectInfo
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.checkerboard
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class ProjectAction(val label: String, val icon: ImageVector) {
    RENAME("Rename", Icons.Outlined.DriveFileRenameOutline),
    DUPLICATE("Duplicate", Icons.Outlined.ContentCopy),
    EXPORT_PNG("Export PNG", Icons.Outlined.Image),
    EXPORT_JPG("Export JPG", Icons.Outlined.Photo),
    SHARE("Share", Icons.Outlined.Share),
    DELETE("Delete", Icons.Outlined.Delete),
}

/** Grid of saved artworks + "new canvas" (sizes/units/presets) + import/export/rename/delete. */
@Composable
fun GalleryScreen(repository: ProjectRepository, onOpenProject: (id: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val changes by repository.changes.collectAsState()
    var resumeTick by remember { mutableIntStateOf(0) }
    var projects by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(changes, resumeTick) {
        projects = try {
            repository.list()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        now = System.currentTimeMillis()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeTick++ }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }

    var busy by remember { mutableStateOf<String?>(null) }
    var showNewCanvas by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ProjectInfo?>(null) }
    var deleteTarget by remember { mutableStateOf<ProjectInfo?>(null) }

    fun message(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(
                text,
                actionLabel = actionLabel,
                withDismissAction = actionLabel != null,
                duration = if (actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    /** Runs a long operation behind a progress overlay; failures become a snackbar. */
    fun runBusy(label: String, failure: String, block: suspend () -> Unit) {
        if (busy != null) return
        busy = label
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                message("$failure: not enough memory")
            } catch (e: Exception) {
                message(e.message?.let { "$failure: $it" } ?: failure)
            } finally {
                busy = null
            }
        }
    }

    val storageGate = rememberStoragePermissionGate(onDenied = { message("Allow storage access to save pictures to your gallery") })

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            runBusy("Importing picture…", "Could not import the picture") {
                val id = repository.createFromImage(uri)
                onOpenProject(id)
            }
        }
    }
    val importPicture: () -> Unit = {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (e: ActivityNotFoundException) {
            message("No app is available to pick pictures")
        }
    }

    fun export(info: ProjectInfo, format: ExportFormat) = storageGate {
        runBusy("Exporting ${format.name}…", "Export failed") {
            val uri = repository.exportProject(info.id, format) ?: throw IOException("the image could not be saved")
            message("Saved to Pictures/Brushwork", actionLabel = if (uri.scheme == "content") "View" else null) {
                viewImage(context, uri, format.mimeType)
            }
        }
    }

    fun onAction(info: ProjectInfo, action: ProjectAction) {
        when (action) {
            ProjectAction.RENAME -> renameTarget = info
            ProjectAction.DUPLICATE -> runBusy("Duplicating…", "Could not duplicate") {
                repository.duplicate(info.id)
                message("Duplicated “${info.name}”")
            }
            ProjectAction.EXPORT_PNG -> export(info, ExportFormat.PNG)
            ProjectAction.EXPORT_JPG -> export(info, ExportFormat.JPEG)
            ProjectAction.SHARE -> runBusy("Preparing to share…", "Could not share") {
                val uri = repository.shareProject(info.id, ExportFormat.PNG) ?: throw IOException("the image could not be prepared")
                shareImage(context, uri, ExportFormat.PNG.mimeType, info.name)
            }
            ProjectAction.DELETE -> deleteTarget = info
        }
    }

    // Don't leave the app halfway through an import/export/delete.
    BackHandler(enabled = busy != null) {}

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text("Brushwork", fontWeight = FontWeight.SemiBold) },
                    actions = {
                        TextButton(onClick = importPicture) {
                            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Import picture")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showNewCanvas = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("New canvas") },
                    containerColor = BrushworkColors.Accent,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            val list = projects
            when {
                list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                list.isEmpty() -> EmptyGallery(
                    modifier = Modifier.padding(padding),
                    onNewCanvas = { showNewCanvas = true },
                    onImport = importPicture,
                )
                else -> {
                    val dir = LocalLayoutDirection.current
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = padding.calculateStartPadding(dir) + 12.dp,
                            top = padding.calculateTopPadding() + 4.dp,
                            end = padding.calculateEndPadding(dir) + 12.dp,
                            bottom = padding.calculateBottomPadding() + 96.dp, // room for the FAB
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(list, key = { it.id }) { info ->
                            ProjectCard(
                                info = info,
                                now = now,
                                onOpen = { if (busy == null) onOpenProject(info.id) },
                                onAction = { onAction(info, it) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }
        busy?.let { BusyOverlay(it) }
    }

    if (showNewCanvas) {
        NewCanvasDialog(
            creating = creating,
            onDismiss = { showNewCanvas = false },
            onCreate = { spec ->
                creating = true
                scope.launch {
                    try {
                        val id = repository.create(spec)
                        showNewCanvas = false
                        onOpenProject(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        showNewCanvas = false
                        message("Could not create the canvas: ${e.message ?: e.javaClass.simpleName}")
                    } finally {
                        creating = false
                    }
                }
            },
        )
    }

    renameTarget?.let { info ->
        RenameDialog(
            initial = info.name,
            onDismiss = { renameTarget = null },
            onRename = { newName ->
                renameTarget = null
                scope.launch {
                    try {
                        repository.rename(info.id, newName)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message("Could not rename: ${e.message}")
                    }
                }
            },
        )
    }

    deleteTarget?.let { info ->
        BwDialog(
            title = "Delete artwork?",
            onDismiss = { deleteTarget = null },
            confirmText = "Delete",
            onConfirm = {
                deleteTarget = null
                runBusy("Deleting…", "Could not delete") {
                    repository.delete(info.id)
                    message("Deleted “${info.name}”")
                }
            },
        ) {
            Text("“${info.name}” will be permanently deleted. This can't be undone.")
        }
    }
}

@Composable
private fun ProjectCard(
    info: ProjectInfo,
    now: Long,
    onOpen: () -> Unit,
    onAction: (ProjectAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(BrushworkColors.Chrome)
            .border(1.dp, BrushworkColors.ChromeBorder, shape)
            .combinedClickable(
                onClickLabel = "Open",
                onClick = onOpen,
                onLongClickLabel = "More options",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuOpen = true
                },
            ),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color(0xFF17181A))
                .padding(10.dp),
            contentAlignment = Alignment.Center,
        ) {
            ProjectThumbnail(info)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(info.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${info.width} × ${info.height} px",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    maxLines = 1,
                )
                Text(
                    relativeTime(info.modifiedAt, now),
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options for ${info.name}")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    for (action in ProjectAction.entries) {
                        val tint = if (action == ProjectAction.DELETE) BrushworkColors.Danger else BrushworkColors.OnChrome
                        DropdownMenuItem(
                            text = { Text(action.label, color = tint) },
                            leadingIcon = { Icon(action.icon, contentDescription = null, tint = tint) },
                            onClick = {
                                menuOpen = false
                                onAction(action)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** The artwork's thumbnail on a checkerboard, with the canvas' proportions. Decoded off the main thread. */
@Composable
private fun ProjectThumbnail(info: ProjectInfo) {
    val file = info.thumbnail
    val image by produceState(initialValue = file?.let { ThumbnailCache.peek(it) }, file, info.modifiedAt) {
        value = if (file == null) null else withContext(Dispatchers.IO) { ThumbnailCache.load(file, info.modifiedAt) } ?: value
    }
    val ratio = (info.width.toFloat() / info.height.coerceAtLeast(1)).coerceIn(0.05f, 20f)
    Box(
        Modifier
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(3.dp))
            .checkerboard(5.dp),
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillBounds,
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

@Composable
private fun EmptyGallery(modifier: Modifier, onNewCanvas: () -> Unit, onImport: () -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Palette,
                contentDescription = null,
                tint = BrushworkColors.Accent,
                modifier = Modifier.size(72.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text("Your gallery is empty", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                "Start a new canvas to paint something, or import a picture to trace or color over.",
                style = MaterialTheme.typography.bodyMedium,
                color = BrushworkColors.OnChromeDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onNewCanvas, colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("New canvas")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onImport) {
                Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Import picture")
            }
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focus = remember { FocusRequester() }
    val submit = { if (value.text.isNotBlank()) onRename(value.text.trim()) }
    BwDialog(title = "Rename artwork", onDismiss = onDismiss, confirmText = "Rename", onConfirm = submit) {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it.copy(text = it.text.take(100)) },
            label = { Text("Name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) {
        // The dialog window is composed a frame later; focus once it is attached.
        delay(150)
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun BusyOverlay(label: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            // Swallow touches so nothing else starts while we work.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Surface(color = BrushworkColors.ChromeHigh, shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Text(label, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun relativeTime(time: Long, now: Long): String =
    if (now - time < DateUtils.MINUTE_IN_MILLIS) "Just now"
    else DateUtils.getRelativeTimeSpanString(time, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

private fun shareImage(context: Context, uri: Uri, mimeType: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, title)
        clipData = ClipData.newRawUri(title, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, "Share artwork")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

private fun viewImage(context: Context, uri: Uri, mimeType: String) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context !is Activity) view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (e: RuntimeException) {
        // No viewer app (ActivityNotFoundException) or the URI grant was refused
        // (SecurityException on some devices): the picture is saved either way.
    }
}
