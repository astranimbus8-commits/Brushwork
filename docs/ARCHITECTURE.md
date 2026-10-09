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
  controller extension functions (`.wt/_tools/ownership-check.ps1` checks a branch; v1.6:
  `.wt/_tools/ownership-check-v16.ps1 -Area A..G`).

## Invariants (v1.6)
- **I7 Previews are views.** Live adjustment proxy tiles, below-caches and frame-drag previews
  never reach a bitmap that is saved, exported, merged, thumbnailed or sampled (eyedropper, bucket,
  clone, content-aware fill). Once a live session ends and its refinement drains, every display
  tile equals a no-session `drawDocument` of that tile bit for bit.
- **I8 Defaults keep v1.5 behaviour and tests deterministic.** `IncrementSettings.enabled = false`:
  with increments off every gesture and control is bit-identical to v1.5. Under Robolectric
  (`"robolectric" == Build.FINGERPRINT`) `LiveAdjust.policy = EXACT`: no session starts and `touch`
  only invalidates; tests opt in with `LIVE` and drive frames through an injected clock and
  `refineStep()`.
- **I9 Data and derived data change together.** A `VPath` with `spline != null` has
  `subpaths == listOf(SplineBezier.toSubpath(spline))` within 0.01 px (the Path tool re-opens a
  spline only after checking it, `SplineBezier.matches`; an approximated spline — order 5–6 or
  unequal weights — that went through an affine transform may be cut into pieces differently, so
  it also passes within 0.5 px of a fresh conversion, else it is edited as a plain Bézier path); a threaded `TextItem` has `text == thread.story.substring(start,
  end)`; every frame of a story carries the same `story`, `storyId`, `rev` and `spec` (except the box
  width and height). Every edit keeps these or clears the extra data (`spline = null`, thread
  cleared) in the same step.
- **I10 Labels are an API.** The labels of design §3.7.11 stay on their controls and are unique
  among visible clickables; renaming one is a foundation change that lists the tests to update.
  Decisions made at integration: the vector strips' chips show "Settings" but are named "Curve
  settings" / "Polyline settings" / "Path settings" / "Shape settings" (the tool menu's cell is
  "Settings"); handle-side chips read "In and out" / "In" / "Out" in Curve, Path and Shape; the
  selection bar's delete item is "Clear"; layer-row badges name their row ("Layer N: text layer",
  "Layer N: text frame k of m", "Editable mask of layer N"); the tool menu's "px" badge is
  decoration and its pixel-only cells read "Pixel layers only"; More › "Import picture" is left
  out while the layer window (which has its own) is shown; the X / Y pill's "Increase X" /
  "Decrease X" actions replace v1.5's "X plus 1 pixel" arrows. `RepeatIconButton` (−/+ and ‹ ›
  arrows) has a click action: a screen reader's click is one step, a finger still repeats.

## Invariants (v1.7)
I1–I10 from `docs/ARCHITECTURE.md` stay as they are (I1 data and cache change together; I2 one action = one step; I3 main thread; I4 compatibility; I5 compositor goldens; I6 one owner per file; I7 previews are views; I8 defaults keep the previous behaviour; I9 spline subpaths equal `SplineBezier.toSubpath`; I10 labels are an API). v1.7 adds four. The designs numbered them differently; this is the mapping: risk's I15 (expressions) folds into I13, risk's I16 (layer-count budget) into I14, contracts' I14 (live modifiers) into I14.

- **I11 Layer tree.**
  - `doc.layers` stays the COMPLETE flat list in render order, bottom first. A folder is a `Layer` with `folder != null`; every layer's `parentId` is `Layer.ROOT_ID` (0) or the id of a folder.
  - Block rule: a folder at flat index `f` with `d` descendants owns exactly `[f − d, f − 1]`, directly below it. Depth ≤ `LayerTree.MAX_DEPTH` (8); folders ≤ `LayerTree.MAX_FOLDERS` (64).
  - Only `engine/LayerStructure.kt` (frozen) changes the structure of a document that has folders: every insert and remove, including those of `ImportLayers`, `SelectionEdits`, `VectorLayerOps` and Pathfinder, goes through `LayerStructure.insert`/`delete`/`move`. `structural {}` validates after every block (debug: throw; release: repair and log).
  - A child of a locked folder is locked, and a child of a hidden folder is hidden: every gate reads `doc.effectiveLocked(layer)` / `doc.effectiveVisible(layer)`, never `layer.locked` / `layer.visible` alone (sweep rule L, §4.4). Only tree walkers (compositor, export, live adjust) read a layer's own flags, because they apply the ancestors themselves.
  - A folder is never a pixel target: `checkUsable` and `startFilter` refuse it with "Choose a layer inside the folder to paint" before anything is recorded; `beginEdit`/`paintTarget` assert it.
  - **A document without folders is v1.6 bit for bit:** the same undo actions, compositor path, goldens, `project.json` bytes and `formatVersion`.
  - Creating a folder never changes the picture; moving layers into a pass-through folder at 100 % never changes it either (unless the move splits a clipping group).
  - Every folder's `bitmap` is `Layer.FOLDER_BITMAP`; nothing ever allocates pixels for a folder, and nothing ever recycles that bitmap (every recycle loop over layers uses `Layer.recycleBitmaps()`, which skips it; sweep rule B, §4.4).
- **I12 Points.**
  - Every point editor (Curve, Polyline, Path, Shape Points, the Free deform mesh) holds its selection as a `PointSelection`.
  - With exactly one point selected and "Select several" off, every gesture and control behaves as in v1.6; the existing tool tests stay green without edits beyond the item-19 test.
  - A gesture, typed value or slider drag on N points is ONE in-tool step. Differing values show "Mixed".
- **I13 Numbers and persisted defaults.**
  - Every typed number goes through `core/Expressions.kt` (via `Units.parse`, `NumberSliderMath.parseTyped` or `SliderMath.parseValue`; plain numbers take the v1.6 code through `Expressions.isPlainNumber`, everything else `Expressions.evaluate`, and text that is neither a plain number nor a valid expression falls back to the site's v1.6 code). A string keeps its v1.6 value unless it is a valid expression that is not a plain number: `parseTyped` filtered "100/2" to 1002 in v1.6 and now reads 50, while "1 000" and "12mm" still read 1000 and 12. A leading `+` or `-` is still a sign.
  - A LEADING relative operator (`*`, `/`, `×`, `÷`, `x`) applies to the value the field shows. The fields resolve it before their `parse` runs (`Expressions.resolveRelative`, §4.5), so the many `parse = { Units.parse(it) … }` lambdas of the tool options need no change.
  - Every new field in a persisted class (`project.json` DTOs, ShapeCodec, TextCodec, VectorCodec, Payload, `ExportScene` is not persisted) carries `@EncodeDefault(EncodeDefault.Mode.NEVER)` and a default that reproduces v1.6. Content that uses no v1.7 feature re-encodes byte for byte, except the codec `version` numbers inside `shapeData` / `textData`. Two goldens captured from `c72ea66` before F1 (`app/src/test/resources/v16/project-plain.json`, `project-adjust.json`) enforce it.
  - The one exception: a field of a class that exists only in format-3 content (`FolderSpec`) is encoded ALWAYS (`@EncodeDefault(EncodeDefault.Mode.ALWAYS)`). v1.6 never reads such a class, and an explicit value means a later change of its default can never silently change files already saved.
- **I14 Live arrays and one format predicate.**
  - A layer's array is DATA (`LayerData.array`); the layer's bitmap is its cache (I1). Every cache writer of a data layer draws through `ArrayDraw` (foundation), so the copies are never left out of a re-render. Copy 0 is the identity, so an array of count 1 changes nothing on screen.
  - Every cache writer also REDRAWS where the copies are: the area it clears is the caller's dirty rectangle united with `ArrayDraw.cacheBounds(before)` and `ArrayDraw.cacheBounds(after)`, both computed from the `LayerData` (never from the layer before `restoreData`). Vector layers diff and render their EXPANDED content (`ArrayDraw.effectiveVector`), and a live vector stroke appended to an arrayed layer re-renders the copies' rectangles in the same step (§4.5).
  - A raster pixel edit of an arrayed layer bakes it in the SAME step: `LayerData.rasterizedContent()` drops `array` with the other data, as it drops vector data today. The one exception is a raster array in "Edit source pixels" mode (`ArraySpec.editingSource`, §3.3): its cache is the source alone, pixel edits change the source, and "Finish source edit" takes the layer's pixels as the new source and re-renders the copies.
  - `ProjectFormat.writtenVersion(layers) = when { any folder -> 3; any adjustment layer -> 2; else -> 1 }`. Arrays, saved selections, symmetry, sharp points, per-point radii and kerning never raise it.
  - Budget: folders, arrays and saved selections never allocate a document-size ARGB bitmap except where §3 says so, and each such allocation is counted by `doc.effectiveLayerCount` against `maxLayers`.

## v1.6 foundation contracts (frozen APIs the areas build on)
- **Tools:** `ToolId.PATH` (a third `CurveKind` of `CurveTool`) and `ToolId.TEXT_FRAMES`
  (`tools/text/frames/TextFrameTool`), appended after `MASK`. `ui/editor/ToolMenu` lists the
  ibisPaint tool menu's cells.
- **Layer-list events:** `addLayer` / `addLayerWith`, `addLayerWithContent`, `duplicateLayer`,
  `deleteLayer` and merge down queue a `LayerListEvent` (ADDED, DUPLICATED, REMOVED, MERGED) inside
  the step's `editScope`, which sits INSIDE `withToolPaused` (the tool's pending work commits first,
  as its own step). They are delivered with the `EditEvent`s when the outermost scope ends, so a
  `LayerListListener` folds its edits into the step with `amendLastStep`; never for undo / redo.
- **Services on the controller:** `increments` (`snap/Increments`, Compose state persisted in
  `AppSettings.increments`; `ui/common/LocalIncrements` in the editor), `liveAdjust`
  (`engine/live/LiveAdjust`: `touch` / `end` / `drawFrame` / `wantsFrame`), `textThreads`
  (`tools/text/frames/TextThreads`, an edit and layer-list listener).
- **Compositor:** `MultiLayerRenderOverride` (one override drawing several layers),
  `CompositeTarget.directWrite`, `drawDocument(layerRange)` (ranges split only at adjustment
  layers), `DisplayTiles.updateBudgeted` (visible tiles nearest a centre first, within a time
  budget).
- **Text:** `TextSpec.letterScale`, `TextItem.thread` (`TextCodec.VERSION = 4`);
  `WrapLayout.layoutFrame` (the band loop with a height stop) and `TextRenderer.frameLayout` /
  `frameEnd`: a frame of a linked story is laid out from the story at its start in its fixed box,
  so rendering a frame and computing where the next one starts are one function. The text editor
  dialog edits a `TextEditorHost` (the Text tool, or a story).
- **Theme:** `ui/theme/IbisColors` and `IbisDims` (the ibisPaint look, measured); the layer
  window fills the size its host gives it.

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

## Layer tree and folders (v1.7: `model/LayerTree.kt`, `engine/LayerStructure.kt`, `engine/FolderComposite.kt`, `ui/layers/`)

**Model (I11, foundation F1/F2).**
- `doc.layers` stays ONE flat list, bottom first. A folder is a `Layer` with `folder: FolderSpec(passThrough)` and the shared 1 × 1 `Layer.FOLDER_BITMAP`; drawing into it throws.
- A child names its folder in `parentId` (`Layer.ROOT_ID` at the top level). A folder's block `[f - d, f]` is contiguous and ends with the folder: its children lie directly below it. `LayerTree.block / ancestors / depth / units / check` read the tree.
- `folderOpen` is saved but is never an undo step (`controller.setFolderOpen`).
- `engine/LayerStructure` (`controller.structure`) is the ONLY structure mutator: `insert`, `delete`, `move`, `above`, `insertionPoint`.
  - It records a `LayerTreeAction` only when the document has a folder. Without folders the v1.6 actions run, and the file saves as format 1, byte for byte v1.6 (I13).
- `insertionPoint()` is where a new layer lands: with an OPEN folder active, its top child; otherwise directly above the active row at its level.
  - `addFolder()` puts the folder directly above the active layer at its level.
  - Then `putInNewFolder`, `putIntoFolderAbove`, `takeOutOfFolder` (bottom child only), `ungroupFolder`, `deleteFolder`, `setFolderPassThrough`. Each is one undo step, and undo/redo restore the tree, properties, bitmaps (by identity) and picture exactly (`FolderUndoRedoRobolectricTest`).

**Consumer sweep (design §4.4).** Every main file that reads `doc.layers` or a layer's bitmap follows one rule, so a folder never reaches a pixel consumer:
- **P**: whole-document pixel loops skip folders (`doc.pixelLayers`, or `if (l.isFolder) continue`).
- **T**: structure is read through `LayerTree`.
- **R**: active-layer pixel readers are safe because `LayerToolRules` and `checkUsable` never give them a folder.
- **C**: the eyedropper, magic wand and object select read the composite when a folder is active.
- **L**: gates read `doc.effectiveLocked(layer)` / `doc.effectiveVisible(layer)`. Only tree walkers (the compositor's level walk, the rows, the property sheet, storage) read a layer's own flags.
- **B**: recycling uses `Layer.recycleBitmaps()` / `Bitmap.recycleUnlessShared()`, so `FOLDER_BITMAP` is never freed.
- **S**: inserts and removes go through `LayerStructure`. `ownership-check-v17.ps1` fails on `doc.layers.add`, `insertLayer(` or `RemoveLayerAction(` outside `engine/`.

**Folder refusals (F5).**
- `LayerToolRules.refusal` refuses on a folder the tools that write into the active layer, with "Choose a layer inside the folder to paint".
- `checkUsable` and `startFilter` refuse folders.
- A child of a locked or hidden folder is refused like a locked or hidden layer.

**Compositing (`FolderComposite`).**
- `Compositor.drawDocument` calls it only when the document has a folder. Otherwise the v1.6 loop runs unchanged (I5).
- The tree is drawn level by level. On each level the units (a layer, or a folder with its block) form the v1.6 groups:
  - an adjustment layer is its own group;
  - a base takes the clipping units directly above it on the SAME level, so a clipping layer at the bottom of a folder draws unclipped;
  - groups without a folder go through `Compositor.drawGroup` as before.
- **Pass-through at 100 %** (not a clip base, not clipped): the children draw straight onto the current canvas.
- **Isolated** (pass-through off, a clip base, or clipped):
  - the children composite into a pooled tile-sized scratch (at most 512² px, `FolderScratchPool`) with its OWN `CompositeTarget`;
  - the scratch then draws onto the parent with the folder's blend mode and opacity;
  - this is not a `saveLayer`, because adjustment layers read their backdrop through `CompositeTarget.bitmap`, which a `saveLayer` cannot expose (V8);
  - a folder clip base renders once per tile and is reused for every clipped unit's `DST_IN`.
- `drawnBlend(layer)`: a pass-through folder that is composited isolated (a clip base or clipped) draws **Normal**; its stored blend mode is used only once Pass through is off. The merge confirmation (`LayerOps.mergeChangesPicture` / `blendsWithBackdrop`) and export read the same rule.
- **Pass-through below 100 %** is exactly o·C + (1 − o)·B, in one pass with one rounding:
  - the backdrop is copied into a scratch and the children are drawn on it;
  - the scratch is drawn back with `SRC` through its `BitmapShader` under a constant A8 coverage bitmap of o;
  - the coverage is drawn at (0, 0) under `cv.translate(tile.left, tile.top)`, with the shader's local matrix at identity, so a partial display-tile redraw (`DisplayTiles`: `clipRect`, `CLEAR`, `drawDocument`) is bit-identical to the full one.
- `drawnAsIs(layers, i)`: every folder the layer is in draws its children straight. Live adjust and preview ranges may start or end only there. It is `LayerTree.showsAsIs`, one rule for the model and the compositor.
- `isClipped` / `isClipBase` (`LayerTree`) answer per level: the sibling unit above is the first layer above with the same parent (a sibling folder's top, not its bottom child); a clipping unit at the bottom of its level, or right above an adjustment layer, is not clipped. `EditorController.toggleClipping` reads the unit below a folder's block the same way.
- `effectStart(layers, i)`: the lowest flat index an adjustment's effect works on, i.e. the bottom of its nearest isolated folder, else 0.
- `renderBlock(doc, folder)` renders a folder's block isolated at document size. `renderBlockThumbnail` renders it through a scaled canvas (2× then filtered down), never into a document-sized bitmap.
- A render override whose layer is a folder (the Shape/vector "as a new layer" previews) draws at `insertionPoint`:
  - as an open folder's top child, with the folder's opacity, blend and isolation;
  - directly above a closed folder (after its clip group when it is a clip base: an approximation).

**Live adjust (`engine/live`).**
- A live session runs only while `FolderComposite.drawnAsIs` holds for the adjustment layer; otherwise the exact path draws.
- `BelowKey` also captures each layer's `parentId` and the folder flags (pass-through; opacity, blend and eye as for a layer). Moving a layer into or out of a folder therefore invalidates the below-cache.

**Export (`exchange/export`).**
- `ExportSceneBuilder.Planner` works per context: the top level and each folder that is isolated for export (pass-through off, opacity < 1, clipped or a clip base).
  - In a context, everything from its bottom up to the topmost shown adjustment becomes one picture.
  - Clip groups are formed per level, and their pictures carry the base's `drawnBlend`.
- A folder becomes a `SceneLayer` with `children`:
  - in SVG a `<g>`, with `isolation:isolate`, its blend and its opacity when isolated;
  - in PDF an optional-content group nested in `/Order`, and a transparency group (`/I true`) when isolated.
- **Pass-through below 100 %** is a NON-isolated Normal group at its opacity:
  - PDF writes a non-isolated transparency group (`/I false`), exactly the canvas's o·C + (1 − o)·B;
  - SVG group opacity always isolates, so SVG writes it isolated and the export summary lists `ExportSceneBuilder.PASS_THROUGH_SVG_NOTE` (`ExportOptionsSheet` shows it for SVG only);
  - its layers still form their own context, so an adjustment inside merges with the folder's layers only (an approximation).
- A hidden folder hides its children.
- Vector arrays export each copy's objects with the `ArrayLayout` matrix applied to the geometry (`SceneLayer` has no transform). Other arrays export the cache picture.
- `StrokeEnvelopeExport` writes one envelope per `VStroke.copies` entry.
- Payload v2 carries the tree (`parentId`, `folder`, `folderOpen`) and arrays. `PayloadImport` rebuilds the tree from the source ids, and replace mode matches non-folder layers only.

**Layer window (`ui/layers`).**
- `LayerTreeRows` lists the rows, skipping everything inside a closed or dragged folder.
- `LayerTreeRows.indent(depth, list)` (via `LayerWindowMetrics.indentAt`):
  - the step is min(12, (list − `DEEP_FOLDER_ROW` 172) / 4) dp per level, stopping at 4 levels;
  - that is 12 dp per level (48 dp max) in the 220 dp list of a 392 dp phone, and 4 dp per level in the 188 dp list of a 360 dp phone, so a depth-4 folder row keeps its values room.
- Rows show inherited eye and lock and per-level clip marks.
- `LayerWindowMetrics.thumbAt(depth, folder)`: a nested row takes its indent from the thumbnail, down to `DEEP_THUMB` 32 dp. A folder row stops at `FOLDER_THUMB_MIN` 40 dp, its open/close target.
- `LayerRow.RowValues`: a folder row shows "Pass through" on one line or, when one line at `LayerRowTextMin` 12 sp would ellipsize, "Pass" over "through" at 11 sp (`rememberBlendSplit`). The opacity line is fitted into what remains, and the row stays 80 dp. At 392 dp every folder row splits.
- Tapping a folder's thumbnail opens or closes it ("Open Folder 1" / "Close Folder 1"): saved, no undo step.
- The reorder handle:
  - a drag moves a whole block (`DraggedUnit`, `movedBlock`);
  - the dragged folder's hidden rows leave a blank `DRAG_GAP_KEY` item of their height at the list's end, so the rows above stay under the finger. Headers and the gap are not reorderable;
  - `HandleGesture` swipe right moves the layer into the folder directly above;
  - swipe left takes a folder's BOTTOM child out (other children refuse with no step);
  - `Smoke.pump` placement animations must finish before a test touches a row, because `ReorderState.itemAt` reads layoutInfo offsets.
- `LayerControls` / `LayerOps` hold the folder menu items:
  - "New folder", "Put in new folder", "Move into folder above", "Rename folder", "Duplicate folder", "Layer from folder", "Ungroup folder";
  - "Pass through" first in a folder's blend list (a mode picked turns it off in the same step);
  - alpha lock and mask disabled, their captions shown on tap (the disabled-tap detector sits inside the toggleable);
  - "Delete folder and its N layers?" (folder only, or everything);
  - "Merge folder" on the strip, refused on a locked or hidden folder, or one with a locked layer inside (`LayerOps.canMergeFolder`; as for merge down, the controller merges without checks).
- `FolderThumbnails` draws the composite in each folder row's glyph.
  - Out-of-date pictures render 300 ms after the last change, one per frame, on the main thread (I3: rendering reads layer bitmaps).
  - The budget is 30 ms each on the T606.

**Mask scopes (`masks`).** `AdjustmentHistogram` and the filter-through-mask range start at `FolderComposite.effectStart`:
- inside an isolated folder they measure the folder's own layers below the adjustment;
- a pass-through folder the adjustment is in is flattened to the top level of the histogram's view document.

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

**Letter scaling (v1.6, `TextSpec.letterScale`).** `LetterRamp` gives each grapheme cluster a
factor from 100 % down to "Smallest letter" (or up, End → start), in Even steps or by the Same
ratio, over the whole text or each paragraph; whitespace takes no step. `ScaledLetters` draws one
letter per call, measured and drawn with ligatures off; Align shifts the baseline (Baseline: none,
Center: capH·(1−f)/2, Top: capH·(1−f)). Scaled text always goes through `WrapLayout` with the
full-size line pitch, and a display tile draws only the lines near it; unscaled text keeps the
v1.5 `StaticLayout` path bit for bit. Vertical text keeps letters centred on the column; text on a
path scales along its one line; right-to-left and complex scripts are drawn unscaled with a note.
`TextExport.lines` returns null first when scaling is on, so exporters fall back to outlines.

**Text frames (v1.6, `tools/text/frames`, `ToolId.TEXT_FRAMES`).** Linked frames are ordinary
text layers whose `TextItem.thread` (`TextThreadSpec`: story, storyId, rev, start, end) names
their slice of one story (I9). `TextRenderer.frameLayout` lays a frame out from the story at its
start in its fixed box (one letter-scaling ramp over the whole story), and `frameEnd` gives where
the next frame starts, so rendering and flow are one function. `StoryWriter` writes a story edit
across the chain as one step; `TextThreads` (an edit and layer-list listener) heals the chain
inside the triggering step (delete, merge down, painting, Transform, a v1.5 edit, wrap-source
changes) with `amendLastStep`, and splits frames that repeat a story id (a Brushwork SVG/PDF
imported back into its own artwork) into separate stories; undo and redo never re-flow.
`StoryMeasureCache` reuses a measured tail only when its text is equal and, for scaled letters, its
slice of the ramp's factors too (capped at 600k characters). `ThreadPreview` previews moves and resizes of several frames through one
`MultiLayerRenderOverride`. The tool draws frames (the story editor opens through
`StoryEditorHost`), shows overflow as a red + on the out-port with "+ N characters", links by
out-port or "Link…", and has Unlink here, Delete frame (the story re-flows in the same step),
Edit story and per-frame wrap around a picture. A duplicated frame becomes an unlinked text;
locked frames keep their slice.

## Kerning (v1.7, item 17; `tools/text/`)

**Model.** `TextKern(index, value)` is extra space AFTER UTF-16 index `index`, in 1/1000 em (−1000..1000). `TextItem.sanitized` keeps the list:
- sorted and unique;
- with no zeros;
- with at most 10 000 entries.

`TextSpec.fontKerning` defaults to on. Both are NEVER-encoded: unkerned text with font kerning on encodes as v1.6, except `TextCodec.VERSION = 5` (I13).

For a frame of a linked story, the indices are STORY indices, and every frame stores the whole story's list. `TextThreads.writeStory` and `StoryWriter` write that list with the slices. `TextThreads.isWhole` treats a frame holding other kerns (one written by v1.6) as broken and heals it in the triggering step.

**Where a kern applies.** One rule (`TextKerns.applies`, shared by rendering and the UI) decides whether a kern after index i applies. It does when:
- i and i+1 are in the text;
- neither side is a line break;
- the paragraph is one `LetterRamp.supports` accepts (not RTL, not a shaping script);
- i+1 is a grapheme-cluster boundary (`LetterRamp.clusterBounds`, per paragraph).

Kerns that do not apply are kept but ignored.

**Edits.** `TextKerns` holds all kern arithmetic:
- `edited(old, oldText, newText, cursor)`: diffs by common prefix and suffix, with the cursor settling ambiguous runs. Kerns in deleted gaps drop; later ones shift.
- `remap`, plus `slice`/`shifted`: a duplicated frame keeps its slice's kerns, re-indexed; joining two stories shifts the second story's kerns.
- `gaps(selStart, selEnd, text)`: the gaps between grapheme clusters that the cursor or selection names, as a list.
  - A cursor inside a cluster names none.
  - A selection end inside a cluster snaps outward to the whole cluster.
- `applying(text, gaps)` and `refusal(text, gaps)` (`LINE_BREAK`, `SCRIPT`): which of those gaps can take a kern, and why none can.
- `commonValue`/`withValue`/`nudged` over any `Iterable<Int>` of gaps: back the row's single value, "Mixed", and −/+.

Every host edit path goes through `edited`: `setText(text, cursor)`, placeholders, the story editor. `KerningEditor` (`setKerns(gaps, value)`, `nudgeKerns(gaps, delta)`) is the host interface the dialog calls; `TextTool` and `StoryEditorHost` implement it.

**Rendering.** `TextKerns.advancesPx(source, kerns, from, sizePx, factors)` gives the extra advance after each character, or null when no kern applies. Text with an applying kern takes the per-cluster route: `WrapLayout.measure` with `kernPx`, drawn by `kernedBlock` as `drawTextRun` pieces split at kerned gaps. Text without one keeps the `StaticLayout` route bit for bit (I5, I8).

Letter scaling scales a kern with the letter before its gap. On a path, kerns move the letters along it. "Font kerning" off sets `'kern' 0` on every paint of the item, on every route: `TextRenderer.NO_FONT_KERNING`, combined with the scaled-letter features by `scaledFeatures(spec)`.

**UI.** `ui/placement/TextKerningSection` sits under "Letter spacing" in `TextEditorDialog` (tag `textKerningRow`). `kerningRow(text, kerns, selStart, selEnd, vertical, onPath)` gives the row's state: the gaps it edits, their common value, and its caption.

- "Kerning": −/+ in steps of 10 and a field in 1/1000 em ("Mixed" when the gaps differ).
  - It edits only the named gaps a kern applies to, so no dead kern is stored.
  - The caption comes from `gapCaption`: "Between “A” and “V”". It names whole clusters (an emoji with its skin tone, a letter with its accent) and shows ↵ for a line break.
  - Disabled, with a caption saying why:
    - "Kerning works on horizontal text" on vertical text (not on a path);
    - "Kerning works between two letters of a line" when every named gap is beside a line break;
    - "Kerning works on left-to-right text with separate letters" when one is in an RTL or shaping-script paragraph.
  - With no gap named, it shows the hint "Put the cursor between two letters, or select letters".
- "Font kerning" switch.

**Export.** `TextExport.lines` returns null when a kern applies to the item's own characters (`TextExport.kerned`) or Font kerning is off. SVG and PDF then draw the text as outlines; A's `ExportSceneBuilder` is unchanged. Unkerned text stays live `<text>`.

## Text: transforms, pill, history taps (v1.7, items 9, 10, 11, 13; area D)

**`TextTransforms` (item 11)** is a pure map of one text layer's data by a similarity: a row-major 3×3 matrix. Flips, skews and perspective give `canMap` false.
- The centre is mapped and `rotationDeg` turns by the map's angle. A scale within 1e-6 of 1 is exactly 1, so a move keeps every size.
- Every length is multiplied by s:
  - size and outline width;
  - box size, padding, border width and minimum sizes;
  - the path's points and sizes;
  - the wrap gap (capped at `TextWrapSpec.MAX_GAP_PX`).
- Quantities in em or percent are unchanged.
- The result is null when a size would leave its range, or when a frame would be turned.

It writes nothing: F's `DataLift` renders the mapped data and commits it through `updateTextLayer`/`updateLayerData` as one step. A wrapped text lays out around its stored outline at its new place.

**A scaled frame of a linked story** gives its look to the whole story:
- `mapped` bumps the frame's `thread.rev` when the map changes `FrameGeometry.storyLook` (any scale), so the frame holds the story's newest copy.
- `TextThreads.isWhole` compares every unlocked frame's look with that copy's.
- On the commit's `EditEvent`, `TextThreads` re-flows the story in the scaled look into every frame. Each frame keeps its own box (`withFrameBox`), and locked frames are pinned. The re-flow is folded into the "Transform" step (`amendLastStep`).
- One undo restores every frame's data and pixels; redo gives the same pixels.
- A move changes neither look nor `rev`, so nothing re-flows.
- Unlinked text is untouched by any of this.
- A transform that maps several frames of one story must commit them in one `editScope`, so the story heals once.

**Pill sources (items 9, 13).**
- `TextTool` is a `PillPositionTool` ("Center" of the open text).
- It is a `ScaledTool`, uniform only: 100 % is the text as opened, scaled about its centre, with size, box, padding and border together.
- It is a `DeletingTool` (trash cell "Delete text"):
  - a new text is cleared with no step;
  - an opened text layer goes in ONE step, "Delete text", through `controller.structure.delete` (rule S), and undo puts it back in its folder at its place;
  - the last pixel layer refuses with `LayerStructure.LAST_LAYER`.
- `TextFrameTool` is a `PillPositionTool` for the selected frame. The move is pending until `endPositionEdit`, then one "Move frame" step.

**History taps (item 10).**
- `TextTool.historyMark()` is `TextMark(open item, layer)`. `rollbackHistory` puts the open text back while the same text is open.
- `TextFrameTool.historyMark()` is `FrameMark(story target, open story, pill-move layer, pending move)`. `rollbackHistory` does three things:
  - puts the story back through `StoryEditorHost.restore`, in the same frame's session only;
  - puts a pill move that was under way at the mark back to its position;
  - drops a pill move begun after the mark.
- Afterwards an untouched editor or text reports `hasUserChanges` false, so the tap's one undo lets it go and undoes exactly one document step.

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

**Path tool (v1.6, `CurveKind.PATH`, `tools/vector/spline`).** A Blender-like NURBS path: the
pending `VSpline` (control points with weight 0.1–10 and thickness, order 2–6, Endpoint, Cyclic)
is the tool's state, and its anchors are always derived through `SplineBezier.toSubpath`, so
preview, brush, fill and both commits reuse the Curve pipeline. `NurbsGeometry` has the knots and
rational de Boor evaluation; `SplineBezier` converts order ≤ 4 with equal weights exactly and
approximates the rest with cubics within 0.05 px (Hermite plus least squares, ≤ 16 pieces per
span). While a point is dragged, `SplineBezier.Incremental` re-converts only the spans whose control
points changed, bit-identical to a fresh conversion (✓ and `matches` convert fresh).
`SplineEditing` inserts / deletes / moves points; `SplinePresets` has the Circle and Capsule
quick starts (fitted to the canvas area that shows, `CanvasView.freeArea`); `PathOverlay` draws the dashed control polygon. ✓ on a vector layer stores one
`VPath` with `spline` and exactly its Bézier form (I9); a tap on a spline path reopens it in Path
(after `SplineBezier.matches`), a tap on a plain path from Path switches to Curve. "To Bézier"
hands the path to Curve as one in-tool step with smooth, grabbable anchors. Path keeps its own
preferences under "vec.path".

**Bézier handle scaling (v1.6).** `CurveGeometry.scaledHandles` scales a point's handles (both,
in or out; automatic tangents are made explicit first; factor held to 0.01–100). The Curve
strip's Handles group (‹ › arrows, value, 10–400 % log slider, side chips, "All points") and a
pinch that starts within 40 dp of the point or its handle ends are each one in-tool step; the value
is relative and rests at 100 %. The Shape tool's Points mode has the same group. "Handle size"
(`AppSettings.curveHandleScale`, 75–200 %) scales the drawn handles and their grab radii for Curve,
Polyline and Path.

## Pathfinder (v1.7 item 20, area G)

- **Geometry: `vector/pathfinder/`**, pure and thread-safe.
  - **`PathConvert`** builds operand regions and reads paths back:
    - a `VPath` via `VectorOps.toVectorPath` with its fill rule;
    - a `VShape` or a shape layer's `ShapeObject` via `ShapeOutlines.outline`, WINDING;
    - a `VStroke` is never an operand.
    - Read-back uses the platform `PathIterator` on API 34+ (`PlatformReader`) and androidx `PathIterator` below (`AndroidxReader`, which a JVM test cannot load). The platform reader calls `next(points, 0)` only, the conic weight in `points[6]`: after `hasNext()` (which reads one segment ahead) `peek()` answers the segment after the next one, so never use it. Conics become quads (0.25 px), quads become cubics exactly.
    - The read-back yields explicit-segment `Contour`s, then `VSubpath`s with sharp anchors; the fill rule comes from the result.
    - Area and connected pieces come from nesting parity.
  - **`PathfinderOps.run(op, operands)`**: the ten `PathfinderOp`s.
    - The shape modes use Skia `Path.op` folds.
    - Divide is built operand by operand. Trim, Merge and Crop are per-operand unions and differences. Outline works on Divide's pieces.
    - Caps: 12 operands, 256 pieces; pieces under 0.5 px² are dropped as slivers.
    - Result: `Done(objects)`, `Empty`, `TooMany` or `Failed`.
  - **`PathfinderStyles`**: `PathfinderStyle(opacity, fill, stroke)`.
    - Unite, Intersect and Exclude keep the top operand's style. Minus front keeps the back operand's, Minus back the front's.
    - Divide, Trim, Merge and Crop keep each piece's own style; Merge then unites touching pieces of the same fill.
    - Outline edges get a PLAIN 1 px stroke in the piece's fill colour.
    - Results never keep a spline.
  - **`PathfinderOutline`**: splits each piece's contour at junctions (vertices snapped at 0.02 px, straight T-junctions split, degree ≥ 3). Each stretch becomes an open unfilled path; a shared stretch is kept once, from the upper piece. On curves each edge is drawn once (Outline = (Unite + Divide) / 2).
- **Tool: `tools/pathfinder/PathfinderTool`.**
  - Picks are `Pick(layer, objectId?)` in Compose state (objectId null for a shape layer).
  - `operands` re-checks every pick on each use: still in the document, effectively visible and unlocked (rule L), not arrayed, the object still a `VPath` or `VShape`. They come in picture order: layer index, then object index.
  - A tap (16 dp slop, 10 dp tolerance) toggles the topmost eligible object top-down across all layers.
    - Strokes and arrayed layers are skipped; their message shows only when nothing eligible is under the finger.
    - A miss sets `missed` (the hint) without a toast.
  - `selectAll()` takes up to 12 eligible objects, else none and "Select up to 12 objects".
  - Picks are not edits: `hasPendingWork` stays false. Picks are cleared on `onSelected` and after an operation.
  - **`apply(op)`**:
    1. `vectors.flushPending()`: an edit still rendering lands first.
    2. Snapshot every operand on the main thread: its region, its style, and its layer's content instance, shape data and opacity.
    3. Compute on `computeDispatcher` (`Dispatchers.Default`).
    4. If it is not done within 300 ms (`withTimeoutOrNull`), hand over to `runBusy("Working…")`, whose Stop cancels the computation.
    5. On landing: drop the result if another tool is current; flush pending edits again; refuse it ("The objects changed: try again") if any snapshot differs.
  - **Landing, always ONE `groupUndo(op.historyLabel)`** with the same label for every inner call:
    - **All operands are objects of one vector layer:** one `vectors.update` with `content.replaced(anchor → results).without(others)`, the content read inside the step. The anchor is the top operand (the back one for Minus front); the first result keeps its id.
    - **Otherwise:**
      1. Check the limit with `roomForResult(effectiveLayerCount, removedShapeLayers, maxLayers)` (the operand shape layers that go are counted out).
      2. Create "Pathfinder N" (N = 1 + the highest existing), a new vector layer.
      3. `structure.insert(layer, structure.above(topOperandLayer))`, so it lands inside the top operand's folder (its bitmap is recycled if refused).
      4. `vectors.addObjects` with the results; layer opacity is folded into object opacity.
      5. `vectors.update(without(ids))` for each operand vector layer (an emptied layer is kept).
      6. `structure.delete` for each operand shape layer.
      7. Make the new layer active.
      - A refused part (out of memory) takes back the parts already done and records nothing.
- **Options: `ui/pathfinder/PathfinderOptions`** (one Row; the options bar scrolls it).
  - First the hint, the "N objects" count, or a spinner with "Working…".
  - Then the "Select all objects" `ActionChip`.
  - Then ten icon-and-label buttons, as tall as the 44 dp options strip and at least 56 dp wide: shape modes, a divider, pathfinders. Each is `clickable` plus `clearAndSetSemantics { contentDescription = op.description }` (I10), enabled with ≥ 2 operands and not busy.
- **Labels.**
  - `PathfinderLabels`: SELECT_ALL, HINT, OUTLINE, STROKES_SKIPPED, ARRAY_SKIPPED, TOO_MANY, TOO_MANY_OPERANDS, resultLayer(n), the busy and outcome messages (WORKING, NOTHING_LEFT, FAILED, CHANGED, NO_MEMORY, picked(n)) and the nine other content descriptions (UNITE … CROP), read by `PathfinderOp.description`.
  - `HistoryLabels.PATHFINDER_OPS` and `pathfinder(op)`.
- **Tests.** `PathfinderOpsRobolectricTest` (the design's squares, budgets), `PathfinderCurvesRobolectricTest` (conic read-back, circles, Outline once per edge), `PathfinderToolRobolectricTest` (every §3.20 tool case, folders, busy handoff, changed operand, a pending edit landing first, the layer limit), `PathfinderRoundTripRobolectricTest` (ellipse layers, save and reopen, undo), `PathfinderOptionsUiRobolectricTest` (in the real options strip at 392 dp). The lead's `StubToolsRobolectricTest` still runs the real tool on every layer kind.

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

**Faster adjustments (v1.6).** Two layers of speed-up, neither of which changes saved pixels:
- **Fused NORMAL path** (`AdjustmentStage`, used when `CompositeTarget.directWrite`): the stage
  reads the composite below with `getPixels`, takes the mask factor from the unchanged DST_IN
  luminance paint (or straight from the mask bitmap for whole-pixel translations, or from
  `MaskFactorCache` during a live session), maps rows in parallel, lerps by mask·opacity with
  the below alpha kept, and `setPixels` straight into the target. Other blend modes and
  `directWrite = false` keep the v1.5 Skia path byte for byte. It stays within 1 level of a float
  reference (2 where v1.5 itself was 2 off).
- **Live sessions** (`engine/live/LiveAdjust`, `ProxyTiles`): while a slider, a mask handle or a
  layer-window opacity drag changes an adjustment layer, `touch(layer, region)` starts a session
  that draws the visible part of the region from 512 px proxy tiles at a power-of-two scale (1/8–1,
  halved after a frame over 33 ms). The composite below the layer is cached per frame under a
  `BelowKey` (every layer below, its data and props, colour mode, safe compositing, overrides);
  layers above are drawn exactly every frame. After `end()` (or 150 ms idle) refinement redraws
  the region exactly, centre-out, 8 ms per frame (`DisplayTiles.updateBudgeted`), so the screen
  converges bit for bit to a no-session render (I7). Memory is capped at 32 MB (the scale halves
  first; an OOM ends the session with one toast; buffers are freed 2 s later or on
  `onTrimMemory`). `changed(layer, region)` is the one-shot form for discrete edits (undo, a typed
  value). Under Robolectric the policy is EXACT (I8); tests opt in with LIVE and an injected clock.
- `AdjustmentEdit.preview` no longer bumps `layersVersion` on every move (the sheet follows its own
  `version` state). "Fast adjustment preview" (`AppSettings.fastAdjustPreview`, in the Masks
  Components sheet and in Settings › Display; both switches follow the saved value) turns
  sessions off; it is read at each session start.

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

## Saved selections (v1.7 item 14, area G)

- **Model (F1).** `Document.savedSelections: List<SavedSelection>` holds immutable entries `(id, name, bounds, packed, revision)`.
  - `packed` is the selection's ALPHA_8 rows cropped to `bounds`, zlib-deflated.
  - Files are `sel_<id>_r<rev>.bin`.
  - Limits: `SavedSelection.MAX = 32` entries and `MAX_TOTAL_BYTES = 32 MB` packed.
- **Controller (F1).**
  - `saveSelection`, `updateSavedSelection`, `renameSavedSelection` and `deleteSavedSelection` record a `SavedSelectionsAction`.
  - `loadSavedSelection(id, mode)` inflates on a worker and lands through `Selection.combine` as the existing `SelectionAction` (Load, Add to, Subtract from, Intersect with).
- **`tools/select/SavedSelectionOps` (G).** Pure Kotlin on the inflated crop bytes, so results are identical on every device.
  - `mappedForCanvas(list, result, oldWidth, oldHeight)` is called by `CanvasOps` on its background thread after the layers are mapped. It maps one entry at a time: inflate the crop, map it row by row through the operation's `CanvasGeometry` into the result's Deflate stream, so at most one entry's crop is alive.
    - Flips, quarter turns and whole-pixel moves are exact; a resize samples bilinearly, with several samples per pixel when it shrinks. Samples in the old document's outer half pixel read its edge pixel, so a selection touching the border still covers the new border.
    - Bounds are re-tightened, and an entry cropped to nothing is dropped.
    - An unchanged entry is returned as the very instance (the list itself when all are kept). A changed one keeps its id and name, and the controller gives it the next revision.
  - `rows(e)` inflates an entry's whole crop (used by the mapping). `thumbnail(e, docW, docH, tw, th)` streams the crop one row at a time into a single row buffer (never the whole crop: the rows' thumbnails are computed in parallel).
  - The ACTIVE selection is still dropped by a geometry change exactly as in v1.6. Saved selections are kept and mapped, and one undo restores both.
- **`ui/layers/SavedSelectionRows` (G).**
  - Placement: ONE `LazyColumn` item after the Selection Layer row (`HEADER_ITEMS = 2`). It emits a `Column` of 48 dp rows, newest first, each in `key(id)` and tagged `V17Tags.savedSelectionRow(id)`: a background thumbnail (keyed by the packed bytes, bounds and document size) plus the name.
  - A tap opens the row's menu (`SavedSelectionLabels`): Load, Add to, Subtract from, Intersect with, Update from, Rename (dialog), Delete. Subtract, Intersect and Update need an active selection.
  - Actions run through `fromPanel`. The rows are never drag sources or drop targets.
  - The rows read `layersVersion`, so undo, redo and canvas operations refresh them.

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

**Increments (v1.6, `controller.increments`, `snap/Increments`).** One switch ("Use increments",
off by default: then every gesture and control is exactly v1.5, I8) and five steps by
`IncrementKind`: LENGTH 10 px, SIZE 1 px, SCALE 10 %, ANGLE 15°, PERCENT 5 %, plus custom steps per
control key. The shared number controls infer a kind from the unit (% → PERCENT, ° → ANGLE, px →
SIZE — for sliders too, including the filter and adjustment sliders — `LengthField` → LENGTH) or
take an explicit `incrementKind` / `incrementKey` (`IncrementStepping.resolve`; a key alone makes a
custom step, e.g. "mask.feather"). Sliders land on multiples with the range ends reachable, −/+ and
scrubs go to the next multiple, and typed values are never rounded. Long-pressing any value opens
the Step popup (`Modifier.stepOnLongPress`, at 85 % of the long-press time; a held `NumberField`
refuses focus until the finger lifts). Gestures step too — Transform (moves, scales of the
original with mirrored axes keeping their sign, rotation, pinch, distort), Shape (moves, sizes,
lines, angles, points, handles), Curve / Path points and handles, Masks handles, Text and frame
moves — in the order object guide, then grid, then increment, per axis (the snap session leaves
the grid out; callers apply `snapping.gridPoint` themselves, the Text tool only while increments are
on, to keep I8), with the step shown in the
top info chip (`increments.readout`) while a gesture is stepped. The steps live in the Increments
sheet ("Increment steps", More › Increments…), in Settings, and behind the X / Y pill's "#" cell.

## Symmetry (`assist/Symmetry*`, `brush/DabMapping`, `tools/symmetry/`, `ui/symmetry/`, v1.7)

**Settings.** `Document.symmetry: SymmetrySettings` (model, F1) changes only through `controller.updateSymmetry(s)`. Like the ruler, changes are not undoable and are saved in `project.json`.
- `type`: OFF, MIRROR, KALEIDOSCOPE, ROTATION, ARRAY or PERSPECTIVE_ARRAY.
- `centerX`/`centerY`: −1 means the canvas centre.
- `angleDeg`: 90 is a vertical mirror axis. The UI shows `angleDeg − 90`.
- `divisions` 2..32, `spacingX`/`spacingY`, and `quad` (TL TR BR BL; it must be convex, otherwise the default trapezoid is used).

**Placement.** `SymmetryMaps.transforms(s, w, h, startX, startY)` returns row-major 3×3 maps, identity first. It returns an empty list when symmetry is off or there are fewer than 2 usable maps, so a stroke whose copies are all culled is a v1.6 stroke and writes no `copies` (I13).
- Mirror: 2 maps.
- Kaleidoscope n: 2n maps.
- Rotation n: n maps.
- Array: lattice translations kept within the canvas size, nearest to the stroke start first.
- Perspective: H·T(i, j)·H⁻¹ for cells in front of the horizon.
- The total is capped at `StrokeCopies.MAX` = 256 maps.

**Dabs.** `DabMapping` places copy k of a resolved dab. Use one instance per stroke or replay; it is not thread-safe.
- The centre goes through the map.
- The diameter is multiplied by √|det J| (exactly 1 for rigid maps). Below 1 px the copy is drawn at 1 px with less alpha.
- The rotation is turned by J. Radial tips keep it.
- Textured AA tips are drawn flipped when det < 0. Pixel tips get a brush whose baked angle is turned.
- The copy takes no values from the stroke's random sequence.
- **Many copies** (≥ `PHASE_MIN_MAPS` = 16 maps): an AA copy under an affine map, unturned or with a radial tip, not mirrored-textured, and at most 256 px wide, is drawn from `DabMapping.PhaseTips`. That is its tip pre-drawn at 4×4 quarter-pixel shifts with DabStamper's filtered paint, then copied at a whole-pixel position with the dab's alpha, within 1/8 px of its exact place. Every other copy, the original dab, and strokes with fewer maps use the exact `DabStamper.stamp`.
- A `PhaseTips` is keyed by tip identity and scale. `BrushTool` keeps one for its strokes and `StrokeRaster` one for its replays (at most 16 ALPHA_8 bitmaps, about 1 MB).

**Live strokes** (`BrushTool`, the V23 stamp sites).
- `symmetryMaps` is taken at stroke start, after the ruler has constrained the input. There are none for path strokes, clone, or tools other than BRUSH, ERASER, SMUDGE and BLUR.
- **Buffer strokes** stamp each dab, then each copy that lands on the document, into the ONE coverage buffer. Stroke opacity is applied at the composite, so overlapping copies never darken twice.
  - `copyTiles` records the commit tiles the copies reached.
  - `ownReach`/`copyReach[k]` record where the dab and each copy drew.
  - **End taper** (`retaperCopies`): each copy's changed end is its own region, and overlapping regions are merged (`disjoint`). Each region is cleared, then every contributor that reached it is re-stamped in canonical order (dab, then copies k). The result equals the vector replay within ±1.
- **Direct strokes** (smudge, blur, watercolor) run one `DirectPainter` per copy, as independent sub-strokes. They are refused at stroke start, with the toast `SymmetryLabels.OUT_OF_MEMORY` ("Not enough memory for symmetry with a brush this large"), when the painters would need more than half the free heap (estimated at 20 bytes per px of each copy's reach).
- One `PixelEditAction` covers every copy. Dabs are never dropped for copies; `StrokeCost` counts only the stroke's own dabs.
- When there are no maps, the code path is the v1.6 one, byte for byte.

**Vector layers.**
- `StrokeInfo.copies` → `VectorStrokeCapture` stores ONE `VStroke` with `copies`.
- `StrokeRaster.render(…, copies)` replays in the same order (dab, then each copy), with the stroke's seed, into one buffer, so the redraw equals the live stroke within ±1.
- Reach and tile rejection use the union over the copies. Per render, `copiesReach` also marks which copies can reach the clip (`near`), and only those are placed.
- The vector-layer eraser is never repeated. "Symmetry doesn't apply to erasing vector objects" shows once per tool. A partial pass over any copy removes the whole stroke in one step.

**Guides.**
- The F5 hook `SymmetryGuides.draw(canvas, t, doc, editing)` draws only when `editing` is true (Symmetry tool active): the lines plus the 44 dp handles.
- `BrushTool.drawOverlay` calls `SymmetryGuides.drawGuides` while it repeats strokes.
- Lines are dashed in the accent colour, clipped to the canvas, then to the screen.
- The array grid is thinned so lines are at least 10 dp apart, at most 400 lines.

**Tool and UI.**
- `SymmetryTool` with `SymmetryHandles`:
  - The angle knob sits 76 dp out and snaps to 15° within 3°.
  - "Spacing X" also sets the grid angle; "Spacing Y" sets the length along v.
  - Corners stay convex.
  - Dragging elsewhere moves the ruler, with a 6 dp slop.
- `SymmetryOptions`: type chips, value chips, "Reset symmetry" and "Done".
- `SymmetryRulerSection` in the Ruler panel: "Place on canvas" closes the sheet and selects the tool.

**Budgets** (§6.3, asserted by `SymmetryBudgetRobolectricTest` with `PerfBudget.ms`). These are JVM numbers for stroke work per frame; the T606 times are device checks.
- Mirror, 100 px brush: about 2.7 ms (budget 16).
- 32 copies of a 50 px brush: about 2.9 ms (budget 33).
- 64 copies of a 64 px brush: about 5 ms (budget 16).

## Storage (`storage/`)
Each project is a folder in app-private storage: `project.json` (document + layer properties),
one compressed raw pixel file per layer/mask, and `thumb.png`. Saves are incremental (only layers
whose `contentVersion` changed). The editor autosaves every 45 s and whenever the app goes to the
background or the user returns to the gallery. v1.5 adds a `.vec` file per vector layer and the
mask spec / adjustment of each layer inside `project.json`; data that can't be read on open is
dropped for its layer only and listed in `Document.loadWarnings` (shown once by the editor).

## UI (`ui/`)
`MainActivity` → gallery or editor. `ui/common/Components.kt` holds shared controls (sheets,
nudge pad, chips, swatches) and `ui/common/NumberControls.kt` (v1.6) the number controls (typeable
sliders, numeric/length fields with units + automatic sliders / drag-to-scrub, with increment
parameters) — use them everywhere.

Editor layout (`ui/editor`, v1.6 ibisPaint main screen): the canvas is full bleed on the light
surround (`IbisColors.Surround`; dark status-bar icons, black navigation bar). At the top: the top
row (`chrome/TopRow`: 8 circles Undo, Redo, Vector, Selection, Stabilizer, Grid, Ruler, More
options at a 48 dp pitch, with an 8 dp gap after Redo where the screen has room — centres 24, 72,
then 128 + 48·(i − 2); under 336 dp Ruler, Grid and Stabilizer fold into the More menu), the
floating options strip (`chrome/OptionsStripPanel` around `ToolOptionsBar`, hidden for tools
without options), the X / Y pill and the selection / object bar under it. At the bottom: the brush
slider rows (`SliderBar.BrushSliderRows`: [value][−][track][+], relative drag, Size / Percent
increments, long-press Step popup) and the bottom bar (`chrome/BottomBar`: eraser switch, tool
menu, size disc, colour, hide interface, layers, back to the gallery). The tool menu
(`chrome/ToolMenuPanel`, two columns of cells in `ToolMenu` order, Filters / Canvas / Settings
among them) replaced the Tools sheet and `ToolGrid`; More options opens a dropdown with the v1.5
overflow entries plus Canvas… and Increments…, headed by the document's name and size. The bands
are pure functions of the screen size and insets in `chrome/ChromeLayout` (values from
`IbisDims`); the pill, the selection bar and "hide interface" never refit the canvas. Panels
(`BwSheet` in the non-modal `SheetHost`, ui/common/SheetHost.kt) sit on the bottom bar at half
height (black α 0.78, top corners 12): touching the canvas minimizes the top panel to a pill
(announced as "<title>, minimized", acting as "Show <title>") and the touch reaches the canvas.
✓ / ✕ float centred 8 dp above the slider rows (right-aligned while the tool menu is open, above
the layer window's top-right corner while it is open). The InfoChip slot (top centre) shows tap
feedback, zoom / rotation and the increments readout. The selection bar's delete item reads
"Clear" (its step's name; "Delete" is the Transform strip's).

The X / Y pill (`ui/tools/CoordinatePill`, v1.6) replaced the two-row X / Y strip: a fold cell,
beveled "X" and "Y" cells whose number IS the slider (drag it: dx / zoom document px; more than
48 dp off the cell gives Fine ×0.1; detents at 0, the centre and the edge; Length steps with
increments on; tap types a value, long-press opens the Step popup) and the "#" increments cell.
Screen readers get "X slider" / "Y slider" with setProgress and the "Increase X" / "Decrease X"
actions (the v1.5 "X plus 1 pixel" arrows are gone). Adapters in `CoordinateSources` serve
Transform, Shape, Text, the Curve and Path points and any `PositionedTool` (Masks, Clone); a drag
or typed value is one edit from `beginPositionEdit` to `endPositionEdit`, however long the finger
rests on the way — the Curve and Shape tools hold their in-tool step open in between
(`beginNumericEdit` / `endNumericEdit`).

The layer window (`ui/layers`, v1.6) is a non-modal floating window the host sizes
(`ChromeLayout.layerWindow`: min(382, w − 10) × min(520, room below the top row − 8) at x 5, its
bottom on the bottom bar; screens under 480 dp tall keep the v1.5 side-by-side layout) and
`LayersPanel` fills (`LayerWindowMetrics`): a 46 dp header (title, "n / max", ✕ "Close layers"),
a left column with the canvas preview (rendered at most every 500 ms, after the window's first
frame) over six buttons, the bottom-aligned list with the Selection Layer row and 80 dp rows
(clip bracket, thumbnail with kind / frame badge, mask square, eye, the number and opacity over
blend mode at ibisPaint's 18 sp, fitted down to 12 sp on narrow rows (the number to 9 sp only when
three digits share the narrowest row with every badge), the MASK and lock badges, ≡ handle; on
the narrowest rows MASK gives way to the mask square's accent border), the transparency squares,
the 9-icon right strip, the 56 dp blend row and the 48 dp opacity row (an adjustment layer's
opacity goes through `liveAdjust`; Percent increments). The blend row's Clipping / Alpha lock /
Lock toggles show icons only (their captions stay the spoken text), and the strip's flips use
ibisPaint's flip glyphs (`ChromeGlyphs.FlipHorizontal` / `FlipVertical`).

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
