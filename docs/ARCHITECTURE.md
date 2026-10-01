# Brushwork architecture

Kotlin + Jetpack Compose UI, a custom `View` for the canvas, and a raster engine built on
`android.graphics` (Skia). Package root: `com.brushwork.paint` (`app/src/main/java/com/brushwork/paint`).

## Threading
Everything that touches the `Document`, layer bitmaps or the `EditorController` runs on the **main
thread**. Heavy pixel work (filters, flood fills on big canvases, segmentation, saving) runs on a
background dispatcher on *copies* of pixels and comes back to the main thread to apply results
(`withContext(Dispatchers.Main)` / `controller.runBusy {}`).

## Invariants (v1.5)
- **I1 Data and cache change together.** A layer with editable data (text, shape, vector content,
  a mask spec) keeps its pixels equal to the rendering of that data; both change together, on the
  main thread, in ONE undo step (`EditorController.updateLayerData` / `setLayerData`). Any other
  pixel edit clears the stale data in the same step (`commitEdit`): a CONTENT edit clears text,
  shape and vector data, a MASK edit clears the mask spec; an adjustment is never cleared.
  Adjustment layers have no pixels to edit: CONTENT edits of them are refused (`checkEditable`).
- **I2 One user action is one undo step, listeners included.** After `commitEdit`,
  `updateLayerData`, `setLayerData`, `groupUndo` or `undoStepNamed` returns, the history grew by
  exactly one step (or none). They run inside `editScope`; the `EditEvent`s they queue reach the
  `EditListener`s when the outermost scope ends, and a listener that edits folds its steps into the
  triggering one with `amendLastStep { }`. Undo and redo never fire edit events.
- **I3 Threads.** Document, layers and controller on the main thread only. Background renders work
  from immutable data (`VectorContent`, `MaskSpec`) and are applied on the main thread only if the
  layer's data is still the same instance. Busy overlay for anything over 400 ms.
- **I4 Compatibility.** v1.0–v1.4 projects open unchanged. `formatVersion` is 2 only when the
  project has an adjustment layer (older versions refuse it); otherwise 1, and v1.4 shows vector
  layers as their cached pixels. Unreadable v1.5 data affects only its own layer (load warning).
- **I5 The compositor is untouched without adjustment layers.** With none, `drawDocument` runs the
  v1.4 path bit for bit (golden tests against a copy of the v1.4 code). `AppSettings.safeCompositing`
  draws adjustment layers as pass-through (kill switch).
- **I6 One owner per file.** After the v1.5 foundation, frozen files (controller, models, storage,
  brush engine, editor UI…) change only on `main`; areas add behaviour through their own files and
  controller extension functions (`.wt/_tools/ownership-check.ps1` checks a branch).

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

## Text layers (`tools/text/`)
A text layer is a normal raster layer whose `Layer.textData` holds the serialized text object
(`TextCodec`: content, style, box, vertical mode, position, path). The text tool re-opens it for
editing (tap its text, or "Edit text" in the layers window) and re-renders the same layer with
`EditorController.updateTextLayer` (one undo step). Any other pixel edit rasterizes the layer
(`textData` cleared, undoably). Text on a path (`TextPathSpec`, `TextOnPath`, `TextPathGeometry`)
lays one line of text along a line, circle, rectangle or cubic curve, either bending the glyph
outlines along the path or rotating each letter rigidly.

## Shape layers (`tools/vector/Shape*`)
With "Editable (own layer)" on (the default), each new shape goes into its own layer whose
`Layer.shapeData` holds the serialized `ShapeObject` (`ShapeCodec`: type, box, look settings,
colors, the brush preset of brush outlines, optional custom points stored relative to the box).
The shape tool re-opens it when the shape is tapped (or "Edit shape" in the layers window), hides
the layer's pixels while it is edited, and re-renders it with `EditorController.updateShapeLayer`
(one undo step; brush outlines are replayed inside `keepLayerData` so they don't rasterize the
layer). "Points" mode turns any shape into anchors (`ShapePoints.kt`) that can be inserted, moved,
deleted and made sharp or smooth. As with text, any other pixel edit rasterizes the layer.

## Vector layers (`vector/`, v1.5)
A vector layer is a normal `Layer` whose `bitmap` is a render cache of `Layer.vector`, an immutable
`VectorContent` (`vector/VectorModel.kt`): objects in z-order — `VStroke` (a recorded brush stroke:
preset, color, seed and every input point with its raw pressure in `PackedPoints`, replayed by
`brush/StrokeRaster`), `VPath` (curves, polylines, imported SVG paths; anchors with optional handle
offsets and per-point width, fill and stroke) and `VShape` (a Shape tool `ShapeObject`). The
compositor, thumbnails, export, snapping and the eyedropper only see pixels.

**Vector mode is derived**: it is on exactly while the active layer is a vector layer
(`controller.isVectorMode`). The top bar's Vector button (`toggleVectorMode`) converts an empty
plain layer in place, or selects the vector layer right above, or adds "Vector N"; tapping it again
goes back to the layer it came from. Tools work on vector layers through seams in the existing
tools, never through other tool instances: the BrushTool `strokeHook` (`brush/StrokeHooks.kt`:
record the stroke as an object, replace it — the vector eraser — or refuse it), the Transform tool's
`ObjectLift` seam (lift objects instead of pixels), the selection funnel (`SelectionJobs.applyAsync
(toObjects = true)` for Lasso / Select shape), a first check in the bucket (`VectorFill.tap`) and a
pointer-down gate (`tools/LayerToolRules`: smudge, blur, clone, remove and frame divider need
pixels). `controller.vectors` (`VectorLayers`) is the service behind them: object edits
(`addObjects`, `appendData` after a live stroke, `update` with sync or async re-renders), hit tests,
the runtime object selection and edit sessions (`VectorEditSession`: the cache with a hole plus a
preview). Every vector edit is pixel tiles plus a `LayerDataAction` in one step, so undo and redo
never re-render. Each vector layer is saved in its own `vector_<id>_r<rev>.vec` (Deflate of JSON,
`VectorCodec`), written only when the layer changed.

## Adjustment layers & editable masks (`masks/`, v1.5)
`Layer.maskSpec` (`masks/MaskModel.kt`) is a parametric mask — linear, radial and brush components
combined by Add / Subtract / Intersect, invert, density — rendered into the existing `Layer.mask`
bitmap (`MaskSpecs.render`), so everything that reads masks keeps working and the spec stays
editable; a painted edit of the mask turns it into a plain mask (undoably). `Layer.adjustment`
(`AdjustmentSpec`: a filter id plus its values as JSON, `FilterValuesCodec`) makes an adjustment
layer: the compositor treats it as its own group (never a clipping base, never clipped) and
`engine/AdjustmentStage` maps the composite below it — read from the caller's `CompositeTarget` —
through the filter's `pixelMapper` (pointwise filters only; Tone first) and draws the result as the
layer's content (blend mode, opacity, mask). Every `drawDocument` caller passes the bitmap it draws
into (`CompositeTarget`: display tiles translate, flattened images identity, thumbnails scale).
Adjustment layers have no pixels: brush and eraser paint their mask, pixel tools are refused, and
merging one down applies its effect to the layer below. Specs are stored inside `project.json`
(pre-encoded strings, `MaskCodec` / `AdjustmentCodec`).

## SVG/PDF exchange (`exchange/`, v1.5)
Export (overflow menu: Export SVG… / Export PDF…) builds one `ExportScene` from the document and
writes it with pure-Kotlin writers (SVG, and a PDF writer with layers as optional content groups);
vector objects become paths, raster layers cropped images, text real text where it can. A Brushwork
payload embedded in both formats restores the layers exactly on re-import. Import (Import SVG or
PDF…, and "New from SVG or PDF" in the gallery, handed over through `PendingImports`) reads SVG with
an own XML tokenizer (no DTD) into editable vector layers, and PDF pages through `PdfRenderer` into
raster layers. `ui/exchange/ExchangeUi.kt` hosts the pickers, sheets and progress.

## Snapping (`snap/`)
One app-wide "Snap to objects" setting (`controller.snapping`, `SnapService`) for every tool. Targets
are the canvas edges and center, the selection, the content bounds of other visible layers, points
(other anchors of the path being edited, vertices of shape layers via `addLayerFeatures`), long
straight lines found in layer pixels (`LineDetector` / `LineScanner`: Table filter lines, frame
borders, box sides; works on transparent and opaque layers) and the grid. Bounds and lines are found
in the background and cached per layer content version (`LayerBoundsCache`). A tool takes one
`SnapSession` (`controller.newSnapSession()`): `begin()` per gesture, `snapPoint` / `snapMove` /
`snapValue` with what the finger alone gives, `draw()` for the magenta guides, `end()`. The
transform, shape, curve, polyline, lasso (polygon / curve), marquee, text, ruler and frame divider
tools use it; the math is `tools/transform/SnapGuides.kt`.

## Storage (`storage/`)
Each project is a folder in app-private storage: `project.json` (document + layer properties),
one compressed raw pixel file per layer/mask, and `thumb.png`. Saves are incremental (only layers
whose `contentVersion` changed). The editor autosaves every 45 s and whenever the app goes to the
background or the user returns to the gallery. v1.5 adds a `.vec` file per vector layer and the
mask spec / adjustment of each layer inside `project.json`; data that can't be read on open is
dropped for its layer only and listed in `Document.loadWarnings` (shown once by the editor).

## UI (`ui/`)
`MainActivity` → gallery or editor. `ui/common/Components.kt` holds shared controls (sheets,
sliders, numeric/length fields with units + automatic sliders / drag-to-scrub, nudge pad, chips,
swatches) — use them everywhere.

Editor layout (`ui/editor`): top bar (Vector first, then panels, and the overflow menu), tool
options strip (`ToolOptionsBar`, starting with a VECTOR chip in vector mode) with the X / Y strip
under it (`ui/tools/CoordinateStrip`, left out of the canvas fit inset so it never moves the
canvas), the Tools sheet (`ToolGrid` sections; Filters is a tile there), floating selection bar
(copy / cut / paste / deselect…) while a selection or clipboard exists — or the object bar while
vector objects are selected — the canvas, the brush size/opacity slider bar (values can be tapped and typed)
and the hotbar at the bottom. Panels (`BwSheet`) are half-height and translucent; inside the editor
they are drawn by a non-modal `SheetHost` (ui/common/SheetHost.kt): touching the canvas minimizes
the top panel to a pill and the touch reaches the canvas, so the view and objects stay editable.
The layers panel is a non-modal floating window in the bottom-right corner (a tap outside closes it).

Other packages: `fonts/` (imported fonts: zip/ttf/otf import, name-table parsing, favorites),
`inpaint/` (content-aware fill: multi-scale PatchMatch completion, used by the selection bar and
the Remove tool), `segmentation/` (smart select: ML Kit subject, Autoseg scene parser with
per-class probabilities, tiled + flipped passes, colour-guided refinement and band matting;
MagicTouch for the Object select tool).

Gestures (`CanvasView`): one finger → the current tool (via the controller); two fingers → first
offered to the tool (`Tool.onTwoFingerStart`, e.g. pinch-scaling a transformed image, text or
shape — only when one of the two fingers is on the object's box as drawn, `tools/PinchTargeting`),
otherwise pan/zoom/rotate the view; two-finger tap = undo (tools with steps take back one
step via `Tool.undoStep`), three-finger tap = redo; holding still with a color tool picks a color
(`EditorController.pointerLongPress` → temporary eyedropper with a preview square).
