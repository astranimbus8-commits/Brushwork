# Brushwork architecture

Kotlin + Jetpack Compose UI, a custom `View` for the canvas, and a raster engine built on
`android.graphics` (Skia). Package root: `com.brushwork.paint` (`app/src/main/java/com/brushwork/paint`).

## Threading
Everything that touches the `Document`, layer bitmaps or the `EditorController` runs on the **main
thread**. Heavy pixel work (filters, flood fills on big canvases, segmentation, saving) runs on a
background dispatcher on *copies* of pixels and comes back to the main thread to apply results
(`withContext(Dispatchers.Main)` / `controller.runBusy {}`).

## Core model (`model/`)
- `Document` — size, dpi, color mode, `layers` (index 0 = bottom), `activeLayerIndex`, grid + ruler settings.
- `Layer` — `bitmap` (ARGB_8888, document-sized), optional grayscale `mask` (white = visible),
  `opacity`, `blendMode`, `visible`, `clipping` (clip to nearest non-clipping layer below),
  `alphaLocked`, `locked`, `editingMask`. Layers are referenced by identity, never by index.
  Call `layer.markChanged()` after pixel changes (the controller helpers do).
- `Selection` — immutable ALPHA_8 mask (255 = selected) + tight `bounds` + async marching-ants `outline`.
- `Settings.kt` — `GridSettings`, `RulerSettings`, `StabilizerSettings` (all `@Serializable`, all
  distances in DOCUMENT PIXELS; the `unit` field only affects display).

## Engine (`engine/`)
- `Compositor` — flattens layers (blend modes, opacity, masks, clipping groups). A tool can replace
  how ONE layer is drawn via `controller.renderOverride: LayerRenderOverride` (live stroke buffer,
  filter preview, transform preview, shape preview).
- `DisplayTiles` — 512 px tiles of the on-screen composite. `controller.invalidateDoc(rect)` marks
  tiles dirty; the canvas view calls `tiles.update(compositor)` then `tiles.draw(canvas, visible)`.
- `Undo.kt` — `UndoManager`, `UndoAction`, `PixelEditRecorder` (copy-on-write tile snapshots) and
  ready-made actions (add/remove/move layer, props, mask, selection, whole-document bitmaps, lambda).
- `BitmapUtils`, `BlendModes`, `ViewTransform` (doc <-> screen; `dp()`, `zoom`).

### How a pixel edit is recorded
```kotlin
if (!controller.checkEditable()) return
val rec = controller.beginEdit()          // active layer, content or mask (editingMask)
rec.touch(rect)                           // BEFORE modifying rect (snapshots tiles once)
// ... draw into rec.layer.paintTarget / layer.bitmap ...
controller.commitEdit(rec, "Brush")       // color-mode constraint + undo + redraw
// or rec.abort() to restore (cancelled gesture)
```
Whole-layer ops: `controller.editWholeLayer(layer, "Label") { bitmap -> ... }`.

## Controller (`EditorController`)
One per open document. Compose-observable state: `activeToolId`, `color`, `brush`/`eraser`/
`smudgeBrush`/`blurBrush` presets, `selection`, `ruler`, `grid`, `stabilizer`, `canUndo`/`canRedo`,
`layersVersion` (bump = layer panel refresh), `docVersion`, `busyMessage`, `message` (snackbar),
`filterSession`. Operations: tools (`selectTool`, `toggleEraser`), input dispatch
(`pointerDown/Move/Up/Cancel/LongPress` — called by the canvas view with DOCUMENT coordinates),
layers (add/delete/duplicate/move/merge/flip/props/masks/import), selection, undo/redo, filters.

## Tools (`tools/`)
`Tool` base class: `onDown/onMove/onUp/onCancel/onLongPress`, `drawOverlay(canvas, viewTransform)`
(screen space), `hasPendingWork/commit/discard` (editable objects such as shapes, text, curves,
transforms show ✓/✕ in the UI), `onActivate/onDeactivate`. Painting tools set
`usesStrokeAssist = true` so the controller runs input through `assist/StrokeAssist`
(ruler snapping + stabilizer). `ToolFactory` builds one instance per `ToolId`.

Two-finger tap = undo, three-finger tap = redo, pinch = zoom/rotate/pan: all handled by the canvas
view, which calls `pointerCancel()` if a second finger lands during a stroke (tools must then leave
no trace).

## Filters (`filters/`)
Pure Kotlin on `PixelBuffer` (non-premultiplied ARGB `IntArray`) — no android imports — so every
filter is unit-tested on the JVM (`AllFiltersTest` runs all of them). Parameters are declared with
`FilterParam` and the UI is generated from them. `FilterSession` (Android glue) previews on a
downscaled copy, applies at full resolution in the background, respects the selection and records
undo. `FilterMath` has shared helpers (blur, distance transform, noise, LUTs, parallel loops).

## Storage (`storage/`)
Each project is a folder in app-private storage: `project.json` (document + layer properties),
one compressed raw pixel file per layer/mask, and `thumb.png`. Saves are incremental (only layers
whose `contentVersion` changed). The editor autosaves every 45 s and whenever the app goes to the
background or the user returns to the gallery.

## UI (`ui/`)
`MainActivity` → gallery or editor. `ui/common/Components.kt` holds shared controls (sheets,
sliders, numeric/length fields with units + automatic sliders / drag-to-scrub, nudge pad, chips,
swatches) — use them everywhere.

Editor layout (`ui/editor`): top bar (panels + overflow menu), tool options strip
(`ToolOptionsBar`), floating selection bar (copy / cut / paste / deselect…) while a selection or
clipboard exists, the canvas, the brush size/opacity slider bar (values can be tapped and typed)
and the hotbar at the bottom. Panels are half-height, translucent bottom sheets (`BwSheet`); the
layers panel is a non-modal floating window in the bottom-right corner.

Gestures (`CanvasView`): one finger → the current tool (via the controller); two fingers → first
offered to the tool (`Tool.onTwoFingerStart`, e.g. pinch-scaling a transformed image, text or
shape), otherwise pan/zoom/rotate the view; two-finger tap = undo (tools with steps take back one
step via `Tool.undoStep`), three-finger tap = redo; holding still with a color tool picks a color
(`EditorController.pointerLongPress` → temporary eyedropper with a preview square).
