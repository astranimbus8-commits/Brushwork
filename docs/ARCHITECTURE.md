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
  triggering one with `amendLastStep { }`. Undo and redo never fire edit events. The history is not
  trimmed while a scope runs (`UndoManager.holdTrim`), so grouping by a mark also works when the
  history is full. A live edit that records its step only when it ends (the Adjust sheet) registers
  a `DeferredStep`: it is flushed into its own step before any other push and before undo / redo.
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
Pointwise filters (v1.5) also expose `pixelMapper`, which `apply()` itself uses (contract-tested
per pixel; thread-safe, rows are mapped in parallel). A mapper keeps alpha, with one deliberate
exception: Gradation Map's semi-transparent stops lower it, exactly as its `apply()` does. Tone
(`adjust.tone`, first in Color Adjustment) works on luminance in linear light and keeps hue; its
panel shows the luminance histogram above the sliders and Exposure reads "+0.50 EV".

## Text layers (`tools/text/`)
A text layer is a normal raster layer whose `Layer.textData` holds the serialized text object
(`TextCodec`: content, style, box, vertical mode, position, path). The text tool re-opens it for
editing (tap its text, or "Edit text" in the layers window) and re-renders the same layer with
`EditorController.updateTextLayer` (one undo step). Any other pixel edit rasterizes the layer
(`textData` cleared, undoably). Text on a path (`TextPathSpec`, `TextOnPath`, `TextPathGeometry`)
lays one line of text along a line, circle, rectangle or cubic curve, either bending the glyph
outlines along the path or rotating each letter rigidly.

Horizontal text can wrap around a picture (v1.5, `TextItem.wrap` / `TextWrapSpec`). The picture
layer's outline (alpha × mask, traced by `WrapContourBuilder`, cached in `WrapContours`) is stored
in the text item in document px, so rendering, export and reload never read the picture, and the
wrapped layout depends only on the item. `WrapLayout` breaks the lines around it (StaticLayout's
rules otherwise). `TextWrapReflow` (`controller.textWrap`, an `EditListener`) re-traces the outline
after any committed edit of the picture and redraws the wrapped text layers — hidden ones included,
locked ones not — inside that edit's undo step (`amendLastStep`); undo and redo never re-flow.
Opening a wrapped text refreshes a stale outline. `TextExport` gives exporters the laid-out lines
(`lines`), the letters' outlines (`outlines`) and every painted part with its color
(`outlineParts`: box fill, border, outline stroke, letters).

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
goes back to the layer it came from, and tapped once more from there it returns to the vector layer
it left (before any of those rules: a new transparent canvas's empty Background is not turned into a
second vector layer). Tools work on vector layers through seams in the existing
tools, never through other tool instances: the BrushTool `strokeHook` (`brush/StrokeHooks.kt`:
record the stroke as an object, replace it — the vector eraser — or refuse it), the Transform tool's
`ObjectLift` seam (lift objects instead of pixels), the selection funnel (`SelectionJobs.applyAsync
(toObjects = true)` for Lasso / Select shape), a first check in the bucket (`VectorFill.tap`) and a
pointer-down gate (`tools/LayerToolRules`: smudge, blur, clone, remove and frame divider need
pixels). `controller.vectors` (`VectorLayers`) is the service behind them: object edits
(`addObjects`, `appendData` after a live stroke, `update` with sync or async re-renders), hit tests,
the runtime object selection and edit sessions (`VectorEditSession`: the cache with a hole plus a
preview). Every vector edit is pixel tiles plus a `LayerDataAction` in one step, so undo and redo
never re-render. `vector/render/VectorLayerRenderer` draws content: a `VStroke` is replayed by
`StrokeRaster` with the live stroke's sampler, dynamics, cost-limited spacing and final-length
tapers (a re-render equals the pixels the live stroke left), brush outlines of paths and shapes
follow the live path stroke (`brushStrokeSamples`), plain lines and fills are Skia paths (varying
widths through `VariableWidthOutline`). Skia's anti-aliasing of a path changes wherever a clip
cuts it, so paths and shapes are always rasterized per 256 px document grid tile
(`VectorLayerRenderer.TILE`) and re-renders cover whole tiles, and brush dabs are cut only at the
document's edges (`document` / `cut`), as the live stroke cuts them: the cache stays exactly a
fresh rendering of the data (and of the live strokes), whatever the dirty regions were. `VectorOps` holds the geometry: paint bounds, hit tests,
`touching` a selection, and exact transforms (a `VShape` stays a shape under similarities and
becomes a `VPath` otherwise). Each vector layer is saved in its own `vector_<id>_r<rev>.vec`
(Deflate of JSON, `VectorCodec`), written only when the layer changed.

**Service internals (`VectorLayers`).** Dirty regions are sets of whole 256 px tiles (`geom/TileSet`:
the tiles each changed object can paint, a stroke's tiles along its points; a z-order change
repaints only the objects that moved, `geom/ContentDiff`). `geom/ObjectIndex` is a 128 px grid of
paint bounds, cached per content instance and reusing the bounds of objects an edit shares; hit
tests, `touching`, renders, cost estimates and the object selection's id lookups go through it.
A re-render is estimated (`VectorLayerRenderer.estimateUnits` × the measured ns per unit): up to
25 ms it runs on the main thread, beyond that on one background worker (its own tip and render
caches) from the immutable content, in patches of at most 1024² px. The patch lands on a later
main-thread turn (never inside the `update` call, so an edit the caller computes next from the old
content is re-based onto it), with the data as one step, only if the layer is unchanged (content instance, content
version, bitmap); otherwise the edit is re-based onto the current content (`ContentDiff.merge3`)
and rendered again. While a render is pending the service registers a `DeferredStep`, so any
other edit, undo or redo first completes it (`flushPending`, also called by `CanvasOps`); edits
inside another step (`editScope` depth > 0) render synchronously. `update` reports through
`onDone` exactly once — applied, refused, re-run after a stale patch, or abandoned when the editor
closes (`dispose` also answers a preparing edit session with null, and refuses every later
request). `isRendering` (Compose state) is true while a render or an edit preparation runs; the
busy overlay "Rendering vectors…" shows at once for an estimate over 400 ms, else after 400 ms.
Under Robolectric the policy defaults to synchronous renders (tests opt into `Policy.ASYNC`).
An operation that changes or reads a layer BEFORE it pushes its own step must land that work
first, or the render is refused there (gone, hidden, locked) or read stale: layer operations
(`withToolPaused`: delete, duplicate, move, merge, flip, clear, fill...) and layer property changes
(`setLayerProps`: hide, lock, rename...) flush the `DeferredStep`s first, and canvas operations
(`CanvasOps.run` / `applyNow`) also run the Object bar actions waiting behind a render
(`settleVectorWork`) before they snapshot the document (`qa/VectorAsyncQaTest`).

**Pure moves (`VectorLayers.ShiftHint`).** A whole-pixel translation of objects that no other
object's paint bounds reach, with no paper grain (unless by multiples of 256 px) and with every
pixel that lands on the canvas coming from it, shifts the cache pixels (≈ 10 ms) instead of
re-rendering. The result is the old cache shifted exactly; it differs from a re-render only at
anti-aliased path edges, by up to a few tens of levels where the move makes a path cross a tile
edge of the 256 px grid (Skia anti-aliases a clipped path differently). `VectorEditSession.commit` detects such moves itself when every replacement is
`VectorOps.transformed(original, integer translation)`; the Transform lift passes the hint to
`update`. Transforms turn brush tips with the objects (`VectorOps.turnedBrush`: a rotated or
mirrored calligraphy stroke keeps its look; moves and scales keep the same preset instance); canvas
rotations and flips remap a layer's pixels only when all its brushes turn into themselves
(`LayerDataTransforms.turnsExactly`), else redraw it from the mapped objects. Under a homography
(Distort) a stroke's size and tip angle are taken at its bounds' centre. A remapped cache equals a
fresh rendering of the mapped objects only up to anti-aliasing: Skia's path rasterization is not
mirror symmetric, so the edge pixels of ellipses and curves can differ (an ellipse's edge pixel
can go from covered to empty; strokes of round tips stay within a few levels) until their tiles
are drawn again (`qa/VectorFuzzQaTest` checks those layers edge-tolerantly). Flip layer mirrors the
layer's bitmap and mask IN PLACE: undo steps that keep a layer's bitmap (canvas operations, merges)
must find it again with every later edit undone in it (`qa/VectorHistoryQaTest`).

**Edit sessions.** `beginEdit` renders the hole (the other objects in the edited ones' tiles) and
the floating bitmap (the edited objects), in the background when expensive (a newer request
answers an older one with null; a long preparation shows the busy overlay, whose Stop gives up).
Lifting every object copies the cache and renders, for the objects that reach past the canvas,
only the bands of the floating rect outside it, so their off-canvas parts show in the Transform
preview at the cost of those parts alone. A session's commit on
a large edit stays installed (hole + preview) until the background result lands.

**Selecting and lifting objects (`vector/select`, `vector/lift`, `ui/vector/VectorObjectBar`).**
Lasso and Select shape on a vector layer select the objects the area touches (New / Add /
Subtract / Intersect; no pixel selection, no undo step; large searches in the background).
Selected objects get dashed boxes and the Object bar (Delete, Duplicate, Forward, Backward, Front,
Back, Recolor, Transform, Deselect; each one step through `vectors.update`). The Transform tool's
`VectorLift.provider` lifts the object selection, else the objects the pixel selection touches,
else all objects; ✓ maps their geometry exactly (`LiftGeometry`: affine or Distort homography;
strokes scale by √|det|) and passes whole-pixel moves as a `ShiftHint`. Lifts and Object bar
actions asked for while a render is pending wait for it (`PendingRenders`). A PIXEL selection
(Magic wand, Object select, Select all) on a vector layer acts on objects everywhere it is used:
the selection bar's and the Selection sheet's Clear remove the objects it touches, their Fill
adds a filled even-odd `VPath` of its outline (`VectorLayerOps.clear` / `fill`, reached through
`clearLayer` / `fillLayer`), Duplicate copies the touched objects as a vector layer; nothing
there rasterizes the layer.

**Drawing on vector layers (`vector/draw`).** `VectorStrokeCapture` (the brush stroke hook) keeps
the live stroke's pixels (`keepLayerData`) and appends the `VStroke` as data in one step; the
selection doesn't clip vector strokes, and pixel-moving tips and alpha-locked layers are refused.
The vector eraser has three modes — Object, Partial (cuts strokes and open single-subpath paths at
the eraser) and To intersection (removes the touched piece between crossings with other objects'
centerlines) — with the geometry in pure Kotlin (`EraseMath`, `EraseTargets`, `EraseSession`), a
dimmed DST_OUT preview while the finger moves, one step "Erase", and a per-layer update queue
(`VectorDrawState`, which never holds its editor). The bucket (`VectorFill`) fills or recolors the
object tapped, or traces an enclosed area into an even-odd `VPath` placed under the line art. The
Shape tool makes `VShape` objects on vector layers and reopens one on a tap (✓ "Edit shape" in
place); a brush outline is previewed as the painting tool's live stroke, started unclipped by the
selection and alpha lock (`BrushStrokePreview.unclipped`), and kept as the object's exact replay
with the preview's seed.

**Curves on vector layers (`tools/vector/Curve*`).** On a vector layer ✓ of the Curve or Polyline
tool adds one `VPath` (anchors, handles kept on sharp anchors, per-point thickness 0–300 %, plain or
brush stroke with its preset and seed, fill); a live brush stroke keeps its pixels, started
unclipped. With no path pending, tapping a path's line (or inside a fill-only path) reopens it; ✓
is one step "Edit path", ✕ restores it exactly. Thickness blends with smoothstep along the arc
length (`CurveWidths`): plain lines are filled `VariableWidthOutline`s of `CurveWidths.line`, brushes
get `CurveWidths.atSamples` as pressure on a brush sized to the thickest sample — the renderer uses
the same functions, so a re-render equals the live stroke the tool kept.

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
(pre-encoded strings, `MaskCodec` / `AdjustmentCodec`). An effect is live only at settings where
its filter has a `pixelMapper` (`AdjustmentEffects.isLive`): Levels' "Auto levels" and Black &
White's smoothing / anti-aliasing look at the whole picture, so the Adjust sheet and "As
adjustment layer" refuse them with a message instead of drawing a silent pass-through. The layers
window inverts an editable mask through its spec (it stays editable) and offers no "Apply mask" on
adjustment layers (there are no pixels to apply it to).

- **Masks tool** (`tools/mask/MaskTool`): + Linear / + Radial / + Brush arm a creating gesture (the
  first one on a pixel layer adds "Tone 1" with the component as one step); handles and pins edit
  components, a handle drag previews at ½ resolution (`MaskPreview`) and records one data-only
  `MaskSpecAction` on release, which re-renders the mask on undo (it records mask tiles instead
  when the re-render is estimated over 150 ms or a painted mask is replaced). The Adjust sheet's
  changes are live and become one "Edit adjustment" step through a `DeferredStep`.
- **Brush components** use one canonical arithmetic (per-stroke float accumulation, rounded to a
  byte at the end of each stroke) in every path — region, full, the 1 B/px `MaskBrushCache` and the
  preview — so they give identical bytes.
- **NORMAL adjustments** over an opaque composite are one SRC_OVER pass, i.e.
  `lerp(below, F(below), mask·opacity)`; where the composite is not opaque two passes (DST_OUT by
  mask·opacity, then PLUS of the masked effect) keep its alpha, so an adjustment never makes
  transparent areas more opaque. A mapper that lowers alpha (Gradation Map's semi-transparent
  stops) draws its colors that much weaker in every mode: a transparent stop shows the image below.
  Effects are held to the document's color mode.
- **Matte**: `renderFlattened` / `renderThumbnail` with a background put it behind the composite
  as a matte when a live adjustment exists (otherwise the v1.4 path runs unchanged).
- **Visible first**: `DisplayTiles.update(visibleDoc)` renders the dirty tiles on screen; off-screen
  tiles stay dirty until they are scrolled into view.
- **`MaskCoverageHint`**: a render override drawing an adjustment layer's mask while it is edited
  tells the stage where that mask can be non-black (and, on an adjustment layer without a mask,
  supplies the mask being made), so the effect is only computed there.
- The stage keeps its per-call state in the compositor's own `AdjustmentScratch`: a nested
  compositor (the clone stamp's All-layers snapshot, filled while the canvas draws) has its own.
- Canvas operations (resize, canvas size / crop, rotate, flip) and Brushwork files placed on a
  canvas of another size map a mask spec with the layer and draw the mask again from it, so the
  mask stays exactly the spec's rendering.

## SVG/PDF exchange (`exchange/`, v1.5)
Export (overflow menu: Export SVG… / Export PDF…) builds one `ExportScene` from the document and
writes it with pure-Kotlin writers (SVG, and a PDF writer with layers as optional content groups);
vector objects become paths, raster layers cropped images, text real `<text>` in SVG where it can
(its box as shapes under it) and otherwise the outlines of every painted part in its own color (PDF
always, through `TextExport.outlineParts`), or its pixels when it holds color emoji (pictures in
their font, without outlines). A Brushwork
payload embedded in both formats restores the layers exactly on re-import. Import (Import SVG or
PDF…, and "New from SVG or PDF" in the gallery, handed over through `PendingImports`) reads SVG with
an own XML tokenizer (no DTD; UTF-16 and 8-bit files are transcoded to UTF-8 first by
`svg/XmlEncoding`) into editable vector layers, and PDF pages through `PdfRenderer` into
raster layers. An SVG imported into an open artwork is one undo step, after which Transform opens
with the imported objects lifted as objects (✓ keeps the layer a vector layer). A file that fits
the canvas (half a pixel of rounding allowed: sizes in mm / pt at the document DPI) keeps its
coordinates; a larger one is scaled to 90 % and centred (`VectorImport.placement`).
Every export (SVG / PDF through `ExportJob`, PNG / JPG and Share through `EditorActions`) first
lands, behind its busy overlay, the work still on its way — the Object bar actions waiting for a
render, the tool's pending work (a Transform's render then runs right there), a vector render in
flight (`settleVectorWork`, commit, `vectors.flushPending`) — so the file holds what is on the
canvas and the screen never freezes before the overlay shows (`qa/VectorExchangeQaTest`).
`ui/exchange/ExchangeUi.kt` hosts the pickers, sheets and progress; the export sheet's options are
`rememberSaveable` and each Save as… picker exports its own format, so an activity recreated
behind the system picker still writes what was chosen.

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
under it (`ui/tools/CoordinateStrip`: two 40 dp rows of ‹ value › and an absolute slider, folding
to one 28 dp line; left out of the canvas fit inset so it never moves the canvas; adapters in
`CoordinateSources` for Transform, Shape, Text, the Curve point and any `PositionedTool` — Masks,
Clone; a drag, arrow run or typed value is one edit from `beginPositionEdit` to `endPositionEdit`,
however long the finger rests on the way — the Curve and Shape tools hold their in-tool step open
in between, `beginNumericEdit` / `endNumericEdit`, as the point thickness sliders do), the Tools sheet (`ToolGrid` sections; Filters is a tile there), floating selection bar
(copy / cut / paste / deselect…) while a selection or clipboard exists — or the object bar while
vector objects are selected — the canvas, the brush size/opacity slider bar (values can be tapped and typed)
and the hotbar at the bottom. Panels (`BwSheet`) are half-height and translucent (the Tools sheet
up to 85 %, so the whole grid shows at once on a 392 x 873 dp phone); inside the editor
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
