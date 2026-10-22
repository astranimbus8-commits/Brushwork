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

## Invariants (v1.8)
I1–I14 stay as they are. Three of them carry most of v1.8's weight:
- **I10 (labels are an API):** every new visible text and description is a constant in `ui/common/LabelsV18.kt` (tags in `V18Tags`). `LabelsV18Test` checks the substring rule and that no main source retypes a value; `ownership-check-v18.ps1` rule L fails an area branch that adds one as a literal outside `ui/common/`. The integration added three, which `LabelsV18Test` counts: `MagneticLabels18.OUT_OF_MEMORY` ("Magnetic lasso"), `ColorSelectLabels18.NO_MATCH` ("Color select") and `TransformLabels18.BOX_HINT` ("The converted strips of area G"). The final QA added `ColorSelectLabels18.NO_SAMPLES` (the Color range chip with no sample). The v1.8 polish added `TransformLabels18.INTERPOLATION_CAPTION` (the caption on "Choose interpolation") and `MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE` (the clone stamp's refusal under the Perspective array): 101 labels. It also adds screen-reader names composed from a value, which are functions, not counted constants: `ArrayLabels18.tintColor` (a Tint colors stop) in `LabelsV18.kt`; beside their v1.7 controls, `colorStopDescription` (a filter gradient's stop, `GradientEditor`), `newSeedDescription` (the seed's shuffle button, "New random seed") and the shared slider's "<label> slider" (`LabeledSlider`). It also adds the Curves sheet's two lines, `CurveGenLabels18.petalsDrawn`, `pointsLimit` and `pointsAtLimit` (visible texts with a digit).
- **I13 / I13b (byte-exact compatibility):** every new field is `@EncodeDefault(NEVER)` with a default that reproduces v1.7, and no codec `version` changes (`ShapeCodec` stays 2, `ArrayCodec` 1). The v1.6 goldens and the v1.7 fixtures re-encode byte for byte (`CodecV18GoldenTest`); frozen v1.7 decoders in test sources (`compat17`, copied from `885793b4`) read v1.8 files as the compatibility table says (`CompatV17DecodeTest`).
- **I14 (ArrayDraw is the one draw seam):** it stays so; per-copy data comes from `ArrayInstances.of` (area B wires it).

v1.8 adds five:
- **I15 Copies are data.** An object's symmetry copies are a `CopyMaps` (`vector/CopyMaps.kt`): affine 3 × 3 maps, identity first, 2..256 maps (`StrokeCopies.MAX`), never baked into the object's geometry. A homography never becomes a copy: it bakes plain objects, in one step; on a vector layer a baked copy whose bounds lie wholly off the canvas is dropped (it would paint nothing; the source always stays; not on a layer with a live array, whose instances follow the content's bounds: `ObjectCopies.baked(o, maps, canvas)`, `bakeCanvas`). Committing a symmetric curve or shape is ONE undo step, whatever the number of copies. Checked by `CopyMapsTest`, `CopyMapsCodecTest`, `SymmetricCommitUndoTest`.
- **I16 Generators match their geometry.** A `VPath` with a `generator` has geometry equal to `CurveGenerators.build(generator, kind)` within 0.01 px. A hand edit of a point clears the generator in the same in-tool step (`CurveTool.editGeometry`); a reopened path that fails the check loses its generator silently; thickness edits are not geometry. `VectorOps.transformed` maps the generator with the path under a similarity (`CurveGenerator.mapped`) and drops it under any other map (`VectorOpsGeneratorTest`). On the foundation `CurveGenerators.matches` was a stub (false) that nothing called; area E made it real, and `CurveTool` runs it when it reopens a path and again when it stores one (`open`, `checkedGenerator`): a path that fails it loses its generator. Checked by E's `GeneratorMatchTest`, `GeneratorClearTest`, `GeneratorTransformTest`.
- **I17 Copies have readers.** Every reader of a `VPath`, a `VShape` or a shape layer's `ShapeObject` goes through `vector/ObjectCopies.kt` and keeps, bakes or (documented) ignores the copies as the readers table below says. A new reader without copies fails `CopiesReadersAuditTest`.
- **I18 Strip placement is a pure function.** Which items sit in row 1 and which in the More row is `StripLayout.split(spec, availableDp)`: a function of the items' declared widths, rows and ranks, never of measured text. At 360 dp and wider (`FIXED_ROW1_MIN_DP`, private in `StripHost.kt`) row 1 never scrolls, and a debug build (`FLAG_DEBUGGABLE`) checks that its items fit their budget: the check throws only at that width or wider. Below 360 dp the budgets do not hold: row 1's cells scroll sideways, the More toggle stays pinned at the end of row 1, and the check is skipped. Checked by `StripLayoutTest`, `StripInventoryTest`, `StripRow1CheckTest`, `StripRow1NarrowTest`.
- **I19 Array looks never touch the source.** Instance 0 is drawn exactly as in v1.7, and with `look == null` every instance is bit-identical to v1.7: `ArrayInstances.of` returns `ArrayLayout.matrices` unchanged. Checked by `ArrayInstancesTest`, B's `ArrayLookSourceTest` and the v1.7 array goldens.

## Invariants (v1.9)
I1–I19 stay as they are. I10 now reads `ui/common/LabelsV19.kt` (tags in `V19Tags`) as well, checked by `LabelsV19Test` and rule L of `ownership-check-v19.ps1`; I13 keeps every codec version of v1.8 (`TextCodec` 5, `ShapeCodec` 2, `VectorCodec` 1, `ProjectFormat` 3, `Payload` 2). v1.9 adds eight. Where an invariant names an area's work, the foundation put the seam in place and a lead test pins the v1.8 behaviour until the area lands ("v1.9 foundation (gate 1)" below):
- **I20 Erase never rasterizes a vector or shape layer silently.** Such a layer becomes pixels only through the "Turn into pixels" dialog (A5) or an existing explicit rasterize. Every erase path asks `EraseRouting.route(layer, source, maskTarget, isPath)` (`vector/draw/EraseRouting.kt`) first; `BrushTool.start` does. Text layers keep v1.8's undoable rasterize with its toast. On `main` at gate 1 the routing still reproduces v1.8 (`RASTERIZE_DATA` for a text or shape layer, and for a vector layer erased along a path); A5 replaces it with the table of R11 (b), and the invariant holds from A5's merge. Checked by `EraseRoutingV18Test` (the v1.8 routing) and A5's routing tests.
- **I21 Paper is not pixels.** `Document.paper` (a `PaperSpec`, one colour) is drawn by `PaperBackdrop` at the listed sites only ("Paper and its sites" below). No layer bitmap, merge result, layer export, clipboard payload or undo snapshot contains it. Checked by `PaperHookNoOpTest` (every site is v1.8 without a paper), `PaperV19Test` and `PaperRepositoryRobolectricTest`.
- **I22 Session history is session state.** Filter sessions (A2), text-path node edits (A3) and the bevel popups (A1, A9) keep their own steps (`tools/ToolHistory.kt`) and show them to Undo and Redo through `InToolHistoryOwner`; their commit is one document step (I2). Checked by `ToolHistoryTest` and `InToolHistorySeamTest`.
- **I23 Backup never writes into a project folder.** Restore writes only through a `.restore-<id>` staging folder and never overwrites an existing project id (A8, `storage/backup/**`).
- **I24 Every text reader handles several items.** No `TextCodec.decode(` or `encode(` outside `tools/text/TextCodec.kt` (rule T); every other reader and writer uses `decodeAll` / `encodeAll` (F2). `TextCodec.wrapSourceId` and `withWrapSource` read and write every item (F1a). A text that is a frame of a story or wraps around a picture is always alone in its layer (A3 keeps it so), so re-flow and frame code never meet `more`. Checked by `TextCodecGateTest`, `TextMultiItemReadersTest` and A3's `MultiTextLayerRobolectricTest`.
- **I25 Preview equals commit.** An optimisation that skips a re-render at commit (`EditorController.commitEdit(..., displayCurrent = true)`, which makes `EditInvalidation.afterCommit` return null) ships only with a parity test that compares the preview and the committed pixels (A6). At gate 1 no caller passes `true`, so every commit invalidates as in v1.8.
- **I26 UI memory is not history.** State that must survive history taps (the open More rows) lives in `UiPrefs` (the file "brushwork_ui"), never in the journaled `AppSettings` ("UiPrefs versus AppSettings" below). Checked by `UiPrefsJournalTest` and `UiPrefsStickyRowsTest`.
- **I27 Large text never clips a strip.** `StripLayout` sizes cells by their declared widths (I18), so every new v1.9 strip cell, popup and sheet control keeps its text inside its cell at font scale 1.3 and 2.0, at 392 and 360 dp: a label wraps, shrinks (to 11 sp at least) or moves the control to the next line rather than overflow. Each area's narrow UI test for its new cells runs at those scales and calls `Qa19Ui.assertNoTextOverflow()`; the lead's `StripFontScaleTest` (gate 2, design 3.4) checks every tool with all areas merged.

## Invariants (v1.10)
I1–I27 stay as they are. I10 also reads `ui/common/LabelsV110.kt` (tags in `V110Tags`), checked by `LabelsV110Test`. v1.10 adds eight, I28–I35 (design 0.4): the lead writes I35 with the UI seams (F0) and I28–I34 with the engine contracts (F1). Where an invariant names an area's work, the foundation put the seam in place ("v1.10 foundation (gates 0 and 1)" below):
- **I35 Object colors go through one walker.** Every per-object color change (R2 pill, R5 vector recolor, R6 filter, R7 previews) reads and writes colors through `vector/ObjectColorSlots`, which applies the fill-follows pin (C4).

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

## v1.8 overview

v1.8 answers nine requests from the user. The lead built a foundation on `main`, eight areas
built the items in parallel on top of it, and the lead integrated them (`v18I`).

**The request, in the user's words:**

> "1. make other tools compatible with mirrors like curves, paths, clone stamp, etc. Put the select several as a small icon right below or besides the X Y scale and transform,
> 2. Make it so that array allows you to change progressively the transparency, colors, size, etc of each copy with a gradient
> 3. Add a magnet selection tool
> 4. Also add a color selection tool
> 5. Make it so you can choose the colors of chromatic aberration and glitch filters
> 6. Add curve options like archemedian that you can edit
> 7. also add a cog shape in shapes
> 8. Organize the menus that appear on top better, and also add 2 rows of the menu when needed
> 9. Also improve the ui so it is more intuitive for top menus"

**The user's answers (binding):**
- Color selection: both. Color Range (tap colors on the canvas or pick one, a Fuzziness slider
  for soft edges, a red preview before confirming) and preset chips by hue, saturation or
  brightness.
- Magnet: tap to tap. Each segment between two taps follows the strongest edge; every point can
  be dragged and undone, as in the polygon lasso.
- Array colors: a gradient tint of 2 or more stops. Each copy is tinted toward its color by a
  Strength %, keeping its shading and transparency, on pixels, text, shapes and vectors.
- Second row: one row, plus a More button that drops down the second row only when tapped.

Item 1 is split in two: 1a (mirrors for Curve, Polyline, Path, Shape and Clone stamp) and 1b
("Select several" as a cell of the X / Y pill). The item list under "v1.8 foundation" below gives
each item's short name.

**Areas, gates and merge order.**

| Area | Name | Gate | Items |
|---|---|---|---|
| A | FILTERS | 1 | 5 |
| B | ARRAY | 1 | 2 |
| C | SELECT | 2 | 3, 4; the selection strips |
| D | MIRRORS | 1 | 1a for the raster tools, the clone stamp and the guides; the Brush, Clone stamp, Ruler and Symmetry strips |
| E | CURVES | 2 | 1a for Curve, Polyline and Path, 6; their strips |
| F | COG/SHAPES | 2 | 1a for Shape, 7; the Shape strip |
| G | STRIPS-1 | 2 | 8 for Transform, Text, Frame divider, Text frames, Masks, Remove and Pathfinder |
| H | CHROME | 2 | 8 (the strip host, the More rows), 9 |

The design calls C, F and G SELECTION, SHAPES and STRIPS; the ownership table under "v1.8
foundation" uses those names.
- **Gate 1** follows the foundation steps F0 to F3: A, B and D branch from `main`.
- **Gate 2** follows F4 ("Select several" in the pill): C, E, F, G and H branch.
- **Merge order:** A, B, C, D, E, F, G, H, by letter. H merges last, so it sees every converted
  strip. Then the lead's integration (`v18I`), then device QA on the phone.

**Where each area's section is** (by heading):

| Area | Section |
|---|---|
| Foundation (F0 to F4) | "v1.8 foundation (gate 1)" below: object copies, array instances, the options strip and its More row, seams and new tools, "Select several" in the pill |
| A | "Fringe colors (v1.8 item 5, area A)", under "Filters" |
| B | "Array instances and looks", after "Live arrays" |
| C | "Selection tools: Magnetic lasso, Color select and their strips" |
| D | "Mirrors for raster tools, the clone and the guides (v1.8 item 1a, area D)", under "Symmetry". D's four strips have no section of their own: `ui/brush/BrushStrip.kt`, `ui/clone/CloneStrip.kt`, `ui/assist/RulerStrip.kt` and `ui/symmetry/SymmetryStrip.kt`, checked by `BrushStripTest`, `CloneStripTest`, `RulerStripTest` and `SymmetryStripTest` |
| E | "Curve generators (v1.8 item 6, area E)", "Mirrors for Curve, Polyline and Path" and "The curve strips" |
| F | "Shapes in v1.8: the cog, object copies and the Shape strip" |
| G | "The converted strips of area G", under "Converted strips and top menus" |
| H | "Top menus", under the same heading; the strip host and the object bar's More row are in "The options strip and its More row" (foundation) |
| Integration (`v18I`) | "Invariants (v1.8)", "Formats and compatibility (v1.8)" and "Testing and CI (v1.8)"; its strip changes (row 1 below 360 dp, the hint's lines, history taps through an open More row) are in "The options strip and its More row" |

## v1.8 foundation (gate 1)

The lead's part of v1.8, written at gate 1. The item sections (clone with symmetry, curve
generators, the cog, Magnetic lasso, Color select, fringe colors, top menus) were written as the
areas merged and completed at integration (`v18I`); "v1.8 overview" above says where each one is.

### Items, foundation steps and area ownership

The items:
- 1a mirrors for curves, polylines, paths, shapes and the clone stamp; 1b "Select several" in the X / Y pill;
- 2 Array looks (opacity, size, turn and tint from the first copy to the last);
- 3 Magnetic lasso (tap to tap); 4 Color select (color range plus preset chips);
- 5 choosable colors for Chromatic aberration and Glitch;
- 6 editable curve generators (spirals, wave, rose); 7 the Cog shape;
- 8 the options strip with its More row; 9 the top menus (long-press labels, sections, opaque menus).

The foundation (the lead, branch `v18/foundation`, then `main`), each step its own commits
`v18F<n>: <what>`:
- **F0** test infrastructure only: `PerfBudget.within` / `ratioWithin` (retrying timing guards), the
  `Timing` JUnit category on every timed test (`TimingTaggedTest` keeps it true), the canvas
  capability probes, frozen v1.7 decoders and filters in test sources (`compat17`, `filters/art/v17`).
- **F1** model, codecs and labels: `CopyMaps`, `CurveGenerator`, `VPath.copies` / `generator`,
  `ArrayLook`, `ArrayInstances`, `ArrayTint`, the cog fields of `ShapeObject` and the interim
  `ShapeType.COG`, `FilterParam.Fringes`, `LabelsV18.kt` and `V18Tags`, `IbisColors.Menu`, the v1.8
  history labels.
- **F2** the copies readers (I17), `ObjectActions.detachCopies`, `ShapeOutlines` moved to its own
  file, the `CogGeometry` stub.
- **F3** the strip model and host, `ToolStrips` and the 13 builder stubs, the two new tools
  (stubs) and the 30-cell tool menu, the seams (`beginPath` maps, `SymmetryGuides.drawFor`,
  `CurveGenerators`), `Modifier.longPressLabel`, the More-row aware test helpers.
- **Gate 1:** A, B and D branch. **F4** ("Select several" in the pill, the strips' point undo
  removed), then **Gate 2:** C, E, F, G and H branch.

| Area | Name | Gate | Items | Owns (main sources) |
|---|---|---|---|---|
| A | FILTERS | 1 | 5 | `filters/art/ChromaticAberration.kt`, `GlitchFilter.kt`, `ArtMath.kt`, `FringeColors.kt`; `ui/filters/FringeControl.kt` |
| B | ARRAY | 1 | 2 | `array/**`, `tools/array/**`, `ui/array/**`, `engine/ArrayDraw.kt`, `exchange/export/ExportSceneBuilder.kt` |
| C | SELECTION | 2 | 3, 4; the selection strips | `tools/select/**`, `ui/selection/**` |
| D | MIRRORS | 1 | 1a (raster, clone, guides); brush, clone, ruler, symmetry strips | `brush/**`, `assist/**`, `tools/symmetry/**`, `ui/symmetry/**`, `ui/assist/**`, `tools/clone/**`, `ui/clone/**`, `ui/brush/**`, `ui/vector/VectorEraserOptions.kt` |
| E | CURVES | 2 | 1a (curves), 6; Curve, Polyline, Path strips | `tools/vector/Curve*.kt`, `BrushStroking.kt`, `VectorPath.kt`, `VariableWidthOutline.kt`, `spline/**`, `gen/**`; `tools/points/**` except `PointEditor.kt`; `ui/vector/Curve*.kt`, `ui/points/**` |
| F | SHAPES | 2 | 1a (shapes), 7; Shape strip | `tools/vector/Shape*.kt` except `ShapeObject.kt`, `tools/vector/CogGeometry.kt`, `ui/vector/Shape*.kt` |
| G | STRIPS | 2 | 8 for Transform, Text, Frame divider, Text frames, Masks, Remove, Pathfinder | `ui/placement/**`, `ui/textframes/**`, `ui/mask/**`, `ui/remove/**`, `ui/pathfinder/**`, `tools/transform/**` |
| H | CHROME | 2 | 8 (host, More row), 9 | `ui/editor/**`, `ui/theme/**`, `ui/common/**`, `ui/tools/**`, `ui/vector/VectorObjectBar.kt`, except the frozen files below |

The letters are the merge order (A first, H last: H sees every converted strip). Then the lead's
integration (`v18I`) and device QA. Everything else is frozen (the lead's), among it
`EditorController.kt`, `model/**`, `vector/**`, `engine/**` (except B's `ArrayDraw.kt`),
`exchange/**`, `filters/**` (except A's files), `ui/common/LabelsV17.kt`, `LabelsV18.kt`,
`LongPressLabel.kt`, `IncrementsLocal.kt`, `ui/editor/HistoryLabels.kt`,
`ui/tools/strip/StripModel.kt`, `StripLayout.kt` and `ui/tools/ToolStrips.kt`. Eight test helpers
are frozen too (`$FrozenTests`): `smoke/SmokeUi.kt`, `testing/StripTestKit.kt`,
`testing/PerfBudget.kt`, `qa16/QaCurves.kt`, `qa16/Qa16Ui.kt`, `ui/editor/HistoryTapFingers.kt`,
`ui/editor/chrome/UniqueLabelsV17.kt` and (F4) `testing/PillTestKit.kt`. `.wt/_tools/ownership-check-v18.ps1 -Area <X> -Worktree <wt>`
enforces the tables (design §5.3), the frozen test helpers, rule S (no layer inserted or removed
by hand outside `engine/`) and rule L (no v1.8 label retyped as a literal outside `ui/common/`).

### Object copies (`vector/CopyMaps.kt`, `vector/ObjectCopies.kt`; F1, F2)

- **Data (I15).** `VPath.copies` and `ShapeObject.copies` (a `VShape`'s copies are its shape's) are
  a `CopyMaps`: `CopyMaps.of(maps)` sanitizes (drops non-finite, singular and non-affine maps,
  puts the exact identity first, keeps at most 256, `NONE` below 2), and `CopyMaps.NONE` means no
  copies. Copy k is the source drawn under C_k (`canvas.concat`). The JSON is exactly
  `VStroke.copies`' (a list of 9-float lists), `@EncodeDefault(NEVER)`: an object without copies
  encodes as v1.7, and v1.7 drops the field (a v1.7 re-render shows the source only; see
  "Formats and compatibility (v1.8)").
- **The seams (§4.4).** `ObjectCopies.mapsAt(c, x, y)` is `SymmetryMaps.transforms` at the first
  anchor (empty when symmetry is off); `isPerspective(maps)` and `fromSymmetry(maps)` (null = a
  homography: bake plain objects instead, in the same step).
- **Readers (I17).** Every reader of a `VPath`, a `VShape` or a shape layer's `ShapeObject` goes
  through `ObjectCopies`, and an object without copies is read exactly as in v1.7 (every helper
  returns its input or calls the v1.7 code once):

  | Reader | Copies |
  |---|---|
  | Vector render, bounds, hit test, lasso and object pick (`vector/render/**`, `vector/select/**`, `ObjectMapping`, `VectorLayers`, `VectorLayerOps`, `VectorRender`, `VectorTools`) | drawn and hit per copy (`drawEach`, `mappedBounds` / `eachBounds`, `hits`) |
  | Object eraser / partial eraser (`EraseSession`, `EraseTargets`, `VectorFill`) | all copies go / expanded first (`expanded`) |
  | Transform of an object (`VectorOps.transformed`, `ShapeAffine`, `LayerDataTransforms`; shape layers: `ShapeTransforms`) | kept and conjugated (m·C_k·m⁻¹, `CopyMaps.conjugated`) under an affine map; expanded under a homography (`transformedAll`, `mapped`) |
  | Shape-layer render and reopen (`ShapeTool`), shape layer to vector layer (`convertToVectorLayer`), payload import, lift (`vector/lift/**`, `DataLift`) | kept |
  | Pathfinder, export (`ExportSceneBuilder`: one part per copy), Array sources and `ArrayDraw.effectiveVector` | baked |
  | Array guide pick (`ArrayTool.pickPath`, `GuideEditor`) | source only (documented) |

  `CopiesReadersAuditTest` runs a vector-layer and a shape-layer fixture with Mirror copies
  through every row.
- **Detach.** `ObjectActions.detachCopies(c)` (the object bar's entry, while
  `ObjectActions.hasCopiesSelected(c)`) replaces each selected object with copies by its plain
  objects (`ObjectCopies.detached`), one step.

### Array instances and looks (`model/ArrayInstances.kt`, `model/ArrayModel.kt`; F1)

- `ArraySpec.look: ArrayLook?` (last field, `@EncodeDefault(NEVER)`, `ArrayCodec` stays version 1):
  opacity, size and turn from the first copy to the last, an easing (`ArrayEasing`), and tint stops
  with a strength. `ArrayLook.sanitized()` clamps; an identity look is never written.
- `ArrayInstances.of(spec, source)` is the one source of per-copy data, in draw order: v1.7's
  `ArrayLayout.matrices` (frozen) plus, with a look, M_k·T(c)·R(turn_k)·S(size_k)·T(−c) about the
  source's center and a `CopyLook` (opacity, tint) per copy, with t_k = ease((k − 1)/(n − 2)).
  Instance 0 is always the identity without a look, and with `look == null` every matrix is v1.7's
  bit for bit (I19); `ArrayInstances.bounds` is `ArrayLayout.bounds` then.
- `ArrayTint.matrix(tint, strength)` (a `ColorMatrix` for a copy's paint), `apply` (one ARGB value)
  and `sample(stops, t)` (the tint along the copies).
- Area B moves every draw, bounds and export caller of `ArrayLayout` to `ArrayInstances`, adds the
  draft rule above 64 copies and the Look tab; until then nothing reads `look`.

### The options strip and its More row (`ui/tools/strip/`, `ui/tools/ToolStrips.kt`; F3)

- **Model (`StripModel.kt`, frozen).** A tool's strip is a `StripSpec`: `items` (`StripItem`: a
  stable `key`, the `label` the inventory and tests use as the control's handle, a declared
  `width`, `row` ROW1 or MORE, an `overflowRank` (0 = never leaves row 1; above 0 = AUTO, leaves
  row 1 highest rank first), `shown` (false reserves the width and draws nothing), `content`), an
  optional `hint` (the More row's first line, or first two: `StripLayout.MAX_HINT_LINES`),
  `overlays` (every sheet and dialog the strip opens) and `movedInto` (v1.7 handles now inside an
  item's popup). `StripSpec.EMPTY` shows nothing. `StripContext`
  (`selectionBarShown`, `vectorMode`, `closeMore`; the editor's `closeMore` is a no-op since
  v1.9, R3) reaches the builders through `LocalStripContext`.
- **Layout (`StripLayout.kt`, frozen, I18).** `split(spec, availableDp)` is pure: available = row 1's
  content width (the screen less 28 dp: 332 dp at 360, 364 dp at 392) less 44 dp while the VECTOR
  cell shows; the 44 dp toggle (plus its 4 dp gap) is reserved as soon as an item or the hint goes
  to the More row; AUTO items leave by rank, the highest first (equal ranks from the end) and go
  to the More row's front in row-1 order. The item budgets this gives at 360 dp are 332 dp (no
  VECTOR cell, no More row), 288 (VECTOR cell), 284 (More row) and 240 (both). The budgets apply
  at 360 dp and wider (see "Row 1 below 360 dp"). `moreLines(split, hint, availableDp,
  hintLines = 1)` wraps the More row (hint first, on `hintLines` lines) into at most
  `MAX_MORE_LINES` = 3 lines, the overflow joining the last line; `hintLineCount(split,
  hintLines)` keeps the hint's count within 1 .. `MAX_HINT_LINES` = 2 (see "The hint's lines");
  `moreRowDp(n)` = 44n + 4(n − 1).
- **Dispatcher (`ToolStrips.of(c)`, frozen).** One builder per tool, in its owner's folder
  (`@Composable internal fun xxxStrip(tool, c): StripSpec`, 13 files). Every builder returns a
  converted spec, or `StripSpec.EMPTY` for a tool state that has nothing to show.
- **Host (`StripHost.kt`, H's after F3).** `ToolOptionsBar` is
  `StripHost(ToolStrips.of(controller), ...)`. The host draws row 1 (tag `V18Tags.STRIP_ROW1`:
  the 40 dp VECTOR cell on a vector layer, the items, the 44 dp toggle at the end, described
  `StripLabels18.MORE_TOGGLE`, Expanded / Collapsed, tag `V18Tags.STRIP_TOGGLE`) and, while open,
  the More row (tag `V18Tags.STRIP_MORE_ROW`) 4 dp under it, floating over the canvas. Since v1.9
  (R3) only its lines are opaque (`IbisColors.Menu`), content-width and end-aligned under the
  toggle (see "State, memory and canvas touches"). Row 1 is fixed at 360 dp and wider (see "Row 1
  below 360 dp").
  `spec.overlays` are composed whenever the strip shows, so a sheet opened from the More row
  outlives it.
  While the row is open, the toggle is filled with `IbisColors.Accent` and the chevron points up.
  A long press shows its label without toggling. A `SideEffect` hides the row of a spec that can
  no longer open it (`StripState.drop`): one that became wide enough for every item, or one with
  nothing left for the More row. v1.9 (R3): the row stays remembered and shows again as soon as
  the spec shows its toggle again (`StripState.undrop`). Each cell is composed under
  `key(item.key)`, so an item that comes or goes before an open popup, on row 1 or on the More
  row, never resets it (`StripHostStateTest`). `LocalStripOverride` (`ui/tools/ToolOptionsBar.kt`,
  null in the app) lets a test show a fake `StripSpec` on any tool.
- **Row 1 below 360 dp (`StripHost`, `FIXED_ROW1_MIN_DP`; v18I).** `StripHost` compares
  `LocalConfiguration.screenWidthDp` with the private constant `FIXED_ROW1_MIN_DP` (360), the
  narrowest window that §3.9's row-1 budgets are for.
  - At that width or wider nothing changes (I18): row 1 is fixed and never scrolls, and in a
    debug build (`FLAG_DEBUGGABLE`) the host's `check` throws an `IllegalStateException` ending
    "(I18)" when row 1's items overrun their budget.
  - In a narrower window (for example a larger Display size on a 392 dp phone) the budgets do not
    hold, so the check is skipped. The VECTOR cell and row 1's items go in an inner `Row` with
    `widthIn(max = the room the toggle leaves)` and `horizontalScroll`. That row is only as wide
    as its content, so an empty row 1 still reports no width and the panel hides.
  - The More toggle comes after a `weight(1f)` spacer, outside the scroller. It stays pinned at
    the end of row 1, always on screen.
  - The scroll state is `key(toolId, vectorMode) { rememberScrollState() }`, so it starts over
    for each tool and each vector mode. `StripLayout.split` and the budgets do not change.
  - Tests:
    - `StripRow1NarrowTest` (320 dp, the host alone, through semantics actions) covers Brush,
      Curve, Shape, Lasso and Transform's box modes on both layer kinds, plus a fake row too wide
      even for 360 dp. Nothing throws; the row scrolls exactly as far as its content needs; each
      row-1 item, scrolled into view, shows whole inside row 1; the toggle is in view without
      scrolling and stays in place; the More row opens.
    - `StripRow1NarrowFingerTest` (`qa18`, 320 × 640 dp, the real editor, real touch events): the
      same five tools on a raster and a vector layer compose without throwing, with row 1 on
      screen. Where the strip has a More row (7 of the 10 strips), the 44 dp toggle shows whole
      on screen, a finger tap opens the More row on screen and a second tap closes it. A fake
      row 1 of two 200 dp items and a More item (`FakeStrips.items`): the second item starts cut off.
      A slow finger drag scrolls row 1 to its end; the item then shows whole, the toggle stays
      put, nothing fires and the canvas does not change. A tap then reaches the scrolled-in item,
      at the place where the first item was before the scroll. The More item takes a tap, and a
      drag back brings the first item back.
    - `StripRow1CheckTest` (360 dp): the real strips have no scroller, and an over-budget row 1
      still throws.
    - When composition throws, Compose hands the exception to the main thread's
      uncaught-exception handler and stops the recomposer, so the test does not fail by itself.
      All three tests collect the exception there.
- **The hint's lines (v18I).** The More row's hint takes up to `StripLayout.MAX_HINT_LINES` (2)
  lines.
  - The host measures the hint the way the row draws it, with the internal composable
    `rememberHintLines(hint, widthPx)`: a `TextMeasurer` with `LocalTextStyle` merged with the
    hint's private font size, the row's content width in px, and `maxLines = MAX_HINT_LINES`. It
    passes the count to `StripLayout.moreLines(split, hint, availableDp, hintLines)`.
  - `moreLines` puts that many empty hint lines first. They count like any other line toward
    `MAX_MORE_LINES` (3).
  - `StripLayout.hintLineCount(split, hintLines)` keeps the count within 1 .. `MAX_HINT_LINES`.
    While the split sends items to the More row, it also keeps the count at or below
    `MAX_MORE_LINES` less one, so the items keep a line of their own. Items that do not fit join
    that last line, which scrolls sideways, as before.
  - `MoreRow` draws the hint as one block, `moreRowDp(hintLines)` tall, with `maxLines =
    hintLines` and an ellipsis. `StripState.moreRowDp` counts the hint's lines, and so do the X /
    Y pill and the selection and object bars, which move with it.
  - At 360 and 392 dp, only the Text frames hint with a frame selected
    (`TextFrameStripItems.FRAME_HINT`) takes more than one line. Every other hint takes one.
  - Tests: `StripLayoutTest` covers a two-line hint alone, with items, crowded, and at every
    measured count. The test rig's `StripRig.hintLines` measures the shown hint at
    `StripRig.WIDTHS` with `rememberHintLines`; rule 5 of `StripInventory.rules(spec, hintLines)`
    counts those lines, and no hint line may hold items. `TextFrameStripTest` checks every text
    for clipping, the hint included, and checks the row height for the hint's lines.
- **State, memory and canvas touches (D25; v1.9, R3 "sticky rows").** `StripState` (hoisted in
  `EditorScreen` per controller, with the app's `UiPrefs`) holds the rows the user opened and
  `moreRowDp`. A row opens and closes only through its own toggle (the strip's
  `V18Tags.STRIP_TOGGLE`, the object bar's More toggle); nothing else forgets it. v1.9 replaced
  v1.8's rule that a canvas touch, a tool change, a chrome surface or an item's `closeMore`
  closes the row.
  - Memory (fact V27, I26). `toggle(id)` (the strip's toggle) writes `UiPrefs.stripOpenRows`
    (file "brushwork_ui", key "strip.openRows": a string set of `ToolId` names; names this version
    does not know are written back as they were). `toggleObjectRow()` (the object bar's toggle)
    writes `UiPrefs.objectBarMoreOpen` ("objectBar.moreOpen"). Both load when the state is made,
    so after a relaunch the remembered row shows from the tool's first frame. Nothing else writes
    them, and a history tap's `restoreUiMark` never takes them back. `UiPrefs` is not journaled
    and not in the backup's settings.json. Without `prefs` (a test rig, a bar on its own) the
    memory lasts as long as the state.
  - Hiding never forgets:
    - `closeAll()` hides every remembered row until its tool's next `enter(id)` or its toggle.
      The host's `LaunchedEffect(state, toolId, vectorMode)` calls `enter(toolId)` on a tool or
      vector-mode change: `closeAll()`, then the tool's memory and the object bar's row show
      again, in one snapshot. `showsFor(id)` (what the host draws) ignores the hides until
      `enter` ran for that tool, so the remembered row shows from the tool's first frame and the
      entry changes nothing on screen. The host's `DisposableEffect` calls `left()` when the strip
      leaves the screen (a filter, the interface hidden).
    - `drop(id)` / `undrop(id)`: the host's `SideEffect` hides a tool's row while its spec shows
      no More toggle, until it shows one again (Transform's next transformation, a narrower
      window).
    - `hiddenByChrome` hides every row while it is true. `EditorScreen` sets it, edge-triggered,
      while the layer window, a panel (not minimized to its pill), an expanded sheet, the tool menu
      or "Hide interface" covers the rows; they show again when the cover goes. A toggle tap
      meanwhile clears it: the user asked for that row. The top-right "More options" menu,
      dropdowns, dialogs and anchored popups are windows over the row: they keep closing on an
      outside tap and leave the row as it was.
    - One row at a time: while objects are selected and the object bar's row shows
      (`objectRowOpen`: remembered, not hidden, and the bar shows its toggle, `objectBarShown`),
      the tool's row is hidden. When the objects are deselected (`objectBarGone()`) or that row
      closes, the tool's remembered row shows again. The strip's toggle opening its row hides the
      object bar's row likewise, until the next `enter` or the bar's next showing.
  - `StripContext.closeMore` is a no-op in the editor ("v1.9 (R3): rows close only by their
    toggle"); the 33 call sites stay, and the sheet an item opens covers the row instead.
    `ToolOptionsBar` forwards the outer context's `closeMore`, so a test rig (`StripRig`, whose
    `closeMore` is `closeAll`) can still hide the row with it.
  - The X / Y pill and the selection and object bars move down by `moreRowDp` + 4 dp
    (`ChromeLayout.pillTop(statusDp, moreRowDp)`, `selectionBarTop(..., moreRowDp)`), animated
    over 150 ms.
  - Opening or closing a row never moves the canvas: the row floats over it. An explicit fit
    (opening an artwork, the first fit after a relaunch, "Fit to screen") leaves room for the open
    row: `ChromeLayout.fitInsetTop(statusDp, moreRowDp = 0f)` adds the row's height and its 4 dp
    gap to v1.8's inset (137 dp on the reference phone, 185 dp with a one-line row), and
    `EditorScreen` gives `CanvasView.fitTopExtra` the difference, which only a fit reads.
  - Canvas touches. A canvas touch is never consumed (v1.8's `Mode.CONSUMED`,
    `EditorScreen.consumeCanvasTouch` and `CanvasView.onConsumeTouch` are gone): one finger uses
    the tool, two fingers pan and zoom, two- and three-finger taps undo and redo, and the row
    stays open.
    - The row covers only its lines (`MoreRow`, `MoreLine`): each line, and the hint's block, is
      opaque behind its own items with a 1 dp edge, content-width and end-aligned under the
      toggle; only a line too wide for the strip spans it and scrolls sideways. The band (tag
      `V18Tags.STRIP_MORE_ROW`, the strip's width, the row's height) has no pointer input, so a
      touch beside a line, in the 4 dp gaps or on a line's background between controls reaches
      the canvas.
    - `CanvasView.onTouchDown` / `onTouchSlop` / `onTouchEnd` drive `StripState.canvasTouchStarted`
      / `canvasTouchSlop` / `canvasTouchEnded` (`canvasTouchDown`, `canvasTouchMoved`). Once the
      touch passes the touch slop the rows fade to `StripState.ROW_FADE_ALPHA` (0.25, in a
      graphics layer, never a recomposition) and come back when the last finger lifts.
    - While a canvas touch is down, `Modifier.passCanvasGesture(state, taps)` on each control of
      the More row and of the object bar's row (never on a line) consumes a pointer that lands on
      it, in the Initial pass, so the control never sees it; pointers that landed before the
      canvas touch keep working. The canvas view gets every pointer of the window, so that finger
      pinches or pans with the canvas' own. `HistoryTapSink.onCanvasDown(HistoryTapSlots.EDITOR,
      id)` tells the hub it is a canvas pointer, so the canvas keeps the gesture and recognizes
      its two- and three-finger taps itself instead of the hub claiming a tap over the UI.
    - When such a joined finger lifts last (the window's `ACTION_UP` or `ACTION_CANCEL`), Compose
      gives that event to the row only. `StripState.joinedGestureEnded(e)` hands it to
      `endCanvasGesture`, which `EditorScreen` sets to `CanvasView.endJoinedGesture(e)` (the event
      moved to the view's coordinates and dispatched while the view's touch span is open), and
      ends the touch. The joined finger's moves after the canvas' own fingers lifted do not reach
      the view.
    - A canvas finger first, then a finger on any other control (row 1, the pill, the top row):
      `HistoryTapHub` claims the gesture as before, and the canvas yields
      (`CanvasView.yieldToHistoryTap`; see "History taps over the UI").
  - `MoreLine` is a composable of its own, with if / else branches instead of early returns in
    the row's loop: the row now stays open while a tool's spec changes (Transform's box to Free
    deform, a polygon lasso's first corner), and those returns left the composition's groups
    inconsistent (a removed node not found, or a composition that never ended).
  - Tests:
    - `StripHostStateTest`: a spec without the toggle hides its row remembered; the object row
      hides the tool's row and keeps every tool's entry, and deselecting brings it back; a tool
      entry shows the remembered row again after `closeAll`.
    - `UiPrefsStickyRowsTest` (design 2.8's `UiPrefsJournalTest` cases): a history tap never
      changes "strip.openRows"; `closeAll`, `drop`, a tool entry, the chrome covers, the object bar
      and the one-row-at-a-time rule never write it; the object bar's toggle writes only its key.
    - `FitInsetTopTest` (JVM): `fitInsetTop` without a row is v1.8's, and each of 1 to 3 lines adds
      its height and gap.
    - `MoreRowHistoryTapsTest` (`qa18`, 392 × 873 dp, real multi-finger events through the
      editor's window, on the strip's row and on the object bar's; the canvas has three states, so
      a doubled undo or redo shows): a two-finger tap undoes once and a three-finger tap redoes
      once, and neither acts with its setting off; a canvas finger plus a finger on a row-1
      control (exactly one undo) and two canvas fingers plus one (exactly one redo), the control
      never firing; a pinch zooms and a pan pans, with no step; with the strip's row a brush drag
      paints one step. The row stays open throughout.
    - `MoreRowCloseTest`, `ObjectBarMoreTest`, `MoreRowOverlayTest`, `Qa18CompatSessionUiTest`,
      the QA menu tests and the cross kit pin the sticky rows: items, canvas touches, tool
      changes, chrome covers and sheets leave the rows remembered.
      `CanvasConsumedTouchRobolectricTest` went with `Mode.CONSUMED`.
    - `qa19/Qa19StickyRowsUiTest` (392 dp, the Shape tool's row in the real editor, a 300 × 600
      artwork that a fit frames under the row): shapes drawn under the row and beside a short line
      land, one step each; a pinch with one finger on the row zooms, with no shape and no step,
      whichever finger lifts first, and the row then takes taps; two-finger taps undo, also with
      one finger on the row; after a tool switch, the layers panel and "Hide interface" the row
      shows again; the "More options" menu still closes on an outside tap; a relaunch shows the
      row from the start, the first fit and "Fit to screen" leave room for it, and opening or
      closing it never moves the canvas. `Qa19StickyRowsNarrowUiTest` runs the canvas touches at
      360 dp at font scales 1, 1.3 and 2.0, with no text overflow in the open row (the Shape
      strip's angle cell `ShapeStripItems.ANGLE_LABEL` was left out until A9 fitted the Shape cells
      at 2.0, I27).
    - `Smoke.controller` clears the `UiPrefs` file, so each test's editors start with no
      remembered row (otherwise a row one editor opened shows in the next).
- **The vector object bar (`ui/vector/VectorObjectBar.kt`, H).**
  - `VectorObjectBar(controller, modifier, state: StripState?)` is one row that never scrolls. `EditorScreen` passes its `stripState`.
  - `ObjectBarLayout.split(units, ranks, innerDp)` is pure. When all the buttons fit at 40 dp (`MIN_ITEM_DP`), they share the row at up to `MAX_ITEM_DP` (46 dp) each, with no toggle.
  - Otherwise the lowest rank leaves row 1 first, a whole rank at a time:

    | Rank | Button(s) |
    |---|---|
    | 1 | Array |
    | 2 | Detach mirror copies (two units wide) |
    | 3 | Forward / Backward |
    | 4 | Front / Back |
    | 5 | Duplicate |
    | 6 | Recolor |
    | 7 | Transform |
    | 8 | Deselect |
    | 9 | Delete |

    This continues until the rest fit beside the 44 dp toggle `StripLabels18.MORE_OBJECT`. The kept buttons are widened to fill the row. Widths are floored to whole px so the toggle measures exactly 44 dp.
  - At 360 and 392 dp, row 1 holds Delete, Duplicate, Front, Back, Recolor, Transform and Deselect. Bring forward, Send backward, Array and Detach mirror copies wait, in that order, in the More row under the bar. Detach mirror copies shows only while a selected object has copies.
  - The open state follows the strip's rules (v1.9, R3; "State, memory and canvas touches"). The bar shows its row while `StripState.objectRowShows()` and it has a toggle, and reports the toggle with `objectBarShown` from a `SideEffect`; `StripState.objectRowOpen` is both. Only `toggleObjectRow()` writes `UiPrefs.objectBarMoreOpen`. `anyOpen` counts the row, and `closeAll`, `hiddenByChrome` and the strip's toggle hide it without forgetting it.
  - A `DisposableEffect` calls `objectBarGone()` when the bar leaves (the objects deselected, a filter, the interface hidden): its row goes along, remembered, and the next selection shows it again. Deselecting swaps the bar out at once, so nothing stays open during its fade-out.
  - The row is a `Column` with the opaque background, no `Surface`, so a touch on its background between buttons reaches the canvas. Each button sits in a `Box` with `passCanvasGesture`, and the row fades to `StripState.ROW_FADE_ALPHA` once a canvas touch passes the slop, as the strip's row does.
  - **One More row at a time.** While the object bar's row shows, the strip's row is hidden, never forgotten; it shows again when the objects are deselected or that row closes. The strip's toggle opening its row hides the object bar's row until the next tool entry or the bar's next showing. The two rows never stack, and neither do their offsets.
  - The toggle and the row reuse `V18Tags.STRIP_TOGGLE` / `V18Tags.STRIP_MORE_ROW` and the Expanded / Collapsed state. The test helpers therefore never take "the last node tagged ...": the strip's helpers reach the options strip only, and `StripTestKit.openObjectRow` reaches this row (see "Testing and CI (v1.8)").
- **Long-press labels (`ui/common/LongPressLabel.kt`, frozen).** `Modifier.longPressLabel(label)`
  shows the label in a bubble (tag `V18Tags.LONG_PRESS_BUBBLE`) under the element for
  `LONG_PRESS_BUBBLE_MS` (1.5 s) on a long press, inside a `LongPressLabelHost` (`EditorScreen`
  wraps the editor in one). It never consumes a tap and never fires the click; a drag shows
  nothing; outside a host it does nothing.
- **Tests and helpers.** `StripLayoutTest` (pure budgets), `StripRegistryTest`,
  `MoreRowOverlayTest`, `StripInventoryTest` (the §3.9 inventory rules on
  every converted spec at 360 dp; `StripHandlesV17` records, per tool on a raster and a vector
  layer, the handles the v1.7 strip offers, and a converted strip must still offer each one on
  row 1, in the More row or inside an item), `StripNoLegacyTest` (every `ToolId` converted, with
  a toggle or a `NO_TOGGLE` reason), `UniqueLabelsV18`, and from the integration
  `StripRow1NarrowTest`, `StripRow1CheckTest`, `StripRow1NarrowFingerTest` and
  `MoreRowHistoryTapsTest` (above). `SmokeUi.find` / `click` and `testing/StripTestKit.openMoreRow`
  open the options strip's More row when a control is not on row 1, so v1.7 tests keep finding
  controls an area moves. They never open the object bar's row: a test opens it itself
  (`StripTestKit.openObjectRow`, or a tap on `StripLabels18.MORE_OBJECT`).

### Seams and new tools (F3)

- `ToolId.MAGNETIC_LASSO` and `ToolId.COLOR_SELECT` are appended after `PATHFINDER` (older
  ordinals unchanged); on the foundation their tools were stubs
  (`tools/select/magnetic/MagneticLassoTool.kt`, `tools/select/colorrange/ColorSelectTool.kt`:
  they open, draw nothing and commit nothing; area C built them, see "Selection tools" below),
  act where Magic wand acts, and fill cells [26] and [27] of the 30-cell tool menu.
- `BrushTool.beginPath(input, seed, draftBudget = 0f, maps = emptyList())`: a path stroke
  replicated through `DabMapping` like a symmetric brush stroke; empty maps are the v1.7 path.
- `SymmetryGuides.showsFor(id)` (the nine tools the rulers repeat) and `drawFor(canvas, t, doc,
  id)`, called by `EditorController.drawOverlays` for the active tool; on the foundation it draws
  the v1.7 guides of Brush, Eraser, Smudge and Blur.
- Stubs the areas filled (on the foundation): `CurveGenerators.build` (a valid Archimedean
  polyline) / `matches` (false), area E's; `CogGeometry.contours` (the interim zigzag, no hole),
  area F's; `FringeControl`, area A's.

### "Select several" in the X / Y pill; the strips' point undo removed (F4, gate 2)

- `PointEditor.selectSeveralAvailable` (Compose state; frozen with `PointEditor.kt`): whether
  "Select several" can be used now. By default `pointCount > 0` (Curve, Polyline and Path, no
  override); `ShapeTool` overrides it with `pointsMode && pointCount > 0`, `TransformTool` with
  `isMeshShown` (Free deform).
- `CoordinatePill` ends row 1 with a 40 dp icon cell (tag `V18Tags.PILL_SEVERAL`, description
  `PointLabels.SELECT_SEVERAL`, role Switch, state "On" / "Off", the accent fill while on, a
  long-press label) while the active tool is a `PointEditor` whose `selectSeveralAvailable` is
  true: `[✥][X][Y][#][🗑][sel]`, folded `[✥][🗑][sel]`. With no position the pill shows only
  `[sel]` (D5; no tool of the app gets there today). `pillRow1(hasPosition, folded, trash,
  several)` gives the cells (pure, in `ui/tools/CoordinatePill.kt`). A tap ends the canvas gesture
  and sets `selectSeveral`, as v1.7's chip did. The pill knows no concrete tool class: Shape's
  "turn it on" message (`PointLabels.SEVERAL_HINT`) is shown by `ShapeTool.selectSeveral`'s setter.
- The strips no longer offer "Undo last point" / "Redo point" (Curve, Polyline, Path, the curve
  lasso), "Undo last corner" / "Redo corner" (the polygon lasso), "Undo point edit" / "Redo point
  edit" (Shape's Points) or the "Select several" chips (Curve, Polyline, Path, Shape, Free
  deform). The top row's Undo / Redo reach the same `undoStep` / `redoStep` (V9: one step each).
  Shape's Points help line names the pill. `NoStripPointUndoTest` matches the six labels exactly
  on every strip state.
- Redo from nothing (gate 2 review): v1.7's strip "Redo point" / "Redo corner" also brought back
  the points of a NEW path, polygon or curve lasso taken back one by one down to nothing (no
  pending work left). `CurveTool.canRedoStep` is now `redoCount > 0` and `LassoTool.canRedoStep`
  the redo count of its kind (polygon corners, curve points; freehand false), pending work or
  not. `EditorController.redo` reaches `tool.redoStep()` with nothing pending when the document
  has nothing to redo (`!undoManager.canRedo && tool.canRedoStep`); `canRedoNow` and
  `HistoryLabels.redo` ("Redo: last point" / "last corner") follow the same rule. The document's
  own redo steps come first, as v1.7's top row had them; with something pending nothing changed.
  Another tool ends those steps: Curve's already ended with the tool (v1.7), and
  `LassoTool.onDeactivate` now drops a polygon's / curve's steps taken back to nothing, so the
  top row's Redo never brings back an old lasso after a tool switch.
- The gizmo's tie (gate 2 review): a point lying ON a handle of the group's box wins the finger
  when it is at most `PointGizmo.TIE_PX` (0.5 px) farther than the handle (Curve, Shape, Free
  deform). The corners are found from the box's centre, so a corner point of fractional
  coordinates (a Free deform vertex) sat a rounding error away and lost to the handle about half
  the time: the drag scaled the group instead of moving it.
- Tests click the cell through `testing/PillTestKit.kt` (frozen helper): `severalCell()`,
  `severalState()`, `toggleSeveral()`. Tests that rendered only a strip and clicked its point
  undo now call `HistoryLabels.performUndo` / `performRedo`, as the top row does.
  `PillSeveralFingerTest` (360 dp) does the whole flow by fingers in the five tools: the cell
  on, points tapped, one drag, one top-row Undo / Redo, the cell off. `PointRedoFromNothingTest`
  covers the redo from nothing, another tool ending it and the document's redo coming first.

## v1.9 foundation (gate 1)

The lead's part of v1.9, written at gate 1 (design 5.1). v1.9 answers the twenty requests R1-R20
of 2026-10-12. The lead built a foundation on branch `v19-foundation` (worktree
`.wt/v19-foundation`) from `main` at `600582d9` (v1.8.0); ten areas then build the items in
parallel from the tag `v19-gate1` and merge in the order A7a, A8, A5, A1, A9, A2, A4, A3, A6, A7b.
Each area writes its own section under "v1.9 areas" below, and the lead's Gate 2 is the last
section there ("Gate 2"); the lead wrote "Testing and CI (v1.9)" at Gate 2. The invariants are in
"Invariants (v1.9)" above.

### Steps, areas and ownership

The foundation, each step its own commits `v19F<n>: <what>`, each reviewed by a second engineer:
- **F0** test infrastructure only (no main source): the frozen v1.8 copies in `compat18/`, the
  `Qa19Ui` kit, `LabelsV19Test`, `Qa19CompatBackFromV18RobolectricTest` (one ignored test per row
  of the compatibility table), the CI shards and `CiShardFilterTest`, `ownership-check-v19.ps1`.
- **F1a** model, codecs, prefs, strip keys and labels (no behaviour change): the text fields,
  several texts per layer in the codec, `PaperSpec`, the v1.9 `AppSettings` keys, `UiPrefs`, the
  two tool-settings fields, `StripKeysV19`, `LabelsV19.kt` and `V19Tags`.
- **F1b** seams, stubs, extractions and region markers (no behaviour change; built in two halves,
  2.4.1-2.4.9 and 2.4.10-2.4.16).
- **F2** the 25 `TextCodec.decode(` / `encode(` sites read and write every text of a layer (no
  behaviour change for one-text layers).
- **F3** R3, the sticky More rows: the foundation's only behaviour change, with its forced test
  rewrites; the frame-times overlay and the "Show frame times" switch.
- **Gate 1:** this section and the blocks under "v1.9 areas" (`v19G1`), the P0 proxy baselines in
  `.wt/v19-p0.txt` ("P0 proxy baselines" below), the tag `v19-gate1`.

| Area | Items | Owns (main sources, besides its regions) |
|---|---|---|
| A7a Stability, first | R9: crash log, Magnetic lasso and Color select fixes, `RenderScaled` | `diagnostics/**`, `ui/diagnostics/**`, `tools/select/magnetic/**`, `tools/select/colorrange/**`, `engine/RenderScaled.kt`, `EditorSession.kt` outside `v19-A7b saveOnLeaving`; `app/proguard-rules.pro` |
| A8 Storage and new canvas | R15, R16, the CI release guards | `storage/ProjectRepository.kt`, `storage/CanvasCreate.kt`, `storage/ImageExport.kt`, `storage/backup/**`, `engine/PaperBackdrop.kt`, `ui/backup/**`, `ui/gallery/**` except `GalleryScreen.kt`, `ui/exchange/ExportOptionsSheet.kt`, `fonts/FontStore.kt`, `fonts/FontOpenIntent.kt`, `brush/BrushPresetStore.kt`, `ui/color/PaletteStore.kt`; `AndroidManifest.xml`, `res/xml/**`, `app/build.gradle.kts`, `.github/workflows/android.yml` |
| A5 Erase engine | R11 | `vector/draw/**`, `vector/pathfinder/**`, `vector/geom/ObjectIndex.kt`, `tools/vector/VectorRender.kt`, `ui/editor/chrome/BottomBar.kt`, `ui/brush/BrushStrip.kt`, `ui/vector/VectorEraserOptions.kt`, `ui/vector/VectorEraseChooser.kt`, `ui/vector/ErasePixelsDialog.kt` |
| A1 Curve tools | R1, R2, R18 (Polyline), R14 curves, R11 for Curve, Polyline, Path | `tools/vector/CurveTool.kt`, `CurveGroupMath.kt`, `CurveGeometry.kt`, `tools/points/Mixed.kt`, `ui/points/MixedNumberField.kt`, `ui/points/ThicknessRow.kt`, `ui/vector/Curve*.kt` |
| A9 Shape tools | R17, R18 (Shape), R14 shapes, R11 for Shape | `tools/vector/ShapeTool.kt`, `ShapeGeometry.kt`, `ui/vector/Shape*.kt` |
| A2 Filters and effects | R8, R10 | `filters/FilterSession.kt` outside `v19-A7b apply`, `filters/FilterSessionMath.kt`, `filters/art/ChromaticAberration.kt`, `CaBlend.kt`, `ArtFilters.kt`, `ArtMath.kt`, `filters/draw/DrawKit.kt`, `ui/filters/**`, `tools/mask/AdjustmentEdit.kt`, `ui/mask/AdjustmentSheet.kt`, `ui/common/NumberControls.kt` |
| A4 Layers | R19, R14 Duplicate | `engine/LayerMerge.kt`, `engine/LayerConvert.kt`, `vector/VectorLayerOps.kt`, `vector/select/**` except `ArrangeOrder.kt`, `ui/layers/**`, `ui/vector/VectorObjectBar.kt`, `ui/editor/SelectionBar.kt`, `tools/select/SelectionEdits.kt`, `SelectionDuplicate.kt` |
| A3 Text | R4-R7, R12, R14 text | `tools/text/**` except `TextModel.kt`, `TextCodec.kt`, `TextPathSpec.kt`; `ui/placement/Text*.kt`, `Frame*.kt`, `ui/textframes/**`, `exchange/ImportLayers.kt`, `exchange/VectorImport.kt`, `exchange/export/ExportSceneBuilder.kt` |
| A6 Render performance | R9 drawing, R11 raster erase, R13 | `brush/**` except `BrushPresetStore.kt` and `BrushLibrary.kt`, `tools/vector/BrushStroking.kt`, `engine/DisplayTiles.kt`, `Compositor.kt`, `EditInvalidation.kt`, `PerfTrace.kt`, `engine/live/**`, `ui/editor/CanvasView.kt`, `DrawingPreviewSetting.kt`, `tools/mask/MaskTool.kt`, `MaskPreview.kt`, `masks/MaskBrushRaster.kt`, `MaskActions.kt`, `MaskSpecRenderer.kt`, `MaskRefine.kt`, `ui/mask/FastAdjustPreviewToggle.kt` |
| A7b Stability, second | R9: governor policy, banded save, filter tiling | `engine/GovernorPolicies.kt`, `GovernorPolicyV19.kt`, `storage/LayerCodec.kt`, `LayerEntries.kt`, `filters/FilterTiling.kt`, `vector/VectorLayers.kt`, `array/ArrayRenders.kt`, `tools/transform/LayerBoundsCache.kt` |

**Frozen for the release** (only F steps and `v19-lead` commits change them): `AppSettings.kt`,
`UiPrefs.kt`, `BrushworkApp.kt`, `MainActivity.kt`, `model/**`, `storage/ProjectFile.kt`,
`storage/CanvasLimits.kt`, `exchange/export/Payload.kt`, `engine/MemoryGovernor.kt`,
`engine/LayerStructure.kt`, `engine/Undo.kt`, `tools/Tool.kt`, `tools/ToolHistory.kt`,
`tools/InToolHistoryOwner.kt`, `tools/ErasableTool.kt`, `tools/points/PointBevel.kt`,
`tools/points/PointEditor.kt`, `ui/points/BevelPopup.kt`, `tools/text/TextModel.kt`,
`TextCodec.kt`, `TextPathSpec.kt`, `tools/vector/ShapeObject.kt`, `vector/VectorModel.kt`,
`vector/select/ArrangeOrder.kt`, `ui/common/Labels*.kt`, `ui/tools/strip/**`,
`ui/placement/StripParts.kt`, `ui/editor/EditorScreen.kt`, `EditorSettings.kt`,
`EditorChrome.kt`, `EditorHistoryTaps.kt`, `HistoryLabels.kt`, `ui/gallery/GalleryScreen.kt`,
`brush/BrushLibrary.kt`, the three `ObjectActions` signatures (`duplicate(c: EditorController):
Boolean`, `duplicateOffset(c: EditorController): Float`, `arrange(c: EditorController, how:
ObjectEdits.Arrange): Boolean`; A4 owns their bodies), and `EditorController.kt` outside its
regions. `ui/editor/EditorActions.kt` and `ui/exchange/ExchangeUi.kt` stay the lead's (their
paper sites are complete). A main file that matches no area and is not frozen is the lead's. An
area that needs a frozen change writes `LeadRequest: <what and why>` in its progress file and
stops that step; the lead lands it on `main` as `v19-lead: <what>` and every open area rebases.

### Model and codecs (F1a, F2)

Every new persisted field is `@EncodeDefault(EncodeDefault.Mode.NEVER)` with a default that means
"as v1.8", the last field of its class, and no codec version moved (I13). A document without the
new data encodes byte-identically to v1.8 (`CodecV19NeverTest`, `CodecV18GoldenTest`,
`I13GoldensRobolectricTest`), and the frozen v1.8 readers of `compat18/` read every new construct
(`Compat18KitTest`). After F1a, rule K refuses any new `@Serializable`, `@EncodeDefault` or
`@SerialName`, or a changed `VERSION =`, on an area branch (except A8's own `storage/backup/**`
formats, which are not project data): new persisted data comes only from the lead (D33).

Text (`tools/text/TextModel.kt`, `TextPathSpec.kt`, `TextCodec.kt`):
- `enum class TextJustify { OFF, ON, ALL }`, `enum class TextKerningMode { OPTICAL,
  METRICS_LATIN }` (null means Auto), `enum class TextListType { NONE, BULLETS, NUMBERS }`,
  `enum class TextNumbering(val sample: String)` (DECIMAL_DOT "1." to CIRCLED "①", eight entries).
- `TextListSpec` (type, bullet "•", numbering, start 1, markerScale 1, markerWeight 0, gapEm 0.5,
  markerFont, markerFontId, markerFontName; `isOn`, `sameFontAsText`, `sanitized()` with the
  limits `MAX_START` 9999, marker scale 0.5..2, weight 0..1, gap 0..3 em). `sanitized()` keeps the
  bullet's first grapheme ("•" when blank) and a marker font id only when `FontIds.isValid`.
- `TextSpec.justify`, `kerningMode` and `list`, after `fontKerning`; `TextItem.id: Int = 0`
  (`sanitized()` makes a negative id 0 and clears `list` on vertical or path text; `justify` is
  kept there and ignored).
- `TextSpec.sanitized()` does NOT force `fontKerning = true` when a kerning mode is set (the
  design and the compatibility table said it would): "Off" (`fontKerning = false`) keeps the
  stored mode, and v1.8 reads a set mode as Auto while kerning is on and as no kerning while it is
  off (F1a deviation, agreed in review).
- `TextPathSpec.nodes: VSpline?` (at most `MAX_NODES` 200) and `withNodeFallback()`: pure and
  idempotent, it writes the v1.8 CURVE or LINE fields from the nodes (a closed node guide becomes
  an open CURVE whose ends coincide), keeps the nodes, and returns the same instance when nothing
  changes; a straight text (type NONE) keeps NONE and its nodes.
- `TextLayerData.more: List<TextItem>`: the texts after the first. `TextCodec.MAX_ITEMS` 64;
  `encodeAll(items)` requires 1..64 items, writes one item byte-identically to `encode`, and keeps
  ids or assigns the next free ones; `decodeAll(data)` returns every text sanitized, at most 64,
  and an empty list for null, blank or unreadable data. `encode` / `decode` are unchanged and
  `VERSION` stays 5.
- F2: every reader outside `TextCodec.kt` handles several texts (I24, rule T). Arrays, transforms,
  data lift, import relinking and SVG/PDF export cover every text; the wrap re-flow re-flows every
  wrapping text; frames and threads read the first framed text (frames stay alone, I24).
  `TextTool` edits the bottom text and keeps the others (`commitLayerEdit` stores all of them);
  emptying the bottom text of several, or the pill's trash on it, removes only that text in one
  step, and a one-text layer behaves exactly as in v1.8. `TextExport.outlines(item)` is new (A3's
  file). `TextFrameTool.join` refuses a layer of several texts.

Paper (`model/PaperSpec.kt`): `data class PaperSpec(val color: Int = white)`;
`Document.paper: PaperSpec? = null` (in memory), `ProjectFileDto.paper` and
`BrushworkPayload.paper` (NEVER), saved and loaded by `ProjectRepository` and carried by the
payload; `ExportOptions.includePaper: Boolean = true` (not persisted).

Tool settings: `CurveSettings.ownLayer` and `ShapeSettings.newLayerEachTime` (both `Boolean =
false`, NEVER, last; `ShapeSettings.withBehaviourOf` copies `newLayerEachTime`). A1 must carry
`ownLayer` through `CurveTool.update`, which saves only unit and nudge for a reopened path.

What v1.8 does with what v1.9 writes is the table of design 5.2;
`qa19/Qa19CompatBackFromV18RobolectricTest` has one test per row (rows 10 and 12 one per owner;
row 8, the wrap re-flow, cannot occur), each reading a fixture of
`app/src/test/resources/qa19/compat/<row>/` with the `compat18/` readers. At gate 1 only row 14
ran; since Gate 2 every row runs (23 tests, each with its committed fixture), and the only
ignored test of the class is the generator `generateFixtures`:

| Row | Construct | v1.8 opens / edits and saves | Owner | Gate 1 | Gate 2 |
|---|---|---|---|---|---|
| 1-5 | `TextSpec.justify`, `kerningMode`, `list`; `TextPathSpec.nodes`; `TextLayerData.more` and `TextItem.id` | the saved pixels; an edit drops the field (and, for `more`, shows only the first text) | A3 | ignored (row 2's ignore text still quotes the forcing rule above) | runs, fixtures `text-justify/`, `text-kerning/`, `text-list/`, `text-path-nodes/`, `text-several/` |
| 6, 7 | v1.8 Transform, array source and data lift of a layer of several texts | the pixels move with every text; the data keeps the first text | A3 | ignored | runs, `text-transform/`, `text-array-lift/` |
| 9 | `ProjectFileDto.paper`, `BrushworkPayload.paper` | one transparent layer; a re-save drops the paper | A8 | ignored | runs, `paper/` |
| 10 | joined shapes, mixed curves, merged layers, duplicates, cut pieces, bevelled points | existing kinds: fully editable | A9, A1, A4, A4, A5, A1 / A9 | ignored | runs (seven tests), `shapes-joined/`, `curves-mixed/`, `layers-merged/`, `duplicates/`, `cut-pieces/`, `bevel-curves/`, `bevel-shapes/`; Gate 2's in-layer Duplicate (P3) writes the same `duplicates/` pixels, so no fixture was regenerated |
| 11 | one-layer new canvases, "Picture" alone | existing format | A8 | ignored | runs, `new-canvas/` |
| 12 | text arrange order, R17 edits | existing fields | A3, A9 | ignored | runs, `text-arrange/`, `shape-r17/` |
| 13 | banded save | byte-identical files | A7b (the lead un-ignores it) | ignored | runs, `banded/` (the lead, after A7b) |
| 14 | the v1.9 prefs, `UiPrefs`, the tool-settings fields | not in the project; v1.8 ignores unknown keys | lead (F1a) | runs, fixture `qa19/compat/prefs/` | runs, `prefs/` (Gate 2's key "newCanvas.customColor" is not in `V19_SETTINGS_KEYS`: a preference v1.8 ignores like the others) |
| 15, 16 | crash reports, `backup-state.json`, the backup folder | outside the projects | A7a (the lead un-ignores it), A8 | ignored | runs, `crash/` (the lead, after A7a), `backup-state/`, `backup-folder/` |

An area writes its row's generator (`generators`), runs the ignored `generateFixtures` once,
un-ignores its row and adds the row's checks after `v18Reads(row, newKeys)`. `Compat18KitTest`
already checks at JVM level that a layer of several texts and a node path read in v1.8, that a
project and a payload lose their paper there, and that the tool settings lose their v1.9 options.

### UiPrefs versus AppSettings (I26)

- `AppSettings` ("brushwork_settings") is journaled: while a UI mark is open every write is
  recorded, so a history tap over the UI takes it back (`rollbackJournal`). v1.9 adds nine keys,
  each default the v1.8 behaviour: "drawPreview" (`DRAW_PREVIEW_FASTER`; or `DRAW_PREVIEW_EXACT`),
  "showFrameTimes" (false), "newCanvas.background" (`NEW_CANVAS_WHITE`; or TRANSPARENT, CUSTOM,
  PAPER), "text.newLayerEachTime" (false), "curve.bevel.amount" and "shape.bevel.amount" (0.15,
  kept in 0.01..0.5, a non-finite value becomes 0.15), "curve.bevel.segments" and
  "shape.bevel.segments" (1, kept in 1..8), and since Gate 2 "newCanvas.customColor" (an Int,
  default 0xFFF3E7D3, the New canvas dialog's former pale cream; written at Create together with
  "newCanvas.background", so the settings backup carries it).
- `UiPrefs` ("brushwork_ui") is a plain `SharedPreferences` file, not journaled, not in the
  project and not in the backup's `settings.json`: "strip.openRows" (the `ToolId` names whose More
  row is open; unknown names are kept) and "objectBar.moreOpen". Only the explicit toggle taps
  write it (`StripState.toggle`, `toggleObjectRow`); hiding a row never does. A history tap
  therefore never opens or closes a row. Tests start each editor with an empty `UiPrefs`
  (`Smoke.controller` clears the file).
- A new key or label is the lead's (`LeadRequest`); an area never adds a field to either class.

### Seams and who fills them (F1b)

Every seam keeps v1.8's behaviour on `main` at gate 1. "Fills" is the area that gives it its v1.9
behaviour; everyone else calls it. "At Gate 2" is the seam on `main` after the ten areas and Gate 2
(the details are in each area's block under "v1.9 areas" and in "Gate 2" there).

| Seam | File | Owner | At gate 1 | Fills | At Gate 2 |
|---|---|---|---|---| --- |
| `ToolHistory<S>(initial, cap = 100)`: `current`, `canUndo`, `canRedo`, `depth`, `record`, `undo`, `redo`, `reset` | `tools/ToolHistory.kt` | frozen | complete (recording the current state is not a step) | used by A2 (`FilterValues`), A3 (path nodes) | `FilterSession.history` (A2) and `TextPathNodeEditor` (A3) |
| `InToolHistoryOwner` and `EditorController.inToolHistory` (region `v19-lead history`) | `tools/InToolHistoryOwner.kt` | frozen | null: v1.8. Undo and Redo ask the owner first; `HistoryLabels` shows "Undo: " plus its label, and `performUndo` / `performRedo` skip `historyBlocked` (the open text editor) while it can step; `canRedoNow` (`EditorChrome.kt`) and the Undo button (`EditorScreen.kt`) read it | A2, A3 set an owner while a session is open | owners: `FilterSession` (A2), `TextPathNodeEditor` (A3) and `EditorController.deferredWaitGuard` while deferred work is pending (since Gate 2 a pending region cut counts too, M3) |
| `MemoryGovernor` (`register`, `onTrimMemory`, `admit`, `entries`, `policy`), `MemoryPressure`, `Trimmable`, `GovernorPolicy` | `engine/MemoryGovernor.kt` | frozen | `DefaultPolicy` trims nothing and admits everything; `BrushworkApp` forwards `onTrimMemory` and `onLowMemory` | A7b's policy, A6's cache registrations | `GovernorPolicyV19` decides; registered: its own entries, A6's caches (`engine/live/WeakRegistration`), `OpticalKerning` (A3), the gallery's `ThumbnailCache` (A8) |
| `GovernorPolicies.current()` | `engine/GovernorPolicies.kt` | A7b | `MemoryGovernor.DefaultPolicy` | A7b (`GovernorPolicyV19`) | `GovernorPolicyV19` |
| `UndoManager.trimOldest(keepAtLeast = 1, until)` | `engine/Undo.kt` | frozen | complete: drops the oldest steps, keeps redo, does nothing inside an edit scope | called by A7b's policy | called by `GovernorPolicyV19` |
| `MemoryGovernor.admit` sites | `FilterSession.apply` (in `v19-A7b apply`), `LayerMerge.mergeDownNow`, `EditorController.mergeFolder` (in `v19-A4 mergeFolder`), `ImageExport` (JPEG copy), `EditorActions.admitFlatten` | the files' owners | always admitted; a refusal shows `StabilityLabels19.NO_MEMORY_FOR` (the JPEG copy throws an `IOException`) | A7b's policy decides; `DiagnosticsSeamsTest` pins one admit per file | the same five sites; `GovernorPolicyV19` decides |
| `PerfTrace` (`enabled`, `span`, `record`, `stats`, `lastMs`, `clear`; eight names) | `engine/PerfTrace.kt` | A6 | spans: FRAME (`CanvasView.onDraw`), LIFT (`BrushTool`), ERASE_REBUILD (`VectorEraser`), MASK_FRAME (`MaskTool` drags), FILTER_PREVIEW (`FilterSession`), SAVE_LAYER (`LayerCodec`), OPEN (`EditorSession`); CUT has no site | A6; A5 adds the CUT span | every span has a site: CUT is `EraseLanding`'s region cut (A5) |
| `EditInvalidation.afterCommit(changed, displayCurrent)`; `commitEdit(..., displayCurrent = false)` (region `v19-A6 commitEdit`) | `engine/EditInvalidation.kt` | A6 | returns `changed`: v1.8 | A6, with the I25 parity test | null for a lift whose preview is its commit (`displayCurrent = true`, I25); `tilesAfterCommit` redraws a lift's undo tiles (A6) |
| `LayerMerge` (`mergeDown`, `siblingBelow`, `mergeDownNow`, `mergeDownInto`, `kindOf`, `rasterReason`), `MergeKind` | `engine/LayerMerge.kt` | A4 | v1.8's merge, extracted from the controller; `kindOf` by `VectorLayerOps.mergesAsObjects`; `rasterReason` null | A4 (R19) | A4's merges (R19; block A4) |
| `LayerConvert` (`shapeToVector`, `toVectorAction(c, layer, name, label)`, internal `convert`) | `engine/LayerConvert.kt` | A4 | complete, byte-identical to v1.8's `convertToVectorLayer` | called by A4, A5, A9 | unchanged |
| `CanvasCreate.seed(spec)`, `seedFromImage(picture, name)`, `CanvasStart { FILL, TRANSPARENT, PAPER }`; `NewCanvasSpec.start: CanvasStart? = null` | `storage/CanvasCreate.kt`; `storage/ProjectRepository.kt` | A8 | the v1.8 seeds ("Background" and "Layer 1"; "Picture" and "Layer 1"), descriptors only | A8 (one layer, R15) | one layer (R15, A8) |
| `PaperBackdrop.draw(canvas, doc, dst)`, `sampleColor(doc)` | `engine/PaperBackdrop.kt` | A8 | draws `doc.paper`; nothing without one | A8 | A8; `CrossPaperRoundTripTest` (Gate 2) follows a paper document through its sites |
| `PointBevel.bevel(...)`, `BevelPoint`, `Result`; `BevelPopup(amount, segments, onAmount, onSegments, onDone)` | `tools/points/PointBevel.kt`, `ui/points/BevelPopup.kt` | frozen | complete | called by A1 (Polyline), A9 (Shape) | called by `CurveTool` (Polyline, A1) and `ShapeTool` (A9); each popup session is one exact step (Gate 2) |
| `ArrangeOrder.step(bounds, selected, dir)` | `vector/select/ArrangeOrder.kt` | frozen | complete: each selected item moves past the first overlapping unselected item in its direction, or one step; null when nothing moves | called by A4 (`ObjectActions.arrange`), A3, A9 | called by `ObjectActions.arrange` (A4; Shape's More row goes through it) and `TextTool` (A3) |
| `ErasableTool` (`eraseOn`, `setErase`, `eraseLabel`, `erasingLabel`) | `tools/ErasableTool.kt` | frozen | no tool implements it | A1, A9 implement it; A5's slot 1 reads it | implemented by `CurveTool` (Curve, Polyline, Path; A1) and `ShapeTool` (A9) |
| `EraseRouting.route`, `EraseRoute`, `EraseSource` | `vector/draw/EraseRouting.kt` | A5 | the v1.8 routing (I20) | A5 (R11 (b)) | the I20 table (block A5); the Eraser brush's path stroke keeps v1.8's route (product P4, deferred past 1.9) |
| `EraseCut.cut`, `strokeRegion`, `Outcome`, `MAX_PIECES` 256, `MAX_TARGETS` 500 | `vector/draw/EraseCut.kt` | A5 | `Unchanged` | A5 | A5; since Gate 2 `cutAll(targets, eraser, active)` stops at its next object once cancelled (M3) |
| `ToolErase.begin`, `ToolEraseSession`, `ToolEraseResult` | `vector/draw/ToolErase.kt` | A5 | `begin` returns null | A5; called by A1, A9 | A5; `CurveTool` and `ShapeTool` erase through it |
| `VectorEraseChooser.item`, `pixelsItem`, `modeCell` | `ui/vector/VectorEraseChooser.kt` | A5 | null (no strip item) | A5 | A5's items and the erase-mode cell |
| `VectorPaintSpec.erase` (DST_OUT); `StrokeInfo.convertFirst` (set by `BrushTool` when the route is `CONVERT_THEN_OBJECTS`); `VectorEraserRecorder(..., convertFirst)` | `tools/vector/VectorRender.kt`; `brush/StrokeHooks.kt`; `vector/draw/VectorEraser.kt` | A5; A6; A5 | false: v1.8 (the gate-1 routing never returns `CONVERT_THEN_OBJECTS`, and the recorder ignores it) | A5 | A5: a legacy shape layer converts and erases in one step (`EraseLanding`) |
| `Breadcrumbs` (32 events), `CrashLog`, `CrashReport`, `ErrorReporter.handler` | `diagnostics/` | A7a | breadcrumbs recorded; `CrashLog` logs only and reports nothing; the handler on every `CoroutineScope(` and `rememberCoroutineScope` forwards to the thread's handler | A7a | A7a's crash log and reports; since Gate 2 a composable's scope is `rememberSupervisedScope()` (P2: a supervisor under the handler) |
| `BackupService` (`status`, `start`, `onSaved`, `onDeleted`), `BackupStatus` | `storage/backup/BackupService.kt` | A8 | `Ok(0, "", 0)`, no-ops | A8 | A8's backup; since Gate 2 `start` does no IO on the calling thread (M4a) |
| `GalleryNotice`, `pickNotice(crash, backup, notSetDismissedAtMillis, nowMillis)`, `GalleryNotices`; `BackupBanner`, `GalleryBackupMenuItems`, `galleryBackupMenuShown`, `EmptyGalleryRestore` | `ui/gallery/` | A8 | `pickNotice` complete; the slots are empty and the menu hidden | A8 (A7a's crash card in the notice) | A8, with A7a's crash card |
| `CrashCard`, `DiagnosticsSection`; `BackupSection`; `DrawingPreviewSetting` | `ui/diagnostics/`, `ui/backup/`, `ui/editor/` | A7a, A8, A6 | empty, except `DiagnosticsSection`'s frame-times switch (F3) | A7a, A8, A6 | filled by A7a, A8 and A6 |
| `TextItemOps.available`, `duplicateItems(c, layer, area, ids)` | `tools/text/TextItemOps.kt` | A3 | false, null | A3; A4 calls it and hides Duplicate on text layers while it is unavailable | `available` true; `duplicateItems` copies the chosen texts (A3), so Duplicate shows on text layers |
| `StripKeysV19` (eleven keys), `LabelsV19.kt`, `V19Tags` | `ui/tools/strip/`, `ui/common/` | frozen | declared | every area uses them; new ones by `LeadRequest` | declared; the strip specs rank their v1.9 items by them |

To make the extractions byte-identical, `EditorController` widened a few members to `internal`:
`settleQueuedVectorWork()`, the inline `withToolPaused`, `storeData(..., record = ::pushUndo)`,
the new `applyLayerProps(..., record)` and the companion `shapeLayerVector(layer)` (the
documented `ShapeCodec` reader of `CopiesReadersAuditTest`); `addVectorLayer` gained `baseName`.

**Paper and its sites (I21).** `PaperBackdrop` draws the paper under the layers at these sites
only: `CanvasView` (after the checker, under the view matrix); `Compositor.renderFlattened(background,
withPaper)` and `renderThumbnail` (the paper is the background colour, with v1.8's matte); the
editor's export and share and the gallery's `renderFlattened(id)` (`withPaper = true`);
`SelectionSupport.PixelSnapshot.take` on the canvas; smart select (`paper ?: white`); the
Eyedropper (a fully transparent canvas sample falls back to `sampleColor`); `ExportSceneBuilder`
(the SVG/PDF background while `includePaper`, and `BrushworkPayload.paper`); `ExchangeUi.restore`
(a new artwork takes the payload's paper). Every site is v1.8 while `doc.paper` is null.

### Region markers and the ownership rules

`EditorController.kt`, `filters/FilterSession.kt`, `EditorSession.kt` and this file are shared by
region. In the Kotlin files a region is `// region v19-<owner> <name>` ... `// endregion`;
`RegionMarkersTest` checks that each exists once, is closed and holds its members by name, so
line shifts after merges do not matter. Marker lines never change.

| File | Region | Members |
|---|---|---|
| `EditorController.kt` | `v19-lead history` (frozen) | `inToolHistory`, `undo`, `redo` |
| | `v19-A6 invalidate`, `v19-A6 commitEdit` | `invalidateDoc`; `commitEdit` |
| | `v19-A7b layerCap` | `maxHeap`, `undoManager`, `maxLayers`, `effectiveLayerCount`, `layerLimitMessage` (and `canAddLayer`, which lies inside) |
| | `v19-A7b settle`, `v19-A7b addMask`, `v19-A7b landSavedSelections` | `settleVectorWork`, `settleQueuedVectorWork`; `addMask`; `landSavedSelections`, `landSavedSelection` |
| | `v19-A3 rasterizeMessage` | `rasterizeMessage` |
| | `v19-A4 paste`, `mergeDown`, `mergeFolder`, `layerFromFolder`, `convertToVectorLayer` | `paste`; `layerMerge` and the `mergeDown` delegate; `mergeFolder`; `layerFromFolder`; `convertToVectorLayer` |
| | `v19-A7a savedSelectionWork` | `startSavedSelectionWork` |
| `filters/FilterSession.kt` | `v19-A7b apply` (A2 owns the rest) | `apply`, `cancelApply`, `commitResult` |
| `EditorSession.kt` | `v19-A7b saveOnLeaving` (A7a owns the rest) | `saveOnLeaving` |

Everything else in `EditorController.kt` is the lead's. In this file each area's block is
`<!-- v19-<x> -->` ... `<!-- /v19-<x> -->` under "v1.9 areas"; an area writes only inside its own,
in its last plan step before its cut steps.

`.wt/_tools/ownership-check-v19.ps1 -Area <A7a|A8|A5|A1|A9|A2|A4|A3|A6|A7b> -Worktree <wt>`
checks a branch against these rules (`-Base` defaults to `main`; F steps and gate commits pass
the commit they started from; exit 0 OK, 1 FAIL, 2 usage or git error):
- **O (owner):** every changed path belongs to the running area (the tables above); a frozen,
  foreign or unowned path fails. A changed existing test is checked in this order: a frozen kit
  (`$FrozenTests`) fails; the area's `$ExtraTestFiles` pass; a `<Name>Test.kt`,
  `<Name>RobolectricTest.kt` or `<Name>UiTest.kt` beside an existing main `<Name>.kt` passes only
  for that file's owner; then v1.8's folder rule (a folder of the area's main files); else it
  fails. New tests and new test resources may go anywhere outside the frozen kits; an existing
  test resource and `app/src/test/test-count.txt` are the lead's. Repository files outside
  `app/src/main/java` are the lead's unless `$OwnedFiles` names them (A7a, A8 above).
- **R (regions):** every changed line of the four shared files lies strictly inside one of the
  area's regions; the owner of `FilterSession.kt` (A2) or `EditorSession.kt` (A7a) may change any
  line outside the other areas' regions.
- **S:** no added main line outside `engine/` holds `doc.layers.add`, `insertLayer(` or
  `RemoveLayerAction()`. **L:** no added main line outside `ui/common/` holds a label of
  `LabelsV18.kt` or `LabelsV19.kt` as a literal. **T:** no `TextCodec.decode(` / `encode(` outside
  `TextCodec.kt`. **K:** no new persisted annotation or `VERSION` change (above).
- **C (commits):** area subjects are `v19<x>: k/n <text>` (k from 1, rising by at most one; n the
  same in every subject); F steps use `v19F<n>: `, the gate work `v19G<n>: `, the lead on `main`
  `v19-lead: `.
- **`$Sequential`:** A7b's region of `FilterSession.kt` passes only on a branch that contains A2's
  merge, and its region of `EditorSession.kt` only after A7a's (`.wt/_tools/v19-merges.json`).

### Sticky More rows (R3, F3)

The rule changed in v1.9: the options strip's More row and the object bar's extra row close only
by their own toggle. A canvas touch reaches the canvas through an open row and never closes it;
chrome (the layers and colour panels, the tool menu, a sheet, "hide interface") hides a row
without forgetting it; each tool remembers its row in `UiPrefs` across tool switches and
relaunches; `StripContext.closeMore` (`{}`) and `ClosesMoreRow()` are no-ops (their call sites
stay); explicit fits
leave room for the open row (`ChromeLayout.fitInsetTop(statusDp, moreRowDp)`), and opening or
closing a row never moves the canvas. The top-right "More options" menu, dropdowns, dialogs and
anchored popups still close on an outside tap. The details (the memory rules, the canvas-gesture
pass-through of `Modifier.passCanvasGesture`, the 25 % fade, the joined finger's last lift) are in
"The options strip and its More row" above ("State, memory and canvas touches"); the frame-times
overlay of F3 is under "Frame times" below. `ui/tools/strip/**` and `EditorScreen.kt` are frozen
after F3, and only A6 changes `CanvasView.kt`.

### CI shards and the test-count floor (F0)

- `app/build.gradle.kts` (`testOptions.unitTests.all`): `-PtestShard=<i> -PtestShards=<n>` (both
  or neither, `0 <= i < n`) runs only the classes with `ciShardOf(className, n) == i`, where
  `ciShardOf` (top of the file) is `(className.substringBefore('$').hashCode() and 0x7fffffff) %
  n`, so nested classes stay with their outer class. `testShard` and `testShards` are task inputs,
  so each shard has its own build-cache key (LA3). `-PtimingOnly` runs the `Timing` category alone
  in one JVM; `-PsoakOnly` runs `SoakHarnessTest` alone (`.github/workflows/soak.yml`, nightly and
  on dispatch). On CI (`CI` set) the other runs leave out the `Timing` category and use two JVMs.
  `CiShardFilterTest` checks that every class falls in exactly one shard.
- `.github/workflows/android.yml`: `unit` (a matrix of shards 0, 1 and 2 with `-PtestShards=3`,
  45 min), `timing` (30 min), `test-count` (10 min; it sums the `tests` attributes of every shard's
  JUnit XML and fails below the floor in `app/src/test/test-count.txt`, when a shard uploaded no
  XML or log, or when a log shows `testDebugUnitTest NO-SOURCE`), and the release APK (`needs` all
  three; the `v*` tag publishes it). CI ran two shards until Gate 2, when a two-shard test step
  took 27.5 min and passed the 26-minute fallback of design 5.5; `CiShardFilterTest` checks the
  balance for both two and three shards.
- The floor is the non-Timing count of all shards: 3970 at v1.8, 4024 after F0, 4072 after F1a,
  4154 after F1b, 4169 after F2 and 4177 after F3 (shard 0 2192 + shard 1 1985), 4764 after
  the ten areas (three shards: 1426 + 1745 + 1593) and 4787 after Gate 2; the Timing run has 325
  at F3 and 360 after Gate 2.
  "Testing and CI (v1.9)" below has the jobs, the local runs and the
  known flakes. Only the lead changes the file (rule O): it raises the floor at each merge to the
  merged branch's count, and lowers it only in the commit that deletes tests on purpose. The file
  has no trailing newline.
- Test kits of the foundation: `qa19/Qa19Ui.kt` (frozen: `uiTest` sections, the editor and
  composable harnesses, `withFontScale`, `assertNoTextOverflow`, the More-row helpers, the 392 and
  360 dp qualifiers), `compat18/**` (frozen v1.8 readers; v1.8 strokes use `PackedPointsV18`),
  `ui/common/LabelsV19Test.kt` (frozen), `qa19/Qa19StickyRows.kt`, and the compatibility test
  above. The older shared kits stay frozen too: `smoke/SmokeUi.kt`, `testing/StripTestKit.kt`,
  `testing/PerfBudget.kt`, `testing/PillTestKit.kt`, `qa16/QaCurves.kt`, `qa16/Qa16Ui.kt`,
  `ui/editor/HistoryTapFingers.kt` and `ui/editor/chrome/UniqueLabelsV17.kt`. One UI `@Test` per Robolectric UI class (a second one gets no recomposition), and each
  area keeps within its UI-class cap (design 5.5, `.wt/_tools/test-times-v19.ps1`).

### P0 proxy baselines (gate 1, LA1)

The lead has no T606 (LA1), so the P0 numbers of design 2.7 are JVM proxies: `qa19/P0ProxyBench.kt`
(test sources, category `Timing`) measures them with Robolectric NATIVE graphics on the lead's PC,
and the lead records them in `.wt/v19-p0.txt`.
- It runs only with `-Pbench`: `.wt/_tools/gbuild.ps1 testDebugUnitTest -Pbench` runs this class
  alone, in one JVM, with `bw.testSet` = "bench" (add `--tests
  "com.brushwork.paint.qa19.P0ProxyBench.<method>"` for one canvas). Without `-Pbench` every test
  is skipped by an assumption; with `CI` set the class is excluded from every test task, so it is
  in no shard and no Timing run and is not counted in `test-count.txt`.
- It drives the app's own code: `Smoke.controller` with a laid-out `CanvasView` of 1080 x 2408 px
  (xxhdpi; the editor's fit area is 1846 px high), touches through `EditorController.pointerDown`,
  `pointerMove`, `pointerUp` and `pointerCancel`, and the `PerfTrace` spans those paths record:
  FRAME (`CanvasView.onDraw`, drawn into a bitmap after every second event, a 120 Hz digitizer at
  60 Hz), LIFT, ERASE_REBUILD and MASK_FRAME. A value is the median of 15 runs after 3 warm-ups; a
  run is one stroke or drag, and its value is the median of its samples (a lift: its one sample).
- Canvases: PHONE 1080 x 2408 with 20 layers, A4 2480 x 3508 with 5, PRINT 3000 x 4000 with 3,
  every layer covered with translucent shapes; strokes go on a middle layer. The app's fit is 0.69
  on PHONE, 0.39 on A4 and 0.32 on PRINT (width-limited; 2.7 assumed 0.47 and 0.41, which are
  measured too); the `zoom` column is the zoom measured.
- Rows: `brush.lift` and `frame` (Hard round, 40 px, a 30 cm stroke: 4134 px at 350 dpi as a
  serpentine over the visible canvas, events 12 px apart; each run is undone, untimed, so every
  run paints the same layer) at fit and at 2.0; `frame.erase` and `erase.lift` (the raster
  Eraser, Soft eraser 90 px) on PHONE and PRINT at fit; `erase.rebuild.<n>` (the vector Eraser in
  Object mode, 40 px, over n = 500 and 2000 strokes on a vector layer of PHONE: every move, and as
  `.hit` the moves that dim new objects) with `frame.erase.vector.<n>` (not a 2.7 row: the frame
  with the dimmed preview, for A5); `mask.frame.<kind>` (30 moves of a Linear, Radial and Brush
  mask drag on an adjustment layer, A4 and PRINT at fit; no frame is drawn, and the first move of
  a drag is left out). Every erase and mask drag is cancelled, so the content stays the same.
- `.wt/v19-p0.txt`: a header (date, commit, the PC's CPU, the T606 factor 6x, the definitions),
  then one line per measurement, `name value unit canvas zoom jvm`. The thresholds of 2.7 are
  compared with `value x 6`. A row with no JVM proxy (PSS after opening and after 50 strokes,
  cold start) has the value `n/a` and the reason. The one CI row, `ci.timing.job` (5.5: at most
  12 min with the compile), is the "Timing tests" job of F0's push and ends in `ci`, not `jvm`.
- A6's checkpoint (2.9): after P1 and P2, A6 runs `phoneBrushAndRasterEraser` and
  `printBrushAndRasterEraser` on its branch and appends their `brush.lift` and `frame.erase` lines
  under a line `# a6-after-p2`; the lead signs off before A6's steps 8-10. The bench prints each
  line as `[p0] ...` and every run as `[p0-runs] ...` (the JUnit XML's `system-out`).

## v1.9 areas

One block per area, in merge order. Each area writes its section only inside its own block
(rule R), in its last plan step before its cut steps; the heading above each block stays.

### Crash log and diagnostics (A7a)

<!-- v19-A7a -->

R9 ("the app seems to be crashing often"; the user's crash came "while using the magnetic lasso
and the color selection tool"): a crash log on the phone that the user can copy and share, one
policy for errors no job caught, and the two tools' jobs guarded and moved off the main thread.

- **Files.** `diagnostics/`: `CrashLog` (the log, the uncaught handler, the "diagnostics"
  preferences), `ReportText` (the report's text and the privacy rules; pure JVM), `ExitReasons`
  (`ApplicationExitInfo`), `FreezeWatchdog`, `ErrorReporter` (the scopes' handler), `Breadcrumbs`
  (F1b). `ui/diagnostics/`: `CrashCard` (the gallery's card, tag `V19Tags.CRASH_CARD`),
  `DiagnosticsSection` (the settings' section under F3's "Show frame times"), `ReportSharing`
  (copy, share, "Share all", the rows' times). `engine/RenderScaled.kt`. The tools:
  `tools/select/magnetic/MagneticLassoTool.kt`, `tools/select/colorrange/ColorSelectTool.kt` and
  `ColorPreview.kt` (`ColorMasks`). `EditorController` region `v19-A7a savedSelectionWork`.
- **Reports and caps.** `filesDir/diagnostics/reports/<13-digit millis>-<kind>[-quiet].txt`
  (kind `crash`, `out_of_memory`, `freeze`, `native`, `anr`, `low_memory_kill`); the id is the
  file name without `.txt`. At most `CrashLog.MAX_REPORTS` = 20 (the oldest goes) of
  `CrashLog.MAX_BYTES` = 64 KB each (the last section is cut, ending "[cut: N more lines]"). A
  handled error is kept at most once a minute per place and class (`REPEAT_MS`). Quiet reports
  (handled errors, freezes, a low-memory kill in the background, the exits from before the first
  run with the log) are listed under Diagnostics only; the others put the card on the gallery
  until it is dismissed (preference "seenAt"; "exitSeenAt" is the newest exit read).
- **Format** (`ReportText`): "Brushwork crash report", then the fields `Kind`, `Fatal`, `Time`
  (UTC), `Where`, `App` ("1.9 (19) release"), `Device` (maker, model, Android, ABI), `Memory`
  (Java heap used of max, native heap; PSS on API 29+), `Canvas` (size, layers, tool: when an
  editor is open), `Summary` (what the Diagnostics row shows: "Crash: IllegalStateException",
  "Handled error: X in <where>", "Freeze: 4.2 s"), then "Breadcrumbs, oldest first" (the last
  32) and "Stack" (the shape of `printStackTrace`, at most 120 frames per throwable and 10
  throwables; an exit has its trace's main thread or the tombstone's names instead).
- **Privacy** (`ReportText.scrub`, on every message and `Where`): the open artwork's name becomes
  `<name>`, a quoted text `<text>`, a path or content id `<path>`, a link `<uri>`, an e-mail
  `<email>`; one line, at most 300 chars. Only class names, line numbers, sizes, counts and the
  app's ids stay. Breadcrumbs hold no personal data by contract and are kept as they are. A name
  in an exception's message without quotes is not recognised unless it is the open artwork's.
- **Install** (first in `BrushworkApp.onCreate`, after `super`): the handler goes before the default one,
  writes the fatal report synchronously (about 5 ms, at most 50 ms; a reserve is freed first for
  an out-of-memory crash) and hands the crash on, so Android still ends the process. Then, on the
  diagnostics thread ("brushwork-diagnostics", one daemon thread, ended after 2 s idle), the
  exits are read; the watchdog starts. Under Robolectric only the app is kept (no JVM-wide
  handler, no watchdog, no exit read). Nothing of it ever throws or blocks the main thread.
- **Exit reasons** (`ExitReasons`, API 30+, at most 20 ms, off the main thread): the exits after
  "exitSeenAt", oldest first. `REASON_LOW_MEMORY` (or, where low-memory kills are not reported, a
  SIGKILL) becomes `LOW_MEMORY_KILL` (quiet in the background), `REASON_ANR` an `ANR` with the
  trace's main thread, `REASON_CRASH_NATIVE` a `NATIVE` with the tombstone's library and function
  names (heuristic). `REASON_CRASH` has its report from the handler already. Each exit carries
  the process's last state summary (the breadcrumbs, `publishSummary`).
- **Freeze watchdog** (`FreezeWatchdog`): its thread posts a no-op to the main thread every 1 s
  while an activity is started and no debugger is attached (under 0.1 ms a second); unanswered
  for 4 s, it takes the main thread's stack once and writes one quiet `FREEZE` report ("Freeze:
  4.2 s", section "Main thread"). A gap of more than two ticks (the phone slept) starts it over.
  Its start and stop run on its own thread, in order with the ticks (a rotation never leaves two
  tick loops).
- **Errors no job caught** (`ErrorReporter.handler`, in every scope the app makes:
  `BrushworkApp.appScope`, `EditorSession.scope`, `FontStore`, every `rememberCoroutineScope`;
  `UnguardedLaunchGateTest` fails a scope without it, and `GlobalScope` always). Release builds
  (not debuggable): the error is kept as a quiet report, the open tool's touch is cancelled
  (`Tool.onCancel`), `StabilityLabels19.ERROR_TOAST` names the tool (outside the editor, the
  error's class), and the app goes on (`SupervisorJob`: only the failed job ends; also when the
  scope's owner had closed it, as the editor scope when the editor closes, and the job throws as
  it unwinds). Not where the error ends the job's scope (`ErrorReporter.tookItsScopeDown`: the
  parent job is no `SupervisorJob`, told by class identity, and is no longer active): a
  composable's `rememberCoroutineScope` is no supervisor (Compose makes it a plain child of the
  window's Recomposer and refuses a job of ours), so its job's error cancels the Recomposer too,
  even after the composable has left, and going on would leave a screen that never draws or
  takes a touch again; that error is handed on and the app stops with the report (v1.8's outcome).
  Debug builds, tests and a JVM with no app hand every error to the thread's uncaught handler,
  as v1.8 did, so a bug is never hidden. `EditorSession` keeps its open, save and close failures
  as reports too.
- **Line numbers in release.** `app/proguard-rules.pro`: `-keepattributes
  SourceFile,LineNumberTable` and `-renamesourcefileattribute SourceFile`, so a stack reads
  `at a.b.c(SourceFile:120)`. Keep each release's `app/build/outputs/mapping/release/mapping.txt`;
  R8's `retrace` with it turns a shared report back into class, method and line names.
- **The card and the list.** The gallery shows `CrashCard` for the newest report neither seen
  nor quiet: `CRASH_TITLE`, `CRASH_SAVED` (and `CRASH_OOM` for memory), then Copy, Share and
  Dismiss, each at least 40 dp tall, on one line at font scale 1. Diagnostics (the editor's
  settings, under "Show frame times"): "Crash reports (n)", each report's summary with its own
  "Copy report <time>" and "Share report <time>" (time "13 Oct 14:03"; the seconds, then " (2)",
  when two would read alike), then "Share all" (one text; past 100 KB a file in
  `cacheDir/exports/` through the FileProvider) and "Delete all reports". The files are read and
  written off the main thread.
- **`RenderScaled`** (`engine/RenderScaled.kt`): what the Magnetic lasso's edge map and Color
  select's proxy read. `Source.canvas(doc)` and `Source.layer(bitmap)` are taken on the main
  thread (no pixel read: a private document holding the same layers, its paper and colour mode);
  `render(source, maxSide = 2048, withPaper)` and `renderAt(source, w, h, withPaper)` run on any
  thread: 1:1 the flatten; from 1/2 up straight through a scaled canvas (no full-size bitmap);
  below 1/2 at twice the size, then filtered down, as `renderThumbnail`. `withPaper` (both tools)
  puts the paper under it, behind a visible adjustment layer as a matte. Its size rounds as
  `EdgeMap.proxySize`. A layer recycled meanwhile throws a `RuntimeException`: the tools take a
  new source and try once more. PRINT (3000 x 4000, 3 layers): at most 60 ms on the phone.
- **Magnetic lasso.** The edge map is rendered on the tool's worker by `renderSource` (a test
  hook) instead of a full-size flatten on the main thread (PRINT: 12 MB at 1536 x 2048 in place
  of 48 MB). The map, trace and closing jobs are guarded: a failure is kept as a report and said
  (`ERROR_TOAST`; out of memory `MagneticLabels18.OUT_OF_MEMORY`), the touch ends, the map goes
  and is not built again for the same pixels (the next activation tries again), the anchors stay
  and the outline still closes (a closing that fails selects the outline as drawn).
- **Color select.** The proxy is rendered off the main thread at `ColorMasks.proxySize` by
  `renderSource`; `drawOverlay` no longer builds it: a draw that finds the pixels changed keeps
  drawing the last preview and asks once for a new proxy (`proxyRequest`, part of `idle`), built
  after the draw. The proxy, preview and commit jobs are guarded the same way. The bands of
  `ColorMasks` run on the caller and at most three helpers of
  `Dispatchers.Default.limitedParallelism(4)` (`ColorMasks.WORKERS`; v1.8: the common fork-join
  pool on every core); a band starts only while the job is not cancelled, the call returns only
  when no band runs, and a band's error is thrown to the caller.
- **Saved selections** (region `v19-A7a savedSelectionWork`): a compression that throws never
  throws at `await` (nor from the undo that lands it): the save is dropped, kept as a report and
  said (`ERROR_TOAST` with "Save selection" or "Update saved selection").
- **The lead's, at A7a's merge.** The report kinds' names and the report's title are
  `StabilityLabels19` entries (`KIND_CRASH`, `KIND_HANDLED`, `KIND_OUT_OF_MEMORY`, `KIND_FREEZE`,
  `KIND_NATIVE`, `KIND_ANR`, `KIND_LOW_MEMORY_KILL`, `REPORT_TITLE`), which `ReportText` uses.
  Compat row 15 (`Qa19CompatBackFromV18RobolectricTest.row15CrashReports`, fixture
  `qa19/compat/crash/`): one fatal report and the "diagnostics" preferences, both outside
  `files/projects/`, so v1.8 never sees them.
- **Not A7a's** (open lead requests): excluding `files/diagnostics/` and
  `shared_prefs/diagnostics.xml` from Auto Backup and from the backup folder (A8's step 7: the
  manifest's backup rules); a supervisor scope for the composables' jobs (their
  `rememberCoroutineScope` sites, Gate 2), so that their errors too are survived rather than
  handed on.
- **Tests** (`app/src/test/java/com/brushwork/paint/v19/a7a/`): `CrashLogTest`,
  `ExitInfoReasonTest`, `FreezeWatchdogTest`, `ErrorReporterReleaseTest`,
  `ErrorReporterScopeTest` (a real composition: its Recomposer is gone, the error handed on, also
  after the composable has left; a supervisor closed by its owner: the error survived),
  `UnguardedLaunchGateTest`, `CrashCardRobolectricTest`, `DiagnosticsSectionRobolectricTest` (the
  one UI class: the card at 360 and 392 dp and the list, font scales 1, 1.3 and 2.0),
  `MagneticLassoGuardTest`, `ColorSelectNoProxyInDrawTest`, `RenderScaledTest`,
  `SavedSelectionWorkGuardTest`, and the Timing guard `RenderScaledPrintTimingTest`.

<!-- /v19-A7a -->

### Paper, new canvas and backup (A8)

<!-- v19-A8 -->

R15 (one layer, the paper) and R16 (the artworks outlive an uninstall). Steps 1-8, 10 and 11 are
built; step 9 (the zip archive: Save all / Open file) is cut, because the backup folder already
keeps the artworks across an uninstall. `BackupLabels19.SAVE_ALL` and `OPEN_FILE` stay unused.

**New canvas (R15).** The New canvas dialog's Background row (`ui/gallery/NewCanvasBackground.kt`)
offers White, Transparent, a colour and Paper, in that order. `AppSettings.newCanvasBackground`
remembers the choice (not the custom colour), and a caption sits under the row. Each choice maps
to a `CanvasStart`, and `CanvasCreate.seed` makes one "Layer 1", active:
- `FILL`: filled with `NewCanvasSpec.background`;
- `TRANSPARENT`: empty;
- `PAPER`: empty over `PaperSpec()` (white).

A null `start` keeps the v1.8 two-layer seed. Test fixtures and the SVG/PDF import use it, and
`storage/CanvasCreateV18Test` pins it. `seedFromImage` ("New from photo") gives the "Picture"
layer alone, active, with no paper.

**The paper (I21).** `PaperBackdrop.draw` is one clipped `drawColor`, with no allocation, so the
canvas calls it every frame. The sites listed under "Paper and its sites" above are pinned by
`v19/a8/PaperBackdropSitesTest`: a search of the main sources fails on a new site and on a site
that is gone. Every flattened raster export and share (gallery and editor; PNG, WEBP, JPEG) is on
the paper. Layer exports and the payload's layer pictures are the layer's own pixels.

The paper travels in the Brushwork payload of SVG and PDF (`BrushworkPayload.paper`; no `paper`
key without one, I13). An import that makes a new artwork takes the payload's paper outside the
import's undo step; an import into an open artwork never changes its paper.

A memory refusal of a flatten or of JPEG's white copy throws `NotEnoughMemoryException(purpose)`
(`storage/ImageExport.kt`). Its message is `StabilityLabels19.NO_MEMORY_FOR`, and the gallery and
editor show it.

**Include paper (step 10).** In `ui/exchange/ExportOptionsSheet.kt`, a document on paper shows
`CanvasLabels19.INCLUDE_PAPER` (`ExportOptions.includePaper`, on by default) in the SVG / PDF
Background section. While it is on, the paper is the scene's background, and the Transparent /
White chips are hidden. With it off, those chips decide. A document without paper shows no
switch.

**Backup folder (R16).** The `storage/backup/` package:
- `BackupService` (started by `BrushworkApp`) reads `noBackupFilesDir/backup-state.json`
  (`BackupStateStore`, `BackupStateData`). With no folder the status is `NotSet`; with a lost
  grant it is `Unavailable`; otherwise a catch-up runs. `ProjectRepository` calls
  `onSaved(id)` / `onDeleted(id)` after each save, create, rename, duplicate and delete, outside
  the project's lock. They only queue the id, conflated per artwork. One low-priority worker
  thread mirrors the queue, then rewrites the index and, when changed, the settings.
- `BackupMirror` mirrors one artwork into `Brushwork backup/<id>/` (layout in `BackupLayout`).
  Data files are copied once. `project.json` is replaced in the R16 order: create
  `project.json.new`, write it "wt", delete `project.json`, rename. A provider without
  `FLAG_SUPPORTS_RENAME` instead gets `.new` written, `project.json` rewritten "wt", and `.new`
  deleted. Stale revisions and a leftover `.new` go.
- A deleted artwork moves to `deleted/<id>/`. It is purged after 30 days, and oldest first while
  the folder's volume has less than 500 MB free. Restored with its id, it moves back
  (`BackupMirror.reviveDeleted`), so no data file is copied again and nothing stays in
  `deleted/` that the purge no longer knows. The restore list shows each id once.
- `BackupIndex` writes `brushwork-backup.json`, format 1. `BackupSettings` writes `settings.json`,
  format 1: the settings, brush edits and palettes (their three preference files), plus the
  imported fonts' list (the files go under `fonts/`). It leaves out UI state, filter recents and
  diagnostics.
- `SafTree` lists each folder with one children query per run and never calls
  `DocumentFile.findFile`. It writes with "wt", and falls back to "w" on a fresh file for a
  provider that refuses "wt".

**Restore (R16).** `BackupRestore` accepts the picked folder or its `Brushwork backup` child, even
when that child is renamed (found by its index). It lists the artworks from the index, or by a
folder scan when the index is missing or leaves some out. It reads `project.json.new` when
`project.json` is missing or damaged.

Each artwork is staged in `projects/` under a staging prefix, then checked: every file is copied
in full (the size the folder lists), and every file `project.json` references is present. Only
then is it renamed into the gallery. A restore never
overwrites an artwork (I23): an id the gallery has gets a new id and
`BackupLabels19.RESTORED_SUFFIX`. Works the gallery holds at the same revision are marked
`BackupLabels19.ALREADY` and unchecked, and deleted ones sit unchecked in a collapsed section.
`BackupLabels19.ALSO_SETTINGS` writes the settings back and merges brushes, palettes
(`PaletteStore.mergeMissing`) and fonts (`FontStore.restoreFonts`) by id, adding only the missing
ones.

**The UI.** `ui/backup/BackupFlow` holds the folder pick, the restore offer and the restore list.
The picker opens at `Documents` (`BackupFlow.INITIAL_URI`). The gallery's notice, its ⋮ menu and
its empty state share the flow, and so does the editor's Settings `BackupSection`. The screen's
`BackupHost` runs the picker and the dialogs (`RestoreDialogs.kt`).

A storage root, Download or no answer is refused (`CHOOSE_AGAIN`). A folder that already holds a
backup offers `RESTORE_THEM` / `BACK_UP_HERE` and writes nothing until the user chooses.
`GalleryNotices.pickNotice` shows one notice, in this order: an unseen crash report, the folder
unavailable, no folder, then the first backup's progress. The no-folder notice waits for the first
artwork, and `LATER` hides it for 7 days, kept in the state file.

**Auto Backup.** The manifest names `res/xml/backup_rules.xml` (Android 8 to 11) and
`data_extraction_rules.xml` (12 and newer, cloud and device transfer). Both are includes only:
`brushwork_settings.xml`, `brushwork_brushes.xml` and `brushwork_palettes.xml`. Lint's
`FullBackupContent` check is fatal on an exclude outside the includes. The backup state lies under
`noBackupFilesDir`, so a folder grant never comes back on a new install.
`android:hasFragileUserData` makes the uninstall dialog offer to keep the app's data.

**Release guards (step 8).** `app/build.gradle.kts` registers `checkReleaseGuards` before
`preReleaseBuild`. The `versionCode` is `BW_VERSION_CODE`, the workflow's run number, or 1
locally. With `BW_RELEASE_GUARD=true`, which the workflow sets only for the build of a release tag
(never for a push to `main` or a pull request, so those stay green; the lead, at A8's merge), a
release build fails in either case:
- the release keystore is missing (`BW_KEYSTORE_FILE`); a debug-signed APK does not install over
  the release, and uninstalling deletes the artworks;
- the `versionCode` is not above `BW_LAST_VERSION_CODE`.

On a tag, the workflow looks up `BW_LAST_VERSION_CODE`: the run number of the successful push
build of the last `vX.Y.Z` release (`gh release list`, `gh run list`; `actions: read`). Without
the guard, a build only warns. The workflow also uploads R8's `mapping.txt` as the `mapping-<run>` artifact.

**Gallery thumbnails (step 11).** `ui/gallery/ThumbnailCache` holds at most 24 MB
(`MAX_BYTES`, LRU by bytes). It is registered with `MemoryGovernor` at priority 0 (a display
cache) as "Gallery thumbnails". MODERATE pressure halves it, and LOW and CRITICAL empty it. A
dropped thumbnail is decoded again when the gallery shows it.

**Tests.** `v19/a8/`: `NewCanvasStartTest`, `PaperBackdropSitesTest`, `PaperExportTest`,
`PaperPayloadRoundTripTest`, `IncludePaperSwitchTest` (UI), `RestoreOfferTest`,
`GalleryNoticePriorityTest`, `BackupMirrorTest`, `BackupDeletedRetentionTest`,
`RestoreFindsFolderTest`, `RestoreNeverOverwritesTest`, `RestoreRepeatTest`,
`AutoBackupRulesTest`, `BackupStateNotBackedUpTest`, `ThumbnailCacheGovernorTest`.

`FakeDocumentsProvider` stands in for the phone's storage. It does not truncate on "w", never
replaces on rename, can lack rename support or fail the next rename, and reports its free space.
`BackupKit` scripts the user's life with the app: paint, choose a folder, uninstall and install
again.

The UI classes are `qa19/Qa19NewCanvasUiTest` and `Qa19NewCanvasNarrowUiTest`, and
`Qa19BackupUiTest` and `Qa19BackupNarrowUiTest` (392 and 360 dp, font scales to 2: the notice,
the offer and the list, "Later", the empty gallery's restore, the editor Settings' Backup section
and the gallery's ⋮ menu). The compat
rows 09, 11, 15 and 16 of `qa19/Qa19CompatBackFromV18RobolectricTest` read A8's fixtures in
`test/resources/qa19/compat/` with v1.8's readers.

<!-- /v19-A8 -->

### Erase routing and the cut (A5)

<!-- v19-A5 -->

R11: the Eraser and the four tools in erase mode (Curve, Polyline, Path, Shape) erase objects on
vector and legacy shape layers, live, in one undo step, and never turn such a layer into pixels on
their own (I20). Code: `vector/draw/` (routing, session, cut, landing, preview, tool erase),
`vector/pathfinder/` (region ops), `ui/vector/VectorEraseChooser.kt`, `ui/vector/ErasePixelsDialog.kt`,
`ui/brush/BrushStrip.kt`, `ui/editor/chrome/BottomBar.kt`.

**Routing (I20).** Every erase asks `EraseRouting.route(layer, source, maskTarget, isPath)` first,
in this order; the source (the Eraser brush or a tool) does not change the route:

| Layer or target | Route | What happens |
|---|---|---|
| folder | `REFUSE` | v1.8's `checkUsable` message |
| a layer mask (an adjustment layer's mask included) | `RASTER` | paints the mask, as v1.8 |
| adjustment layer content | `REFUSE` | v1.8's `checkEditable` message |
| vector layer | `OBJECTS` | the object eraser in the current mode |
| legacy shape layer | `CONVERT_THEN_OBJECTS` | `LayerConvert.toVectorAction` and the erase in ONE step (`EraseLanding`); a cancelled or empty erase leaves no step |
| text layer | `RASTERIZE_DATA` | v1.8's undoable rasterize with its toast |
| any other layer | `RASTER` | pixels |

The one exception is `isPath` with the Eraser brush (BrushTool's path stroke, never hooked): a
vector or shape layer stays v1.8's `RASTERIZE_DATA` (lead request: A6 to route it too).
`ToolErase.begin` uses the same table (`isPath = false`) and returns null, after saying why, for a
refused layer, a locked or hidden one, an alpha-locked layer's pixels or a shape that doesn't decode.

**The three modes.** `VectorEraseMode` keeps v1.8's three buttons (OBJECT, PARTIAL,
TO_INTERSECTION; v1.8's descriptions, which the frozen labels test reads as v1.8 text); the strip
and the hint show `VectorEraseMode.note`, which for PARTIAL is `EraseLabels19.PARTIAL_NOTE`. What
the user asked for as three ways of erasing:
- Object (`OBJECT`): every object the eraser touches goes whole (all its copies too).
- Partial (`PARTIAL`): strokes and open lines (one open sub-path, no fill) are cut along the
  eraser as in v1.8 (`EraseSession`); shapes of any style except lines and arrows, and paths with
  a fill, are cut by the region the eraser swept (`EraseCut.cutsByRegion`), the pieces editable
  `VPath`s in the object's place and style (a stroke-only shape's cut edge is stroked too, so its
  gap closes into a dent at the lift); closed or multi-sub-path paths without a fill go whole, as
  in v1.8, now without v1.8's hint.
- Turn into pixels (`EraseLabels19.TURN_INTO_PIXELS`, the Eraser's More row, last, warning tint;
  also the last entry of the tools' mode cell): `ErasePixelsDialog` asks first
  (`PIXELS_TITLE`, `PIXELS_BODY`, `PIXELS_CONFIRM`), then `ErasePixels.turnIntoPixels` rasterizes
  the layer captured at tap time in one undoable step (`VectorLayerOps.rasterize`, or for a shape
  layer `setLayerData` with Transform's rasterize label) and the open tool erase reroutes to pixels.

**EraseCut** (`EraseCut.cut`, `EraseCut.cutAll`; pure, thread-safe, Skia PathOps through
`vector/pathfinder/`). A target's region is `PathConvert.region` (outline under its fill rule, a
shape's outline; live copies baked first, each copy cut on its own). Strokes and line or arrow
shapes are no operand (the v1.8 stroke cut handles them). Rules and caps:
- the eraser must take at least `PathfinderOps.SLIVER_AREA` (0.5 px²) of the region, else the
  object is untouched (a graze of its outline leaves it);
- the pieces are the connected components of region minus eraser, each a plain `VPath` with the
  object's fill, stroke and opacity (`PathfinderStyle.objectOf`), so the cut edge is stroked; a
  shape reopens as a Bézier path; slivers are dropped; nothing left removes the object;
- more than `EraseCut.MAX_PIECES` (256) pieces, or a failed op, leaves the object unchanged;
- only the topmost `EraseCut.MAX_TARGETS` (500) targets of a gesture are cut; the rest stay;
- one toast after the step (`EraseLanding.land`): `EraseLabels19.TOO_COMPLEX` when any object was
  left as it was by a cap or a failed op (also with no step, when nothing else was erased), else
  `EraseLabels19.COPIES_BAKED` when an object's live copies became pieces of their own.

**Landing** (`EraseLanding`): queued behind the layer's pending updates (`VectorDrawState.serial`),
applied to what the layer holds now (the footprint fed again when it changed). The region cut runs
on `Dispatchers.Default` (`VectorDrawState.cutDispatcher` is the test seam), the busy overlay after
`EraseLanding.CUT_BUSY_AFTER_MS`; it runs on the main thread for few targets (a tool erase: up to 50),
inside another step, under another busy operation or after two reruns, and under a test's render
policy (`VectorLayers.Policy` SYNC or ASYNC) unless the test sets `cutDispatcher`
(`VectorDrawState.cutsInBackground`), so Robolectric tests stay deterministic. While it runs it is the
editor's `DeferredStep`: anything else that records a step lands it first. A layer that changed
meanwhile reruns the erase on its new content. Undo: one step labelled
`VectorEraserRecorder.ERASE_LABEL` (the Eraser) or `EraseLabels19.STEP_*` (a tool).

**The preview** (`DoomPreview`, the render override while the finger is down): the layer without
what will go, as the result looks (v1.8 dimmed). Each move rebuilds only the changed parts into an
alpha mask redrawn at most once per frame (`DoomPreview.rebuilds`; `P0ProxyBench` reads `items`).
In PARTIAL, filled targets are punched by the eraser's capsules (`punch`, `punchOutline`). Known
differences with the result, until the lift: at sliver edges, a graze the cut ignores looks erased
in the preview, and the result strokes the cut edge where the preview shows a clean edge; an object
that stays but lies under a removed part is hidden there too (the layer's pixels are one image).

**ToolErase** (`vector/draw/ToolErase.kt`): a tool's erase with its own outline (`ToolEraseSession`):
its footprint is the outline stroked at the tool's width, round, plus the fill of a filled closed
outline. On RASTER and RASTERIZE_DATA it takes pixels away live (`VectorPaintSpec.erase`, DST_OUT;
black into a mask) inside the selection and bakes at commit; on OBJECTS and CONVERT_THEN_OBJECTS it
erases objects in the Eraser's mode through the same session, cut and landing. Contract for A1 and
A9: `begin` null means refused (the tool shows its outline only); call `update` on every outline
change, mode change and after Turn into pixels (`reroute`); the session owns the render override;
`commit(label)` returns `Done`, `Nothing` or `Refused`; `cancel` when erase mode goes off.

**UI.** Row 1 of the Eraser on a vector or shape layer holds `VectorEraseChooser.item` (the three
mode buttons, `V19Tags.ERASE_CHOOSER`) and the size; the opacity goes to the More row, where
`VectorEraseChooser.pixelsItem` (`StripKeysV19.ERASE_PIXELS`, `V19Tags.ERASE_PIXELS`) comes last;
at a large font scale its text shrinks (never below 11 sp) to stay within two lines of its cell
(`VectorEraseChooser.FittingFontScale`, I27).
The four tools while erasing such a layer get `VectorEraseChooser.modeCell`
(`StripKeysV19.ERASE_MODE_CELL`, `EraseLabels19.MODE_CELL`), whose menu lists the modes with their
notes, a divider and Turn into pixels. The cell is 56 dp at overflow rank 0 (frozen strip key):
the four tools' strips (A1, A9) must leave room for it in row 1 (F1a G5).
Bottom bar slot 1 (`EraseModeSlot`, `V19Tags.ERASE_SLOT`): for an `ErasableTool` it reads `eraseLabel` or `erasingLabel`, draws open while erasing and toggles
`setErase`; for any other tool it is v1.8's slot.

Tests: `v19/a5/` (routing, cut, landing, tool erase, chooser and slot UI, Timing:
`EraseCutPerfTest` 50 and 500 filled targets, `EraseRebuildPerfTest` a frame with 2000 objects
within 8 ms per mode), `qa19/Qa19EraseModesUiTest` (360 and 392 dp, font scales 1.3 and 2.0), and
compat row 10 (cut pieces) of `Qa19CompatBackFromV18RobolectricTest`.

<!-- /v19-A5 -->

### Curve tools (A1)

<!-- v19-A1 -->

R1, R2, R18 (Polyline), R14 (curves) and R11 for the Curve, Polyline and Path tools (one class,
`CurveTool`, by `kind`). Code: `tools/vector/CurveTool.kt`, `CurveGroupMath.kt`, `CurveGeometry.kt`,
`tools/points/Mixed.kt`, `ui/points/ThicknessRow.kt`, `ui/points/MixedNumberField.kt`,
`ui/vector/Curve*.kt`.

**Point thickness, one point and several (R1, R2).** `ThicknessRow` (‹, the value box, ›, the blue
slider; 260 dp, `ThicknessRowLabels`) is v1.6's one-point control moved out of
`CurveToolOptions.kt` unchanged (`ThicknessRowGoldenTest`). With two or more points selected the
More row shows the same row as `GroupThicknessControl`, and Path's group weight popup shows
`GroupWeightControl` (the orange log slider, 0.1 to 10); `CurveGroupEdit` runs both. The value box
reads the shared value or "Mixed", the thumb sits at the points' mean, and while the thumb is
dragged the box shows the factor (`PointLabels19.FACTOR`). Rules (`MixedEdit`):
- `dragged`: only a drag that starts on the thumb moves values, relatively: each point scales by
  thumb / start mean, so a taper keeps its ratios; a point at 0 % rises by the thumb's gain; from a
  mean of 0 every point takes the thumb. A track tap or a double tap does nothing in group form.
- `stepped`: ‹ › move each point to its next step on the percent grid (0 % goes up one step).
- `offset` / `typed`: "*2", "/2", "+10", "-10" apply to each point; a plain number sets all.
- R2 (the one-point report, "doesn't move up from 0 %"): at 0 % the thumb sits flush against ›'s
  40 dp box; a touch up to `GroupSliderGeometry.EDGE_REACH` (8 dp) beside the slider's start edge
  goes to the thumb while the thumb is near (`thumbBesideSlider`), so › no longer ticks +5 % and
  swallows the drag. Elsewhere › ticks as before.
- One drag, one ‹ › hold or one typed value is one in-tool step. `setWidth` and `setWidths` clamp
  with one constant, `CurveWidths.MAX_FACTOR` (= `VSpline.MAX_WIDTH`, 3).

**Bevel points, Polyline (R18).** While points are selected the More row shows "Bevel points"
(`StripKeysV19.BEVEL`, `V19Tags.BEVEL`). `CurveTool.bevelPoints` bevels the selected sharp corners
with the remembered Amount and Segments (`AppSettings.curveBevelAmount`, `curveBevelSegments`) through
`PointBevel`, as ONE in-tool step, selects the new points and opens the frozen `BevelPopup` in its
own window (`CurveBevelPopup.kt`), above the bottom bar. Its changes re-apply the same step from the
pre-bevel copy; a re-tap while the new points stay selected reopens it; any other edit, undo, redo
or Back ends the session; the canvas tap that closes it adds no point. Only sharp corners bevel
(`PointLabels19.BEVEL_SHARP_ONLY`); past 2000 points nothing changes (`BEVEL_TOO_MANY`).

**Curves of several kinds on one layer (R14).** A tap on a path object of the active vector layer
reopens it in the tool that made it (`reopenablePathAt`): Curve and Polyline now reopen each
other's lines, as both did with Path's since v1.6. A switch hands the open path over
(`Handoff`), shows `CurveLabels19.SWITCHED_TO` and raises `CurveTool.slotFlashes(tools)`, the one
counter the bottom bar keys its tool-slot flash on: `BottomBar.kt` (A5's file, written by the lead
at A1's merge, `toolSlotFlashing`) draws slot 2's open-state box for `SLOT_FLASH_MS` (200 ms),
timed on the frame clock, each time the counter rises after the bar was shown
(`EraseSlotRobolectricTest`). The reopened edit commits as one controller step. Old projects open as
they were (compat rows `row10MixedCurves`, `row10BevelledCurvePoints`).

**Editable (own layer) (R14, D22).** The last row of each curve tool's settings sheet is
`CurveLabels19.OWN_LAYER` with `OWN_LAYER_DESC` (`CurveSettings.ownLayer`, per tool, off by default
and never written while off; hidden while a path is reopened). On, ✓ of a NEW path on a pixel
layer (`ownLayerTarget`: the active layer, content not mask, not a vector, text, shape or
adjustment layer or a folder, and not a brush that needs pixels, which paints into the layer as
Shape does) runs `commitOwnLayer`: inside one `groupUndo` named after the tool it adds
`addVectorLayer(baseName = CurveLabels19.LAYER_BASE)` ("Curves N", N = vector layers + 1, above the
pixel layer, active) and the path as its object (`vectors.addObjects`, synchronous inside the step;
perspective copies baked as on a vector layer). The pixel layer stays byte-identical (the live
brush stroke is cancelled first). The next paths of any kind join that layer as v1.8 objects.
`checkEditable` on the pixel layer runs first, so a locked or hidden layer refuses as before.
Deviation from addLayerWith's rule that pending work commits before the pause: `addingOwnLayer`
makes the nested `onDeactivate` a no-op, so the path survives the insert and simply stays pending
at the layer limit (out of memory for the object, the empty layer is deleted in the same step).
The preview treats such a path as an object (`objectTarget`: no selection or alpha-lock clip,
copies drawn one by one); it is still drawn inside the pixel layer, so that layer's opacity, blend
mode or mask show on the preview but not on the result (a device check). Ignored while erasing,
on a vector layer (paths join it) and for a reopened path. `update`, `showLook` and `adopt` keep
`ownLayer` the user's, like the unit and nudge step.

**Erase mode (R11 a).** `CurveTool` is an `ErasableTool`: slot 1 reads `EraseLabels19.WITH_CURVE`
(`WITH_POLYLINE`, `WITH_PATH`) or the `ERASING_*` twin, and the last row of the "Stroke kind" menu,
`EraseLabels19.INSTEAD_OF_PAINT`, is the same switch (the menu's face shows the eraser glyph while
on). Erase mode is transient: `onSelected` turns it off when the user picks the tool; it stays on
for the next path. While on, the pending outline is drawn dashed and erases live through A5's
`ToolErase` session (see the A5 block for routing, modes and landing):
- The footprint is what the tool would paint: the line at its width (the brush's size for
  "Current brush", the plain line's width; none when every point is at 0 %), plus the fill when it
  is on and possible (an open path fills as if closed). Varying thickness erases the line's
  outline; mirror copies erase too (a fill or a varying line is one region, united with PathOps,
  as mirrored copies wind the other way). Taper and pressure are ignored: full width to the ends.
  Cost: a plain line of uniform width erases with the path itself (about 1 ms a frame at 500
  points on the desktop JVM); a fill or a varying line with copies is united with PathOps every
  frame (10 to 22 ms at 500 points, about 3 ms at 50), which the paint preview never builds. The
  Timing guard `CurveEraseFrameBudgetTest` holds 50 points within 4 ms; many points with copies
  on the phone is a device check.
- ✓ commits ONE step labelled `EraseLabels19.STEP_PATH` (the Path tool), `STEP_FILL` (a fill
  alone) or `STEP_LINE`; a refused commit keeps the path. A refused layer (locked, hidden, an
  alpha-locked layer's pixels, a folder) shows the outline only and says why once per layer state
  (`eraseRefused`, keyed on the layer and `layersVersion`, cleared at ✓).
- No reopening while erasing (a tap starts an outline); an explicit reopen turns erase off;
  turning erase on applies a reopened path first. "To Bézier" and its hand-back keep erase mode.
- On a vector or legacy shape layer (content, not mask) row 1 shows A5's mode cell
  (`VectorEraseChooser.modeCell`, `StripKeysV19.ERASE_MODE_CELL`, `EraseLabels19.MODE_CELL`)
  right after "Stroke kind" (`CurveStripFacts.eraseCell`); its "Turn into pixels" opens
  `ErasePixelsDialog`. Room for it at 360 dp with the VECTOR cell: "Stroke kind" becomes AUTO
  (rank 1) on an open Curve and Polyline and on Path; Curve's generator chip and an open Path's
  "Order N" (after the weight) become More items; the "Use brush size" / "Taper ends" shortcut
  leaves the strip (both stay in the settings sheet). Without the cell the strips are v1.8's.

Tests: `v19/a1/` (`GroupThicknessControlTest`, `GroupWeightControlTest`, `ThicknessRowGoldenTest`,
`ThicknessFromZeroTest`, `CurveWidthConstantsTest`, `PolylineBevelRobolectricTest`,
`CurvePolylineReopenTest`, `CurveEraseRobolectricTest` on raster, text, vector and legacy shape
layers, `CurveStripInventoryTest` every strip state at 392 and 360 dp with the label scan,
`CurveOwnLayerTest`, `CurveEraseRoundTripTest` (every erase step's undo and redo, save and reopen,
"Turn into pixels" mid-outline, "To Bézier" with erase on, in-tool undo while erasing); Timing
`GroupThicknessBudgetTest`, `CurveEraseFrameBudgetTest`), `qa19/Qa19ThicknessUiTest` (also the own
layer row of each settings sheet at 360 dp and font scale 1.3 and 2.0, and erasing a vector layer
through the stroke kind menu, the mode cell and the pixels dialog at 392 and 360 dp and every font
scale), `qa19/Qa19PolylineBevelUiTest`.

<!-- /v19-A1 -->

### Shape tools (A9)

<!-- v19-A9 -->

R17, R14 (S1), R18 and R11 for the Shape tool: per-edit undo, new shapes joining the selected shape
layer, Bevel points, the More row's object items and erase mode. Code: `tools/vector/ShapeTool.kt`
(the tool), `ui/vector/ShapeStrip.kt` and `ShapeStripParts.kt` (the strip), `ui/vector/ShapeBevel.kt`
(the bevel item and popup host), `ui/vector/ShapeObjectItems.kt` (the object items),
`ui/vector/ShapePointOptions.kt` (the Shape settings rows), `ui/vector/ShapeRoundnessCell.kt`.

**Per-edit undo (R17).** While a shape is pending (a new one, a reopened legacy shape layer or a
reopened `VShape`), each finished move, resize, rotation, line-end or point drag, nudge run, pinch,
typed or scrubbed value, point edit and bevel is one in-tool step (`ShapeTool.history`, at most 200,
the oldest dropped), for every type, with or without its own points; before v1.9 only a shape with
points had them. Undo (the button or a two-finger tap) takes back the last edit and keeps the shape
open; redo steps survive a two-finger tap or pinch on the shape or its points' gizmo, and a pinch
that changed nothing leaves no step. At the bottom Undo acts as in v1.8: a new shape is discarded,
an untouched reopened shape closes and the document's last step is undone. Edits that bring a
reopened shape back to how it was opened are still undone one by one. The commit stays ONE
document step (`ShapeTool.SHAPE_LABEL` for a new shape, `EDIT_SHAPE_LABEL` for a reopened one).
Two rules:
- Type flip: a type change between line-like (Line, Arrow) and box-like types clears the in-tool
  history, because the old boxes don't map onto the new geometry. A change within one kind keeps it.
- Entering Points mode after other edits is no step of its own, because the outline does not
  change. Undo takes back the edit before it and leaves Points mode. At the bottom of the history
  it is one step, as in v1.8.

**Joining (R14, S1).** With "Editable (own layer)" on and `ShapeSettings.newLayerEachTime` off (the
default), a NEW shape that v1.8 would put in a layer of its own joins the selected legacy shape
layer (`joinTarget`). The layer becomes a vector layer in place (`LayerConvert.toVectorAction`) and
the shape one of its objects, in ONE step `SHAPE_LABEL` (`commitJoin`, rendered under
`VectorLayers.Policy.SYNC` so no render lands as a step of its own). There is no join into a layer
that is locked or hidden (also through a folder), into a layer whose mask is edited, with
Perspective copies, or with an outline whose brush moves pixels; v1.8's own layer is used instead.
A shape outside the canvas stays pending and nothing converts.
- "New layer each time" (`PlaceLabels19.NEW_LAYER_EACH_TIME`, `V19Tags.NEW_LAYER_EACH_TIME`) is a
  row of Shape settings below "Editable (own layer)", shown while that one is on. On, it gives
  v1.8's layer per shape. `newLayerEachTime` is NEVER-encoded, so v1.8 reads the settings.
- "Editable (own layer)" now describes the join (`PlaceLabels19.SHAPE_EDITABLE_DESC`).

**Bevel points (R18).** `PointLabels19.BEVEL` (`StripKeysV19.BEVEL`, 40 dp, last in the More row)
shows in Points mode. Tapped, it bevels the selected sharp corners at once with the remembered
Amount and Segments (`AppSettings.shapeBevelAmount` / `shapeBevelSegments`), as ONE in-tool step.
- The new points are sharp, have no handles and a roundness of 0, so the shape's corner radius
  does not round them. Points the bevel does not touch keep their smoothness, handles and roundness.
- The new points are selected, and the `BevelPopup` (`ShapeBevelPopupHost`,
  `V19Tags.BEVEL_POPUP`) adjusts that same step. It re-applies from the points before the bevel
  (`BevelSession`, I22). It closes on Done, on a tap on the canvas (consumed), on another edit, on
  undo, or when the shape closes.
- A smooth corner or too many points says why (`PointLabels19.BEVEL_SHARP_ONLY`, `BEVEL_TOO_MANY`).
- In Points mode the box's "Center" and "Proportional" / "Circle" are hidden
  (`ShapeStripInfo.boxModifiersShown`; they act on box handles and new-shape drags only). That keeps
  the fullest Points mode within three More lines at 360 dp with the VECTOR cell.

**Object items (R14).** "Duplicate" (`LayerLabels19.DUPLICATE`), "Bring forward" and "Send
backward" (`ObjectEdits.Arrange` labels; `StripKeysV19.DUPLICATE` / `FORWARD` / `BACKWARD`) come
after v1.8's More items. They show while the open shape is, or once placed becomes, a shape object
(`ShapeTool.objectActionsApply`): a reopened `VShape`, a new shape on a vector layer, or a new shape
that will join the selected shape layer. They never show in Points mode, for a reopened legacy shape
layer, or while erasing. They are hidden while the selection bar or the object bar shows
(`StripContext.selectionBarShown`), because those offer the same labels (I10). A tap places the
shape (its own step), runs the object bar's `ObjectActions.duplicate` / `arrange` on it alone (its
own step), and opens the copy or the shape again. Renders land at once (SYNC policy) meanwhile.

**Erase mode (R11).** `ShapeTool` is an `ErasableTool`: bottom-bar slot 1 reads
`EraseLabels19.WITH_SHAPE` / `ERASING_SHAPE`. "Stroke with" ends with a divider and
`EraseLabels19.INSTEAD_OF_PAINT` (checked while on), which toggles the same `setErase`. Turning the
mode on or off lands what is pending first: a pending shape is placed, and a pending erase lands in
the mode it was drawn in, so `setErase(false)` commits it. The mode is transient: it is never
saved, and it goes off whenever the tool is chosen again (`onSelected`). A layer change keeps it on
and lands the pending erase; a tool switch lands it too.
- Every shape drawn is then an eraser outline, drawn dashed (`OverlayPainter.path(dashed = true)`)
  with its copies. A `ToolEraseSession` (`ToolErase.begin(..., EraseSource.SHAPE)`) erases live as
  the outline changes, through A5's routing.
- The footprint is the outline at the stroke width (a brush outline at its preset size), plus the
  fill of a filled closed style; a fill-only style has no stroke width. The fill is non-zero, as
  the painted fill is, and every copy winds as its source (`ShapeCopies.mapped`), so mirror copies
  that overlap erase their overlap too.
- Every edit works as when painting (Points mode, the bevel and the per-edit undo included) and
  updates the session. A mode picked in the mode cell applies at once (`refreshPreview`).
- The commit (✓, a tap outside, a new drag, a tool or layer switch, turning the mode off) is ONE
  step `EraseLabels19.STEP_SHAPE`; ✕ leaves no trace. Only one outline erases at a time: a drag
  that starts a new shape lands the pending erase first.
- A refused layer (`begin` null after its message: locked, hidden, alpha-locked pixels) shows the
  outline alone. The commit asks again, and a refused commit keeps the outline pending.
- "Editable (own layer)", "New layer each time" and joining are ignored. A tap opens no shape.
  "Turn into path" and the object items are not offered. The layers panel's "Edit shape"
  (`LayerOps.editShape`, through `ShapeTool.editLayer`) turns the mode off first. Nothing turns
  into pixels silently (I20): a text layer takes v1.8's rasterize with its toast, inside the
  erase step.
- On a vector or legacy shape layer (`VectorEraserStrip.erasesObjects`), row 1 ends with
  `VectorEraseChooser.modeCell` (`StripKeysV19.ERASE_MODE_CELL`, 56 dp, rank 0). Its Turn into
  pixels asks through `ErasePixelsDialog` first, and the session follows the layer after.
- Deviation from the A5 block's "must leave room for it in row 1": row 1's five cells (type 56,
  style 56, width 64, Points 72, the mode cell 56) take 368 dp with their gaps and the More toggle,
  over the 364 dp of a 392 dp screen. While the cell shows, "Points" therefore leads the More row
  instead (`ShapeStripItems.items(modeCell)`). Row 1 is then type, style, width and the cell,
  292 dp with the toggle. At 360 dp with the VECTOR cell the width (AUTO 1) goes to the More row,
  as before.
- The fullest erasing strip is Points mode on a shape with copies, a smooth point with handles and
  a corner selected, the bevel last. It keeps three More lines at 360 dp with and without the VECTOR
  cell and at 392 dp, the cell in row 1 at each (`ShapeStripInventoryTest`).

Known limits: with a fill-only style "Stroke with" is hidden, so erase mode is reached through
slot 1 only; arrow heads add no fill to an erase footprint (`ShapeOutlines.brushOutline`); a
layer refused when the outline began (locked, hidden) is asked again only at the commit, so an
outline whose layer was unlocked meanwhile erases nothing live until ✓; a pinch dropped as a
no-op while the in-tool history is full has already lost its oldest entry; a legacy shape layer
outlined with the brush converts with seed 0 when a shape joins it (`LayerConvert`).

Tests: `v19/a9/` (`ShapePerEditUndoTest`, `ShapeTypeFlipHistoryTest`, `ShapeBevelRobolectricTest`,
`ShapeJoinLayerTest`, `ShapeArrangeDuplicateTest`, `ShapeMoreRowHiddenWithBarsTest`,
`ShapeSaveReopenTest`, `ShapeEraseRobolectricTest` (raster, vector, legacy shape and text layers,
Turn into pixels with an outline pending, overlapping mirror copies),
`ShapeStripInventoryTest`, Timing: `ShapeBudgetsTimingTest`). UI tests at 392 and 360 dp, font
scales 1.3 and 2.0 (3 UI classes): `qa19/Qa19ShapeUndoUiTest`, `Qa19ShapeBevelUiTest`,
`Qa19ShapeEraseUiTest`. Compat rows 10 (joined shapes, bevelled shape points) and 12 (R17 edits) of
`Qa19CompatBackFromV18RobolectricTest` (fixtures `shapes-joined`, `bevel-shapes`, `shape-r17`):
v1.8 reads them strictly, saves the shape layers back byte for byte, and reads every shape
object's field as v1.9 does.

<!-- /v19-A9 -->

### Filter session history (A2)

<!-- v19-A2 -->

R8 and R10. Files: `filters/FilterSession.kt` (outside A7b's `apply` region),
`filters/FilterSessionMath.kt`, `filters/art/CaBlend.kt` (new), `filters/art/ChromaticAberration.kt`,
`ui/filters/**` (`FilterUi`, `FilterParamControls`, `ChoiceDropdown` (new), `FringeControl`, the
curve and gradient editors), `ui/common/NumberControls.kt`, `tools/mask/AdjustmentEdit.kt`,
`ui/mask/AdjustmentSheet.kt`. Labels: `FilterLabels19` (`RESET_ONE`, `CHANGE_STEP`,
`SESSION_AT_START`); tag `V19Tags.FILTER_RESET_PREFIX` + the parameter key.

**The session's steps.** `FilterSession.history: ToolHistory<FilterValues>` starts at the opening
values (cap 100, I22). The session is `controller.inToolHistory` from `start()` until it closes,
so the hotbar's Undo and Redo and the two- and three-finger taps walk its steps first
(`HistoryLabels` reads "Undo: <control label>"). Labels live beside the history
(`undoLabels`, `redoLabels`), trimmed with it. Nothing is persisted; ✓ records one document step
exactly as in v1.8 and drops the session's steps; ✕ drops them too.
- A step is a *finished* change: `commitStep(key)` from the control (a slider released, a typed
  value, a chip, switch, dropdown row, colour, seed, curve point or gradient stop dragged, a point
  dragged on the canvas or typed, a text field left or its dialog confirmed, a fringe preset or
  swatch). `update(key, value)` alone is live. A change never finished (text typed without leaving
  the field, a run of − / + presses) becomes its own step when another control changes, when the
  panel is undone or redone, or before ✓ (`settle()`; `pendingParam()` names it, so Undo of a
  pending change reads "Undo: <its label>"). Equal values never make a step
  (`FilterSessionMath.sameValue(s)`: arrays by content, numbers by value).
- At the opening state `undoStep()` returns false and the controller cancels the panel, as in v1.8;
  landing there shows "Back at the start: Undo again closes the effect" once. A new step drops the
  redo steps; Redo while a change is pending records it and redoes nothing. While the apply runs
  (`isApplying`) `canUndo`, `canRedo` and the labels are off, so the labels read "Filter cancelled"
  and "Finish the filter first": Undo then stops the apply and closes the panel, as in v1.8.
- A history tap whose first finger landed in the panel (`EditorHistoryTaps`' light mark):
  `historyTapGuard` sets `heldByTap` when the editor's root consumes the claimed gesture, so the
  control's own end of that gesture (a slider's `onValueChangeFinished`, a − / + release) is not a
  step; the mark puts the values back before the undo.
- "Reset to defaults" is one step `RESET_ALL`; a control's reset (`resetParam`) is one step
  "Reset <label>". `sessionDefault(p)` is the drawing colour for a drawing-colour parameter.

**Reset icons.** Every control's label row ends with `ResetSlot` (`ResetValueButton`: 24 dp icon,
40 dp target, `ResetGap` = 8 dp after the value, description "Reset <label>"), shown only while
`ParamHost.isDefault(p)` is false (fringes compare as drawn, a v1.7 "order" included).
`LabeledSlider(onReset, resetTag)` carries it for sliders; other callers pass nothing and are
unchanged. The curve editor's own "Reset" button gave way to the label row's icon. A row that
can hold the icon is always at least `MinTouchTarget` (40 dp) tall (`LabelRow`, the point row; the
slider, switch, colour, seed, text and dropdown rows already are), so the icon showing or going
never moves what is below: a curve point added at a touch's DOWN or a gradient stop dragged stays
under the finger (A2 review; `Qa19FilterUndo` checks the fringe chips stay put).

**Adjustment sheets.** `ParamHost` is shared: `adjustmentParamHost` (`AdjustmentSheet.kt`) drives the
Adjust sheet's controls through `AdjustmentEdit`. `changing(label)` marks the step's name (a pending
change of another control is pushed first), `finish(label)` pushes "Change <label>" at once (so the
next Undo's toast names it; a deferred push would be named after the previous step), and `record`
pushes a reset ("Reset <label>"), a new effect or "Reset the effect" (v1.8's "Edit adjustment").
A run of − / + presses stays one pending step until another control, Close, an Undo or a reset
(`resetParam`, `resetAmount`; that control's own too) flushes it.
Amount (the layer's opacity) is a control like the others (`AMOUNT_KEY`).

**CA blend (R10).** Both Chromatic aberration filters end with `CaBlend.modeParam` ("Blend mode", the
17 `LayerBlendMode` labels by reference, default Normal) and `CaBlend.opacityParam` ("Opacity",
0-100 %, default 100). `FilterParamControl` shows a `Choice` that `CaBlend.isModeChoice` accepts as
`ChoiceDropdown` (one 44 dp row; the modes are menu rows shown only while it is open). `apply` is
`CaBlend.over(src, aberrate(src), values)`: Normal at 100 % (also values without the two keys)
returns the aberrated picture untouched, so v1.8's output is byte-identical (`FringeGoldenTest`);
otherwise one pass per pixel, the mode resolved once per pass. The twelve modes `DrawBlend` has are
`DrawBlend.composite` itself; Exclusion and the W3C non-separable modes (Lum, ClipColor, SetLum,
Sat, SetSat as one scale) are `CaBlend`'s own, in non-premultiplied floats, source-over where the
backdrop is. No platform blend mode (fact V8). Measured on the JVM proxy (`CaBlendPerfTest`,
192 x 144, one thread): the blend pass about 0.23 x the CA pass with Multiply (budget 0.4) and
about 0.30 x with Color (0.6); the whole filter about 1.3 x v1.8 with Multiply at 60 % (1.4) and
about 1.5 x with Color (1.6); a checkpoint under 0.001 ms (0.1). The non-separable modes take
channel minima and maxima with `Math.min` / `Math.max` (branch-free; one instruction on ARM64):
with comparisons, the random channels of the test picture mispredicted and the Color pass cost
0.56 x.

**Tests.** `v19/a2/`: `FilterSessionHistoryTest`, `AllFiltersUndoTest` (every registered filter, every
parameter; an exhaustive `when` over `FilterParam`), `FilterResetPerControlTest`,
`AdjustmentPerChangeStepTest`, `ChromaticAberrationBlendTest` (an independent double-precision W3C
reference, within 1/255), `FilterPanelLabelsTest` (a reset meets another text only where its label
already did), `CaBlendPerfTest` (Timing); `qa19/Qa19FilterUndoUiTest`
(392 dp) and `Qa19FilterUndoNarrowUiTest` (360 dp), at font scales 1, 1.3 and 2.0, through
`Qa19FilterUndo` (touches found by label or tag; the overflow check skips only v1.8 texts that end
in "…" on purpose: the panel title, its adjustment note, the Masks strip chips, the slider rows).

<!-- /v19-A2 -->

### Several objects per layer and merges (A4)

<!-- v19-A4 -->

R19 ("when you merge either a shape layer with another shape layer, a text layer with another
text layer or a curve layer with another curve layer they dont become rasterized", and the arrange
options on them) and R14 ("a button to duplicate the selection in the same layer").

- **Files.** `engine/LayerMerge.kt` (the merge kinds, the raster reasons, the fold, "Keep
  editable"), `ui/layers/LayerOps.kt` (`mergeDown(c, layer, ask, keepEditable)`,
  `mergeAsPixels`), `ui/layers/MergeDialog.kt` (`MergeIntoPixelsDialog`, tag
  `V19Tags.MERGE_DIALOG`), `ui/layers/LayersPanel.kt` (the dialog's saveable state),
  `ui/layers/LayerRow.kt` (the derived shape badge), `tools/select/SelectionDuplicate.kt`,
  `ui/editor/SelectionBar.kt`, `vector/select/ObjectActions.kt` (`arrange`'s body).
  `EditorController.mergeDown` stays a one-line delegate (region `v19-A4 mergeDown`), and region
  `v19-A4 mergeFolder` asks `LayerMerge.folderData` for the data its merged layer keeps; the
  other A4 regions are unchanged. `LayerConvert.shapeToVector` (F1b) converts a legacy shape
  layer on the way.
- **Merge kinds** (`LayerMerge.kindOf(upper, lower)`, the pair "Merge down" works on: `lower`
  right below `upper`):
  - `TEXT_ITEMS`: two text layers. The texts of the upper layer join the lower layer's on top
    (`TextCodec.encodeAll(below + above)`, ids kept or assigned); the lower layer keeps its name
    and opacity. The conditions, in order (the first that fails is the reason): no mask on either
    (`WHY_MASK`); both Normal (`WHY_BLEND`); neither clipping (`WHY_CLIP`); no live array
    (`WHY_COPIES`); no threaded or wrapped text (`WHY_FRAMES`, I24); at most `TextCodec.MAX_ITEMS`
    texts in all (`WHY_TOO_MANY`); the same opacity (`WHY_OPACITY`, added by the lead at the
    merge); under 100 % no text of one layer meeting one of the other, by rounded paint bounds
    (`WHY_OVERLAP`).
  - `VECTOR_OBJECTS`: two shape or vector layers (v1.8's vector-onto-vector case included). The
    upper layer's objects join the lower layer's on top with new ids (`VectorContent.plus`); a
    legacy shape layer becomes a vector layer of one `VShape`. The conditions: no mask; the upper
    one Normal and not clipping (the lower layer's blend and clipping stay the merged layer's, as
    in v1.8); no live array; no adjustment layer (`WHY_KINDS`); each layer's opacity folds
    exactly (below), else `WHY_OVERLAP`.
  - `RASTER`: anything else, v1.8's flatten through a temporary two-layer document. A text layer
    with a shape or vector layer is `WHY_KINDS`.
  - Every kind is ONE step "Merge down" that undo restores whole (data, pixels, props, the
    removed layer). No merge draws the paper (I21, `LayerMergePaperTest`).
  - The kept types are not rendered anew: the upper layer's pixels are drawn over the lower
    layer's, piece by piece (`LayerMerge.disjoint`, the disjoint rects of a `Region`): each
    text's own paint bounds, each object's (up to `MAX_PARTS` = 64 objects; more, or a legacy
    shape layer, take one bounding box or the whole layer). Only the undo tiles the pieces meet
    are saved, so two texts in opposite corners of a PHONE canvas touch two places, not 2.6 Mpx.
- **The opacity fold (D26).** A shape or vector layer under 100 % folds its opacity into each
  object's own opacity, and the merged layer is at 100 %. That is exact when no two of its objects
  share a pixel (their paint bounds rounded out are pairwise disjoint: a sweep by left edge), so
  that is the condition (`foldsExactly`). The merged pixels are not rendered anew: the lower
  layer's are scaled by its opacity within its objects' paint bounds (DST_IN), and the upper
  layer's are drawn over them at its opacity. `MergeExactTest` compares the picture before and
  after within 1/255, and within 3/255 where a fold is involved (anti-aliased edges round twice).
- **The dialog.** `LayerMerge.rasterReason(upper, lower)` is non-null only for two data layers
  (text, shape, vector) that would become pixels. Raster, adjustment, folder and non-adjacent
  pairs, kept types, and data that does not decode (merged as pixels, as in v1.8) give null.
  `LayerOps.mergeDown` with `ask` (the layers panel's Merge) hands the reason to the panel,
  which shows `MergeIntoPixelsDialog`:
  - `LayerLabels19.MERGE_TITLE` and the body with the reason;
  - `LayerLabels19.MERGE_CONFIRM`: `mergeAsPixels`, then the toast `MERGED_AS_PIXELS` with the
    reason;
  - "Cancel": no step;
  - only for `WHY_OVERLAP`, `LayerLabels19.KEEP_EDITABLE` with its note
    (`LayerMerge.mergeDownEditable`): the type is kept although see-through overlaps change a
    little; the objects are rendered anew wherever either layer painted (I1).
  - The panel keeps the open dialog (`mergeAskId`, `mergeAskWhy`) across a rotation. A merge
    onto a raster layer never asks.
- **The derived shape badge.** `LayerRowModel.shapesOnly`: a vector layer whose objects are all
  `VShape` (a merge of shape layers) shows the shape badge (`showsShapeBadge`), like a legacy
  shape layer. Nothing is stored; an object of another kind makes it a vector layer again.
- **Duplicate on the selection bar** (R14). `LayerLabels19.DUPLICATE` comes after Save, the
  bar's eleventh item. It shows only where `SelectionDuplicate.kindOf(c)` is non-null: there is a
  selection, and the active layer is not a folder, an adjustment layer, a live array, a mask
  being edited, a legacy shape layer, or a text layer while `TextItemOps.available` is false (A3).
  It is one step `LayerLabels19.DUPLICATE_STEP` (`groupUndo`); the offset is
  `ObjectActions.duplicateOffset` (16 px at 100 %).
  - Pixels: at the areas' merge this was a stand-in (the selected pixels drawn again 16 px right
    and down, the selection moved by `SelectionDuplicate.shifted`). Since Gate 2 (product P3) the
    selected pixels (`SelectionEdits.drawSelected`, soft edges kept) float as a copy in Transform
    at the same spot of the same layer (`TransformTool.startInLayerCopy`, a placement `Session`
    with `inLayer = true`: the source is never cleared, a cancel never removes the layer, and the
    commit never folds with an add-layer step). The check mark lands it as the one step
    `DUPLICATE_STEP` (an alpha lock keeps it inside the painted pixels) with the selection moved
    along; the cross, Delete or Undo while it floats leave the layer, the selection and the
    history as they were. A tool switch or closing the editor lands it as that step
    (`onDeactivate`), and Transform stays the tool after the check mark or the cross. Applying an
    unmoved copy is a step too and composites the copy over itself (soft pixels gain alpha). The
    copy bitmap is the selection's bounds, never the document; one that does not fit shows
    `LayerLabels19.NO_MEMORY` and records nothing. While it floats the selection bar hides
    (every placement has user changes) and the Transform strip shows v1.8's "Delete picture".
    `SelectionDuplicate.offset` now applies to objects and texts only; `shifted` and its test stay
    unused by product code.
  - Objects: the objects the selection touches (`c.vectors.touching`) are copied right above the
    topmost of them (`ObjectEdits.duplicate`) and selected, with the toast `DUPLICATED`. When the
    selection touches none, a toast says so (`LayerLabels19.NOTHING_TO_DUPLICATE`, with the
    layer's name) and there is no step.
  - Texts: the texts that meet the selection, copied with the same offset through
    `TextItemOps.duplicateItems` (A3 made it available: `available` is true since A3's merge), with
    the toast `DUPLICATED`; `CrossTextDuplicateTest` (Gate 2) pins it on a merged layer of several
    texts against the Text More row's Duplicate.
  - The item grows to its one-line label at any font size (`BarItem(fit = true)`).
  - Slid to its end, the bar must start with a whole item at least the cue's 12 dp in (v1.8
    polish). With eleven items a 392 dp phone started with "Deselect" under the start cue, so the
    row's end padding (`selectionBarEndPadding`) grows where needed: the item before it then
    shows only under the cue.
- **Bring forward and Send backward** (R19). `ObjectActions.arrange` (signature frozen) sends
  Forward and Backward through `ArrangeOrder.step` over the objects' paint bounds. Each selected
  object passes the next object that overlaps it, or takes one plain step when none does, so
  "Already in front" and "Already at the back" come only at the top or the bottom. Front and Back
  keep v1.5's `ObjectEdits.arrange`. Each is one step; A9's shape More row calls the same entry.
- **Merge folder keeps the type** (R19, step 8). `EditorController.mergeFolder` still makes the
  merged layer from the folder's composite (`FolderComposite.renderBlock`), with the folder's
  name and props as in v1.8. `LayerMerge.folderData(folder)` adds the data when that composite is
  exactly the rendering of it. The folder holds no folder, and its shown children are all Normal,
  without a mask, clipping, live array or adjustment. A hidden child is left out, as the
  composite leaves it out. Then:
  - all text layers at 100 %, no threaded or wrapped text, at most `TextCodec.MAX_ITEMS` texts:
    one text layer of their texts, bottom first;
  - all shape or vector layers whose opacity folds exactly (`foldsExactly`): one vector layer of
    their objects, bottom first, each layer's opacity folded in (`faded`), a shape converted;
  - anything else: one raster layer, as in v1.8, without the R19 merge dialog (v1.8's
    pass-through question in `LayerOps.mergeFolder` stays).
  - Either way it is one step `FolderLabels.MERGE` (`MergeFolderKeepsTypeTest`).
- **Compat** (5.2 row 10). v1.8 reads and edits the fixtures `layers-merged` (a vector merge
  with a folded 50 % layer, and two shape layers merged into a vector layer of shapes) and
  `duplicates` (a raster and a vector Duplicate) as they are: existing kinds and fields only. A
  text merge writes row 5's `more` (A3's row).
- **Tests.**
  - `v19/a4`: `MergeKeepsTypeTest`, `MergeExactTest`, `MergeRasterReasonTest`,
    `MergeConfirmDialogTest` (UI), `LayerMergePaperTest`, `ShapeBadgeTest`,
    `SelectionDuplicateRobolectricTest`, `ArrangeOneStepTest`, `MergeFolderKeepsTypeTest`,
    `MergeUndoReopenTest` (every merge and Duplicate kind: undo and redo restore pixels, data,
    props, the layer list and the selection byte for byte; the results reopen as saved), with
    `A4Kit`.
  - `qa19`: `Qa19MergeUiTest` and `Qa19DuplicateUiTest` (392 and 360 dp, font scales 1, 1.3 and
    2.0, I27; the eight v1.6 items keep their x positions, "More" whole at 392 dp).
  - Forced updates: `LayerMergeExtractionTest`, `VectorLayerOpsRobolectricTest`,
    `ObjectActionsRobolectricTest` (Forward's plain step), `Qa18PolishChromeSelectionBarTest`
    (eleven items; the stroke waits for the tool menu to close, and its one retry closes a
    menu still open and picks the marquee again when another tool took the stroke); and, by the
    lead at the merge, `qa18/ChromeLabelsTest` (the selection bar's list ends with Duplicate).

<!-- /v19-A4 -->

### Text (A3)

<!-- v19-A3 -->
All 13 steps are built, including the cuttable steps 12 (vertical justify) and 13 (tap the guide
to insert a point).

- **Placeholder (R6).** `PlaceholderAmount.WORDS` ("3 words") is first in `CHIP_ORDER`; the
  amount chips wrap in a `FlowRow` (`TextFlowChips.kt`).
- **Justify (R4).** `Justify.kt` spreads the lines that `WrapLayout` broke and never changes a
  break, so wrap, frame and overset passes are untouched. `TextJustify.ON` spreads every line
  except a paragraph's last line, which keeps `align` ("Last line:" in `TextAlignSection.kt`).
  `ALL` spreads that line too. The space goes into word gaps, or between clusters for CJK lines.
  Export writes a justified line as one run per word (`TextExport`, `ExportSceneBuilder`).
  Vertical text (step 12) justifies its columns in `VerticalTextLayout.layout(justify)`, with the
  same rules: no break changes, and `ON` spares the last column of each line. A spread column
  starts at the top and ends at the block height (the box height, or the tallest column for
  point text). The space goes into word gaps after the first letter. Opening spaces stay an
  indent, and trailing spaces hang. A column without gaps spreads between cells when it holds
  CJK letters, or under `ALL`; a one-word Latin column keeps `align` under `ON`. The sheet shows
  "Justify columns", "Justify the last column too" and the caption "Last column:"
  (`TextAlignSection`). Vertical text exports as outlines from the same glyphs.
- **Kerning (R5).** `KerningModes.kt` turns a mode into extra advances on top of the font's
  kerning, as the v1.7 manual kerns do (`TextKerns.advancesPx`). Every route that draws kerned
  text therefore draws the modes, and a mode that moves nothing keeps v1.8's pixels. Auto is the
  font's pairs. Off turns them off and is synced with the switch. Metrics keeps pairs only inside
  Latin, Greek or Cyrillic runs (`ScriptRuns`). Optical (`OpticalKerning`) sets each pair from
  banded letter profiles to the font's "nn" gap and takes back the font's own pair. Its profiles
  live in a governed cache of about 850 KB.
- **Lists (R7).** `ListMarkers.kt` gives each non-blank paragraph of horizontal text a bullet or
  a number (`format`). Numbering counts per text: a story counts over its frames, and two texts
  of one layer never continue each other (C22). Every line keeps the marker column's indent, for
  every alignment. The marker size, weight and font come from `TextListSpec`
  (`TextListSection.kt`: No list / Bullets / Numbering, then "List settings"). Vertical text and
  text on a path draw no list and lose it (`ListMarkers.applies`); the sheet says why. SVG writes
  the markers as separate `<text>`.
- **Several texts per layer (R14).** With a text layer active, a tap on empty canvas places the
  new text in that layer (`TextTool.joinTarget`). Exceptions: "New layer each time" is on, a
  selection is active, or the layer cannot join (a linked frame, a wrap text, a live array, mask
  editing, or `TextCodec.MAX_ITEMS` texts, which toasts `LAYER_FULL`). A tap edits one text
  (`editLayer` with its index); a second tap cycles to the text under it (`cycleBelow`). While a
  text is open, the layer previews in place (`TextTool.LayerPreview`). Each tile draws the open
  text and only the other texts whose ink meets that tile. ✓ re-renders only where the open text
  was and is. `TextLayerDraw` draws neighbours in picture order. A pixel edit of a layer of
  several texts toasts `RASTERIZED_TEXTS` with the count (C13, region `v19-A3 rasterizeMessage`).
  A text frame links only a layer of one text. For a layer of several, `TextFrameTool.join`
  refuses with `ALONE_ONLY`. A text among others cannot start to wrap around a picture
  (`WRAP_ALONE_ONLY`; one that already wraps can only turn it off).
- **Text items (R14).** `TextItemOps.duplicateItems(c, layer, area, ids)` returns one
  `UndoAction` for A4's Duplicate; the arrange items use `ArrangeOrder.step`. Duplicate and
  Arrange commit the open text's pending changes first. Duplicate of the open text is refused,
  before anything is committed, in a full layer (`LAYER_FULL_NO_COPY`: a copy never starts a new
  layer) and for a text that wraps around a picture (`WRAP_ALONE_ONLY`). In the Text More row,
  "New layer each time" (no text open) or Duplicate, Bring forward and Send backward (a text
  open) stand before Snap; Snap, then Numbers, end the row as in v1.8. The v1.8 pins in
  `Qa18PolishChromeVerifyTest` require this order. `StripKeysV19`'s ranks (50-98: after v1.8's
  More items) would put the v1.9 items after Snap, as A9's Shape More row has them. The lead
  kept this order at the merge; Gate 2's strip inventory tests, with every area merged, settle
  it against the ranks. The More row leaves out only what a bar
  beside the strip shows itself (I10). While the object bar shows, Duplicate, Bring forward and
  Send backward leave it. While the selection bar shows a Duplicate (a selection that
  `SelectionDuplicate.kindOf` can copy), only Duplicate leaves. The paste bar (a clipboard, no
  selection) carries none of them, so they stay. `TextStripFacts.of` reads which bar shows from
  the controller under `StripContext.selectionBarShown`. A9's `ShapeObjectItems` still hides its
  items under any bar, the paste bar included (design 2.3.5's wording); the lead left the same
  narrowing there to Gate 2's I10 cross test. The strip is a pure `textSpec(tool, TextStripFacts)`, and the composable
  only reads the facts.
- **Merged texts and the label scan (step 11).** `MergedNumberedTextsTest` (C22, after A4's merge):
  "Merge down" and "Merge folder" keep each text's own numbers and pixels (I1). A text edited
  after the merge renumbers only itself. A frame of a story merges as pixels (`WHY_FRAMES`).
  `TextStripLabelScanTest` checks the Text strip in all 300 states (no bar, the selection bar,
  the object bar). Each state keeps `StripInventory.rules` at 360 and 392 dp. No label repeats
  or holds another, within the strip and hint or against the bars, the node bar and the sheets'
  labels. One configuration is left out, and the test pins it: a two-line hint at 360 dp with
  the VECTOR cell, where an open text of a layer of several would take 388 dp (344 dp beside
  the selection bar). It cannot occur, because that text's text layer is active, so
  `isVectorMode` is false and the cell does not show.
- **Path nodes (R12).** `TextPathSpec.nodes` (frozen) holds a `VSpline`; `TextPathNodes` converts
  a guide. `asPath` gives a curve's 4 control points, a circle's 8 nodes, or a rectangle's exact
  arcs. `asPolyline` gives `CUBIC_POLYLINE_SEGMENTS + 1` points. The edits are
  `withAddedPoints`, `withPointAfter`, `withoutPoint` (at least 2 stay) and `withSharpToggled`,
  all capped at `TextPathSpec.MAX_NODES`. `TextPathGeometry` and `TextOnPath` lay text out along
  the nodes. A closed guide's text starts at its point nearest node 0 and stands by its winding.
  v1.8 reads the CURVE or LINE fallback (compat row 4).
- **Node editor (R12, I22).** The path sheet's "Add points", "Edit as path" and "Edit as
  polyline" (`TextPathNodeEntry`) close the sheet and call `TextTool.startNodes`. Node editing
  then starts with the `NODES_STARTED` toast, and `TextPathNodeEditor` becomes
  `EditorController.inToolHistory`. Each edit is one in-tool step of a
  `ToolHistory<TextPathSpec>`: Add point, Delete point, Sharp corner, or a node dragged (picked
  within 24 dp; the step `MOVE_POINT`). `TextPathNodeBar` sits where the selection bar does: 44 dp high, tag
  `V19Tags.TEXT_PATH_NODES`, placed by `TextPathNodeBar.topDp` from the `IbisDims`. Its ✓
  (`DONE_POINTS`) and the floating ✓ return to the sheet; the next ✓ commits one document step.
  While nodes are edited, two fingers zoom the view and node drags do not snap. A tap that
  picks no node (step 13) adds one where it meets the guide within 24 dp, when the finger
  lifts (`TextPathNodeEditor.tapGuide`, `TextPathNodes.pointOnGuide`). This is one Add point
  step, and the new node is selected. A drag adds nothing, and neither does the first finger of
  a two-finger gesture. The node goes on the line between two nodes, strictly between them, and
  the closing segment of a closed guide counts. On a polyline it lands exactly at the tap. For a
  tap on the curve of path nodes, it lands at the line's point nearest the tapped curve point,
  as "Add point" places nodes on the lines. At 200 points the tap toasts `NODES_MAX`.
- **The lead at the merge.** `LAYER_FULL_NO_COPY`, `WRAP_ALONE_ONLY`, `ALONE_ONLY` and
  `MOVE_POINT` are `TextLabels19` entries; they replaced A3's stand-ins in `TextTool`,
  `TextFrameTool` and `TextPathNodeEditor`, and `LabelsV19Test` checks them on the Text strip,
  the Text frames tool and the node bar. `TextItem.sanitized`'s KDoc says vertical columns
  justify. `NODES_HINT` does not mention the tap on the guide: the caption has no line limit
  and covers the canvas under the node bar, so a longer hint waits for the device check.
<!-- /v19-A3 -->

### Drawing and mask preview (A6)

<!-- v19-A6 -->

A6 makes drawing, erasing and the mask tools cheaper per frame without changing a committed pixel
(I25) or v1.8's behaviour under Robolectric's EXACT policy (I8). Steps 1-10 of design 3.2 A6; P4,
P3 and R11a are built and kept off (CUT (proxy)), each behind one constant.

**The lift (P1, I25).** A brush, eraser, smudge, blur or watercolor lift redraws only the parts of the canvas
in the 256 px undo tiles its dabs touched (`EditorController.commitStrokeEdit` in region `v19-A6 commitEdit`,
`EditInvalidation.tilesAfterCommit`), not the stroke's bounding box; other `commitEdit` callers
keep v1.8's rect. Where the canvas already shows the stroke exactly as it commits (an RGB
document, no recorder-gated tool such as the clone stamp, not a path stroke, the stroke's
override still installed; smudge, blur and watercolor without a monochrome override and not on an
adjustment layer's mask) the lift passes `displayCurrent = true` and redraws only the dabs the
canvas has not shown yet and the retapered end (`BrushTool.lastLiftShowedCommit` reports the
decision). A watercolor stroke on an adjustment layer's mask redraws its tiles as before: no
override draws that mask while it paints, so `AdjustmentStage` keeps the effect clipped to the
mask's coverage from before the stroke until the commit marks the layer changed (or turns its spec
into a painted mask). `v19/a6/PreviewEqualsCommitParityTest` pins the premise: after every lift
the display equals a full redraw of the committed document, bit for bit, across blend modes, alpha
lock, selection, grain, taper, layer masks (brush, eraser, smudge), clipping, adjustment layers
(above the stroke, and their painted and editable masks), folders, symmetry, smudge, blur and
watercolor; elsewhere the lift redraws its tiles as before.

**Per-frame allocations (P2).** `DisplayTiles` keeps each tile's Canvas and `CompositeTarget`
with its bitmap, counts its dirty tiles (`hasDirty` is O(1); `dirty` holds null for a clean tile,
as in v1.8, since `VectorPerfHarness` reads it, and each tile's own reused rect otherwise) and
reuses its rects, order arrays and `TileUpdate` values; `Compositor` caches its blend Paint per (mode,
alpha) on the main thread; `DirectStroke` (smudge, blur, watercolor) recycles its dabs. The brush
and the raster Eraser (`BufferStroke`) keep theirs, which the lift reads for its tiles, so their
frames allocate only the new dabs.

**Mask drags (R13).** Under the live policy (`LiveAdjust.Policy.LIVE`, the app's; tests opt in
with `liveAdjust.policy` and `clock`) the Masks tool records a drag's newest spec and renders its
preview once per frame: `CanvasView.onDraw` first calls `BeforeFrame.beforeFrame()` on the active
tool. While the finger moves the preview renders at the interaction scale
(`MaskTool.interactionScaleFor`: `LiveAdjust.proxyScaleFor(zoom)`, at least 1/8 and at most v1.8's
`MaskPreview.previewScale`), so it lines up with the live proxies one to one; after 150 ms still
(`LiveAdjust.touch(..., resting = true)` keeps the proxies refining) and on release it renders at
v1.8's scale. With "Fast adjustment preview" off the drag previews at v1.8's scale, once per
frame. A Brush-mask stroke stamps only the dabs added since the last frame into the preview
(`MaskBrushRaster.DabTrail`, `StrokeTiles`: 64 px float tiles, at most 1024). A release whose
full-resolution render is estimated above `MaskTool.REFINE_MIN_MS` (24 ms) becomes a `MaskRefine`:
bands rendered on the main thread, one slice per Handler message within 6 ms of the live clock,
while the preview shows the edit; it lands as ONE step through `MaskEdits.applyWith`, equal to
v1.8's `MaskEdits.apply` (I1). It is a `DeferredStep`: another step, undo / redo, a new gesture
or leaving the tool lands it first; a layer changed in another way drops it. It holds one byte per
pixel of the changed region (at most the document); when that does not fit in memory the release
renders at once, as in v1.8. Under EXACT every
move previews at once at v1.8's scale and the release renders at once (I8).

**Memory (design 2.4.3).** Three entries in the `MemoryGovernor` registry, for A7b's policy:
- "display tiles", priority 0, one per editor (region `v19-A6 invalidate`): the bytes of the tile
  bitmaps held (`DisplayTiles.heldBytes`). At any pressure above NORMAL its trim frees them all
  (`DisplayTiles.freeBitmaps`) and invalidates the canvas, which renders them again on its next
  frame; NORMAL frees nothing. `EditorController.dispose` closes it.
- "live proxies", priority 10, while a `LiveAdjust` holds proxies (registered with its
  ComponentCallbacks2, closed with them): `LiveAdjust.proxyBytes`. Above NORMAL, proxies kept
  after a session (2 s) are freed; at LOW or worse a running session ends (its next frame renders
  exactly). MODERATE leaves a running session alone.
- "below-caches", priority 20, while a stroke's below-cache (P3 and R11a, below) holds tiles:
  `StrokeBelowCache.bytes`, at most 24 MB. Above NORMAL it is freed and the rest of the stroke
  composites as v1.8.
All hold their owner weakly (`engine/live/WeakRegistration`): an owner collected without closing
reads as 0 bytes and its entry is closed at the next registration. All trims run on the main
thread and return the bytes freed. The "Fast adjustment preview" switch reads R13's note
(`FAST_ADJUST_PREVIEW_DESCRIPTION = StabilityLabels19.FAST_ADJUST_NOTE`) in Settings and in the
Masks sheet.

**Drawing preview (P4, LA2): CUT (proxy), kept hidden.** `engine/live/StrokePreview` and
`ui/editor/DrawingPreviewSetting` ("Drawing preview": "Faster" or "Exact pixels", saved in
`AppSettings.drawPreview`, default "Faster", with `StabilityLabels19.DRAWING_PREVIEW_NOTE` under
the chips, in Settings' Display section after "Fast adjustment preview"). With "Faster" a stroke
that paints pixels the user drags (not a path, a recorded stroke or the clone stamp) asks
`LiveAdjust.beginStroke` when it starts; under the live policy it then hands each event's dirty
rect to `LiveAdjust.touchStroke` instead of invalidating it: a stroke session whose proxies
composite the whole stack (split 0; P3's below-cache serves the display tiles, not the proxies) at the proxy scale, and
`end` after the lift or the cancel refines to exact pixels. Dabs are stamped at document
resolution in both modes, so the layer, the undo step and the canvas after refinement are the
same (I1, I7); the lift redraws the stroke's tiles (`previewIsCommit` is false while live).
Zooming faster than x1.2 per second ends the session; the rest of that stroke is drawn exactly.
LA2 ships it only when "Faster" is at least 20 % cheaper per frame on A4 or PRINT at fit;
`v19/a6/DrawPreviewProxyBench` (JVM, local) measured Faster / Exact ms per drawing frame: A4 at fit
(0.39) 8.50 / 8.40, at 0.47 8.83 / 8.99; PRINT at fit (0.32) 9.09 / 9.19, at 0.41 9.83 / 9.73. The
frame is the tiles' blit and the canvas chrome; the stroke's own composite is about 2 % of it. So
`StrokePreview.SHIPPED` is false: the setting is not shown, `StrokePreview.faster` is false and
every stroke previews as in v1.8 ("Exact pixels"). Flipping `SHIPPED` ships it as built.

**Stroke below-cache (P3): CUT (proxy), kept off.** `engine/live/StrokeBelowCache` (one per
editor, `LiveAdjust.strokeBelow`) keeps, for each display tile a stroke reaches, the composite of
the layers below the stroke's layer at scale 1. The split k is the first layer of the stroke
layer's clipping group (an adjustment layer is a group of its own); in a document with folders it
is the layer itself when every folder it is in draws its children as is and it is not clipped,
otherwise there is no cache. `DisplayTiles.render` asks its `BelowRenderer` first: the tile's dirty
part is the cache copied 1:1 (SRC), then `Compositor.drawSplit(k, n)` with the stroke's override,
which equals a CLEAR and `drawDocument` bit for bit (I7). `drawSplit` accepts any group start
(`isSplitBoundary`); `drawDocument`'s own contract is unchanged (`DrawDocumentRangeTest`). The
cache is built where it is needed, in 16 px cells (a bit per cell; one build per frame, the missing
cells' bounding box). A `BelowKey` of `[0, k)` is checked at every use, and an invalidation the
stroke did not make itself (`own`) drops the cells there. It holds at most 24 MB (LRU over tiles,
the "below-caches" entry above) and goes at the lift and the cancel. Brush, smudge, blur and
watercolor strokes that are not paths, recorded strokes or the clone stamp begin it when
`StrokeBelowCache.strokesOn`. `v19/a6/StrokeBelowBench` (JVM, local; the P0 bench's boards, Hard
round 40, its 30 cm serpentine at fit, median of 9 alternating strokes) measured the display
tiles' composite per drawing frame, cached / full: PHONE (layer 10 of 20) 0.17 / 0.17 ms, PRINT
(layer 1 of 3) 0.07 / 0.06 ms, PHONE with 40 layers painting the top one 0.32 / 0.40 ms; the
whole frame 5.93 / 5.96, 9.48 / 9.24 and 6.27 / 6.86 ms. A stroke reaches new pixels every frame,
so most of each frame's part is built anyway; the composite is about 3 % of the JVM frame (the
software blit of the screen is the rest), and `brush.lift`, the row that met P3's threshold, has
had no composite in it since P1. So `FOR_STROKES` is false: every stroke composites as v1.8.
Flipping it ships P3 as built.

**Raster Eraser below-cache (R11a): CUT (proxy), kept off.** The raster Eraser's strokes (soft,
hard and pixel erasers, DST_OUT; on a layer's pixels or on a mask) begin the same cache when
`StrokeBelowCache.eraserOn`, its own switch. Unlike P3 the saving is measurable: a 90 px soft
eraser repaints each pixel over several frames, so more of each frame's part is already cached.
`StrokeBelowBench` measured the composite per erasing frame, cached / full: PHONE 0.37 / 0.50 ms
(0.74), PRINT 0.18 / 0.18 ms (one layer below), PHONE with 40 layers erasing the top one 0.43 /
0.94 ms (0.45). The whole JVM frame moves by about 6 % at best (6.30 / 6.69 ms on PHONE; its
rows are noisier than the composite's), short of the 20 % per frame that step 8 asked of P4, and
the composite's share of a phone's CPU frame (where the blit is on the GPU) is a device
measurement. So `FOR_ERASER` is false: the Eraser composites as v1.8. Flipping it ships R11a as
built.

**Guards.** `v19/a6/LiftInvalidationTest`, `PreviewEqualsCommitParityTest`,
`DisplayTilesReuseTest`, `MaskInteractionScaleTest`, `BrushMaskIncrementalTest`,
`MaskReleaseParityTest`, `GovernorRegistrationTest`, `DrawingPreviewGuardTest` (P4 off as
shipped and under EXACT; opted in, the stroke is drawn from proxies and lands bit for bit as
"Exact pixels"; the zoom guard), `qa19/Qa19DrawPreviewUiTest` and `Qa19DrawPreviewNarrowUiTest`
(hidden as shipped; available, the setting whole at 392 and 360 dp and font scales 1, 1.3, 2.0),
`DrawSplitTest` (`[0, k)` then `[k, n)` equals the full draw at every group start, flat and with
folders; a split inside a group is refused), `StrokeBelowCacheTest` (P3 turned on: the canvas
equals a fresh render at every second move and after the lift, with the cache used, across split
positions, clipping groups, blend modes, adjustment layers, masks, "Safe compositing", folders,
selection, symmetry, smudge, blur, a grayscale document, changes below during the stroke, the
memory cap and the cancel; R11a turned on: the soft, hard and pixel erasers, clipped, masked and
clip-base layers, a layer's mask, an adjustment's mask, selection, blend modes and a change below;
500 seeded random changes below and above during brush and Eraser strokes, exact after every
frame; as shipped the switches decide);
Timing (`A6Bench`, the P0 bench's way of
timing a laid-out `CanvasView`): `LiftPerfTest` (the brush lift and the raster Eraser's frame on a
512 px canvas; locally the PRINT lift within Q5's 40 ms at the T606 factor 6 and the PHONE erase
frame within 1.5x A6's checkpoint), `MaskFramePerfTest` (the mask tool's work per frame, live
at most 0.6x v1.8's, for Linear, Radial and Brush; 512 px everywhere, A4 at fit locally) and
`StrokeBelowBench` (local only: the composite and the frame with the below-cache on and off).

**At the merge (the lead).** `EditorController.dispose` closes the "display tiles" entry
(`GovernorRegistrationTest`); the weak hold stays for an editor that is never disposed. P4
(`StrokePreview.SHIPPED`), P3 (`StrokeBelowCache.FOR_STROKES`) and R11a (`FOR_ERASER`) stay off
until the device A/B of Gate 3 (D5 and D6, D8, D10: p95 frame time, at least 60 samples per side,
a release-like APK pair); the JVM numbers above cannot decide them. `WeakRegistration` stays in
`engine/live` (A7b may use it from there). `LayerToolRules` still allows the watercolor brush on
an adjustment layer's mask, whose lift redraws its tiles (above).

<!-- /v19-A6 -->

### Memory and banded save (A7b)

<!-- v19-A7b -->

R9, second half ("the app seems to be crashing often", with large artworks): the memory the
editor holds is one policy with levels, a large allocation is admitted before it starts, the
filters and the save no longer need a layer-sized Java buffer, and no wait on the main thread is
unbounded. A soak harness runs the whole loop for Q10 and Q11 every night.

- **Files.** `engine/GovernorPolicyV19.kt` (the policy, `registerWeakly`), `engine/GovernorPolicies.kt`
  (`current` is V19), `filters/FilterTiling.kt`, `storage/LayerCodec.kt` (`snapshot`, `bandRows`,
  `writeBands`), `storage/LayerEntries.kt` (`BAND_BYTES`, `writePixels`),
  `tools/transform/LayerBoundsCache.kt` (H4, below), `vector/VectorLayers.kt` (`flushPending`)
  and `array/ArrayRenders.kt` (`flush`): the bounded waits. Regions: `EditorController`
  `v19-A7b layerCap` (the undo entry, `maxLayers`, `effectiveLayerCount`), `settle` and
  `landSavedSelections` (the waits), `addMask`; `EditorSession` `saveOnLeaving`; `FilterSession`
  `apply`. Labels: `StabilityLabels19.UNDO_TRIMMED`, `NO_MEMORY_FOR`, `TOO_SLOW`. Tests:
  `v19/a7b/`.
- **Levels** (`GovernorPolicyV19`, the default `MemoryGovernor.policy` from `BrushworkApp.onCreate`).
  Entries are trimmed lowest priority first. `PRIORITY_DISPLAY` 0: "display tiles"
  (`EditorController`, closed in `dispose`), `ThumbnailCache`, `OpticalKerning`.
  `PRIORITY_PROXIES` 10: "live proxies" (`LiveAdjust`). `PRIORITY_BELOW` 20: "below-caches"
  (`StrokeBelowCache`). `PRIORITY_UNDO` 50: "undo history" (`UNDO_ENTRY`, one per editor,
  closed when the editor's scope completes: `EditorSession.close` cancels it after `dispose`).
  NORMAL and MODERATE free nothing; LOW (`TRIM_MEMORY_RUNNING_LOW`) frees the entries under 20;
  CRITICAL (`RUNNING_CRITICAL`, `COMPLETE`) frees them all. A6's entries are held weakly
  (`WeakRegistration`, `registerWeakly`): a collected owner's entry reads 0 bytes and is closed.
  `GovernorRegistrationsV19Test` pins each of A6's caches to its level and what LOW and CRITICAL
  free.
- **Undo trim** (`trimUndo`, main thread, no collection). The undo entry drops the oldest steps
  while the memory in use (Java heap plus `Debug.getNativeHeapAllocatedSize`, read once, less
  the dropped steps' `byteSize`) is at or over `HEAP_TARGET` 70 % of `maxMemory`. It keeps the
  newest `KEEP_UNDO_STEPS` = 1 and the redo steps, and nothing while `holdTrim` holds. Any drop
  shows "Memory is low: older undo steps were cleared".
- **Admit** (`admit`, any thread). A flatten, filter apply, export, merge or folder merge runs
  only when its estimate fits in the free Java heap less `ADMIT_MARGIN` 64 MB, with one
  collection before a refusal. Refused: "Not enough memory for <name>", nothing changes. The
  Java heap only: bitmaps are native since API 26, so a bitmap admit rarely refuses on the phone.
- **Filter tiling** (`FilterTiling`). A local filter (a `PixelMapper`) applies to its region (the
  selection's bounds, else the layer) in bands of `BAND_PIXELS` (1 M pixels, at most 256 rows):
  the region's result (4 bytes a pixel) plus two bands, instead of v1.8's 8 bytes a pixel of
  the layer. Neighbourhood filters (blurs, distortions) keep the whole-image path behind
  `admit`. Pixels, changed bounds and the step are byte-identical (`FilterTilingEqualsWholeTest`).
- **Banded save** (`LayerEntries.writePixels`). On main, the layer or mask is copied natively
  (`LayerCodec.snapshot`); on IO it is written a band at a time through a Java scratch of at most
  `BAND_BYTES` 4 MB, byte-identical to v1.8's file (`BandedSaveByteIdenticalTest`). Array sources
  still write from a whole buffer (`ArrayCodec`).
- **Bounded waits** (`awaitDeferredJob`, `deferredWaitLimitMs` 1.5 s). v1.8's three
  `runBlocking` waits (a vector render, an array render, a saved selection compressing) wait at
  most 1.5 s on main. Past it the work is cancelled and never lands: the layer keeps what it had,
  no step is recorded, and "<step> took too long and was cancelled" shows. An Undo or Redo that
  waited is that cancellation. Under another overlay ("Saving…", "Exporting…") the work lands on
  the spot through the synchronous path. `NoRunBlockingOnMainTest` keeps them out. A5's
  `EraseLanding` (`Cutting.flush`) still waits without a bound: deferred to Gate 2.
- **Leaving** (`EditorSession.saveOnLeaving`, Home, app switch, screen off). It returns at once;
  on the app scope it waits up to the limit for the renders and the saved selections
  (`awaitDeferredWork`), lands what is pending synchronously (`leavingDeferred`: never
  cancelled), and saves.
- **Smart guides and recycled layers** (H4, `LayerBoundsCache`). Trimming the history disposes a
  removed layer's step (`LayerTreeAction.dispose`, frozen), which recycles the layer on main
  while its scan may still read it. The worker now copies the layer and its mask on main
  (`copyPixels`), scans the copies on Default and recycles them in a `finally`. A result is kept
  only when the layer, its bitmap, its mask and its content version are the ones copied. No
  memory for a copy means nothing to snap to in that content (not retried until it changes).
  Cost: one main-thread copy per queued layer, and transiently the layer plus its mask at full
  size (`LayerBoundsCacheCopyTest`).
- **Masks in the layer cap** (`effectiveLayerCount`). A layer mask is a document-sized bitmap
  like a layer, so it counts as one, turned off or not. `addMask` refuses at the cap with the
  layer-limit message; an adjustment layer from a selection takes its two rooms at once
  (`canAddAdjustmentLayer`). The Layers panel's "n / max" counts masks too. An artwork over the
  cap still opens (`MaskCapTest`). The other ways a mask is made or copied need the room too
  (the lead at the merge): "Add mask" on a layer whose mask was turned off
  (`LayerOps.addEnabledMask`), an editable mask (`MaskEdits.addSpecMask`), a duplicated layer or
  folder (`LayerStructure`: each copied mask one more), an array (`ArraySources.hasRoom`) and an
  import (`PayloadImport.fitting`: a mask one more slot; an adjustment layer's two hold its own).
- **Soak harness** (`SoakHarnessTest`, `@Category(Timing)`; it runs only with `-PsoakOnly`, which
  sets `bw.testSet=soak`: `.github/workflows/soak.yml`, nightly and on demand; it skips itself
  elsewhere). Two artworks (phone and PRINT (3:4) proportions at 512 px) go round 3 times: open from
  the last save; 200 strokes (brush, eraser, a layer mask), LOW every 50, CRITICAL every 100 with
  Undo and Redo after it; a blur in a selection and Invert; a Magnetic lasso round (Q10: 12
  anchors, a drag, one selection step); a Color select round (Q11: Reds, fuzziness drags,
  samples, one selection step); a layer deleted with its folder just after the smart guides
  start scanning a copy of it, recycled by CRITICAL (H4: only a step with folders recycles
  what it removed; whether the scan still runs then depends on the threads: it did in 5 or 6
  of the 6 rounds in desktop runs, and `LayerBoundsCacheCopyTest` holds it running for certain); save;
  close. It asserts no crash or handled report and no scope error, layers reopen as saved, no
  live bitmap is recycled, and closed editors' caches hold 0 bytes. Once a closed editor's scope
  has ended its undo entry is gone, and the registry does not grow round by round. No
  main-thread call may reach a FREEZE report, and each tool activation's best time must be
  within 32 ms (Q11's "no frame over 32 ms at activation"; CI factor applied). It prints the longest call of each kind
  (the job's JUnit XML keeps them). On the desktop JVM a run takes about 17 s; the longest call
  is a filter start (about 180 ms), the activations take under 5 ms.

**At the merge (the lead).** Applied: the three tests that pinned the behaviour before A7b pin
A7b's (`MemoryGovernorRegistryTest.theDefaultPolicyIsV18AndTheAppRunsV19`: `MemoryGovernor`'s
own default stays v1.8's and `GovernorPolicies.current()` is V19; `VectorAsyncBurstQaTest`'s
gate opens after 1 s, within the 1.5 s wait; `GovernorRegistrationTest` lists "undo history"
last); the mask rooms above (`MaskCapTest`); compat row 13 (`row13BandedSave`, fixture
`qa19/compat/banded/`: one project saved whole and in bands of 7 rows, the same files byte for
byte); an unused `runBlocking` import out of `EditorController`. The undo entry needs nothing
from `dispose`: it closes with the editor's scope. Deferred to Gate 2: a bound on
`EraseLanding`'s wait (A5's file; what a cut that times out leaves is undecided); a timeout
count in the frozen history region, and an Undo or Redo that reports the cancellation instead
of its label (`HistoryLabels`; the toast already says it); a rework of `EditorSession.close` (a
change on close; the bounded waits cover it); a running byte total in `UndoManager` (an
optimization); `Filter.kernelRadius` tiling for the neighbourhood filters and streamed
`ArrayCodec` sources (features); the design's wording, for the lead's revision of the design
(this block says what the code does).

<!-- /v19-A7b -->

### Gate 2 (the lead: cross tests and deferred fixes)

The lead's Gate 2 (design 2.1 and 3.4) ran as five lanes, each built and reviewed on a branch of
its own from `main` at `9a213149` (the ten areas merged, CI on three shards), then merged into
`v19-gate2` with `--no-ff` in this order, each merge clean: product (`aa5908ea`; P1-P4), memory
(`dec9e513`; M1-M4), objects (`9a03e511`; X1-X4), text (`e012a951`; T1-T3, `CrossUndoSnap`) and
strip (`81e10e87`; S1-S7). The lead's own `v19G2` commits followed (the frozen note of
`Qa19Ui.kt`, `DiagnosticsSeamsTest` after P2, this section), then the merge into `main`.

**Cross tests** (`app/src/test/java/com/brushwork/paint/v19/cross/` unless named):

| Design 3.4 item | Class (tests) | What it pins |
|---|---|---|
| Erase joined shapes with the four tools in each mode | `CrossObjectsEraseJoinedShapesTest` (2) | X1: a vector layer of two shape objects erased with the outline of Curve, Polyline, Path and Shape in Object, Partial and To intersection: one step each whose undo and redo give the objects and pixels back exactly, the layer a fresh rendering of its objects (I1) and never pixels by itself (I20), saved and opened again as it was; a Partial piece cut with Shape opens in a curve tool; "Turn into pixels" after its warning is v1.8's rasterize as a step of its own, then each tool erases the pixels |
| Merge two shape layers, then a Partial cut | `CrossObjectsMergeThenCutTest` (1) | X2: two shape layers ("New layer each time") merge down from the layers panel with no dialog into one vector layer of both shapes, one step "Merge down"; Partial cuts one shape into paths, one step "Erase"; each undoes exactly |
| Shape More row's Duplicate, Bring forward, Send backward as the object bar's | `CrossObjectsShapeMoreRowTest` (1) | X3: the same data, ids, order, pixels and step labels as the object bar's buttons for the same object (A4's plain step when nothing overlaps); only what stays open differs (Shape reopens the result, the bar keeps it selected) |
| I10 with both bars | `CrossObjectsBarsUiTest` (1, UI, 392 and 360 dp); `v19/a9/ShapeMoreRowHiddenWithBarsTest.underThePasteBarAloneTheObjectItemsStay` | X4: with objects selected under Shape only the object bar offers the object items, so no label shows twice (nor with the bar's own row open); S6: under the paste bar alone they stay in the More row |
| Merge layers of several texts, then edit and arrange | `CrossTextMergeEditArrangeTest` (2) | T1: two text layers of two texts each merge (TEXT_ITEMS); an Edit text and each Bring forward / Send backward of the Text More row is one exact step; the pixels equal a fresh `TextRenderer.drawItem` of every text with no tolerance; save and reopen keep every text, its order and the pixels |
| Duplicate a text from the Text More row and the selection bar | `CrossTextDuplicateTest` (1) | T2: one exact step `LayerLabels19.DUPLICATE_STEP` each; the copy is a new editable text right above its original, offset as the object bar's, with an id of its own |
| Undo walks (I22): a filter session, text-path node edits, the bevel popup | `CrossUndoSessionsEndAsOneStepTest` (3), helper `CrossUndoSnap` | T3: while a session is open Undo and Redo walk its own steps and the document history stays; its ✓ is one document step whose undo and redo are exact, storing what the walk left (the filter's values, the nodes, the bevelled points), on new and reopened polylines and shapes |
| A ten-step filter session on PRINT at CRITICAL | `CrossMemoryFilterSessionTest` (2) | M1: a ten-step Tone session with CRITICAL trims before it, inside it and while it applies; "Not enough memory for Tone" first, then a banded apply that commits bitwise what NORMAL commits, one step that undoes and redoes exactly; the editor's governor entries gone once it closed. CI runs a 384 x 512 stand-in that crosses the same 16 bands; PRINT itself runs only where `CI` is unset |
| A paper document through thumbnail, export, merge, backup restore | `CrossPaperRoundTripTest` (1) | M2: the thumbnail and the PNG and WEBP (bit for bit) and JPEG (within 6) exports equal the flatten on the paper; SVG and PDF with Include paper carry it and import back; Merge down keeps the paper out of the pixels; the backup mirror and a restore into a fresh install keep it, and a second restore never overwrites (I23) |
| (Gate 2 fixes M3, M4) | `CrossMemoryEraseCutTimeoutTest` (2), `CrossMemoryBackupGapsTest` (2) | M3 and M4 below |
| R3's sticky row during every new strip item | `qa19/Qa19StickyRows.newItems`, sections of `Qa19StickyRowsUiTest` and `Qa19StickyRowsNarrowUiTest` | the ••• menu, the bevel popup, the erase-mode chooser, the Stroke kind and blend-mode menus close on a pick or an outside tap, the Turn into pixels dialog on Cancel, the sheets on Close; after each the row shows again, open |
| Qa19Compat with every row, LabelsV19Test, StripLayoutTest and the strip inventory at 392 / 360 dp | `qa19/Qa19CompatBackFromV18RobolectricTest`, `ui/common/LabelsV19Test`, `ui/tools/strip/StripLayoutTest`, `StripInventoryTest`, `v19/a1/CurveStripInventoryTest`, `v19/a9/ShapeStripInventoryTest` | green with every area merged; every compatibility row runs (the table in "Model and codecs" above) |
| I27 | `StripFontScaleTest` (1, UI) | at 392 and 360 dp and font scale 1.3 and 2.0: every strip state of `V18CrossTestKit.states` and the v1.9 worst cases with the More row open (and the erase-mode cell's menu and the bevel popup where they show) keep their texts inside their cells, and the erase-mode cell stays in row 1 of Curve, Polyline, Path and Shape (G5); the editor's object bar and its row, the X / Y pill, the Shape settings sheet and the bevel popup; `FitText` itself |

**Decisions of the lanes** (rules since Gate 2):
- **Supervised scopes (P2, A7a's open item).** A composable gets its coroutine scope from
  `diagnostics/rememberSupervisedScope()`: a `SupervisorJob` parented to the composition's job,
  with `ErrorReporter.handler`, the composition's dispatcher and frame clock, cancelled when the
  composable leaves. A failed job ends only itself; in a release build its error becomes a
  report and a toast instead of ending the window's Recomposer. `UnguardedLaunchGateTest` fails
  a plain `rememberCoroutineScope` outside `diagnostics/SupervisedScope.kt` (UNSUPERVISED) and
  one without the handler; `DiagnosticsSeamsTest` counts a `rememberSupervisedScope()` site as
  guarded (the lead's Gate 2 fix: after P2 its scan found 7 sites against a floor of 13). Limit:
  an error thrown in a `LaunchedEffect` body or another composition-scoped effect still goes
  through the Recomposer, as in v1.8.
- **Transform floats the raster Duplicate (P3).** In the A4 block above ("Duplicate on the
  selection bar"); `TransformTool.startInLayerCopy`. P1: the Duplicate's out-of-memory toast is
  `LayerLabels19.NO_MEMORY`.
- **EraseLanding's timeout (M3, the lead's ruling).** `Cutting.flush` waits through
  `EditorController.awaitDeferredJob` (`deferredWaitLimitMs`: 1.5 s, 0 while leaving). On a
  timeout a cut that need not land is cancelled: the layer keeps its data and pixels, no step is
  made, the tool hears `done(false)` and `onDeferredWaitTimedOut` shows `TOO_SLOW` with the
  tool's label. A cut that must land (while `leavingDeferred`, or `mustLandDeferredWork()`,
  except under the cut's own busy overlay, which shows after `CUT_BUSY_AFTER_MS`, as the
  `VectorLayers.BUSY_LABEL` exclusion) is cancelled in the background and cut synchronously in
  the step, as before. A pending region cut arms the wait guard (`refreshDeferredWaitGuard`
  counts `VectorDrawState.hasPendingCut`). The cut is cooperative: `EraseCut.cutAll(targets,
  eraser, active)` asks `active` before each object and copy, so a cancelled cut stops at its
  next object (one object's PathOps runs to its end).
- **A8's gaps (M4).** (a) `BackupService.start` does no IO on the calling thread: it bumps the
  generation, resets the state to a new process's and reads the state file, the folder and the
  grants on the `brushwork-backup` worker; saves and deletes during the reads are caught up by
  the start (a save keeps `hasArtworks` true). On a cold start the no-folder notice shows a
  moment after the gallery's first frame. `startWith(app)` stays synchronous for tests. (b)
  `EditorPrefs` follows "brushwork_settings" through a listener, so a settings restore reaches an
  open editor at once; a followed Safe compositing calls `onSafeCompositingFollowed` and
  `EditorScreen` redraws (`controller.invalidateDoc(null)`). (c) "newCanvas.customColor" (in
  "UiPrefs versus AppSettings" above).
- **Objects.** To intersection over shapes alone erases nothing and makes no step (shapes are
  only boundaries, v1.8's rule); "Turn into pixels" is two steps ("Rasterize vector layer" with
  its toast, then the erase); a Partial piece of a shape is an editable path; two shape layers
  merge down with no dialog (`LayerMerge.rasterReason` is null for VECTOR_OBJECTS); a Duplicate
  lands right above the duplicated object; two shapes drawn alike keep different
  `VShape.seed` values.
- **Texts.** `TextTool.duplicateOpen()` and `arrangeOpen()` leave the copy or the moved text open
  (tests call `TextTool.discard()` before a snapshot or an Undo); a filter on a layer of several
  texts is one step that holds the pixel edit and the rasterize data action
  (`TextLabels19.RASTERIZED_TEXTS`); "Done editing points" (`TextTool.doneNodes`) records no
  step, only the text's commit "Edit text" does.
- **Large fonts (I27, S1-S4).** A strip, bar, pill, field or gallery-card text that may meet a
  large font scale uses `ui/common/FitText`, not `Text`: at font scale 1 or below it is `Text`
  with the same arguments (v1.8's layout); above, `Text` at its size when that fits, else the
  largest 0.25 sp step that fits its box (no line past `maxLines`, none ellipsized), never below
  the text's own size at font scale 1 (`scale1Size`); line height and letter spacing shrink with
  it, and cells keep their widths (I18). The lead keeps this reading of I27's "at least 11 sp":
  no text gets smaller than v1.8 drew it at font scale 1, and a text cut at font scale 1 by
  design (a long gallery name) stays cut at that size. Only v1.8's Array no-array hint passes a
  floor (8 sp, v1.8's own autosize); the object bar keeps v1.8's `BasicText` autosize. A test
  measures a `FitText` by its node's size (the semantics layout keeps the style's size).
  `Qa19Ui.textOverflows` (the lead's frozen kit, its one Gate 2 change): a line is too wide when
  `minOf(lineRight - lineLeft, getLineWidth)` passes the box by 0.5 px, so a centred line's
  floored and ceiled ends no longer read 1 px too wide ("Your gallery is empty").
- **More-row order (S5).** Text's v1.9 More-row items stand before Snap / Numbers (v1.8's pins
  "Snap ends the row" and "Snap, then Numbers" of `Qa18PolishChromeVerifyTest` hold); Shape's
  object items (Duplicate, Bring forward, Send backward) follow v1.8's More items by their
  `StripKeysV19` ranks 50-52. The two rows differ by design. Text's Duplicate shows with an open
  text, "New layer each time" without one.
- **Bars (S6).** Only the selection bar and the object bar hide Shape's object items
  (`ShapeStripItems.selectionOrObjectBarUp`); under the paste bar alone they stay. Shape's
  Duplicate of a new, pending shape is two steps (it places the shape, then copies it); Text's of
  an untouched open text is one. A sheet, a panel or node editing hides the More row, which
  stays remembered (`UiPrefs`) and shows again, open, when they end.
- **Labels (the lead).** No stand-in moved into `LabelsV19.kt`: every text the lanes named
  ("Close", "Done", "Cancel", "Cancel filter", "More options", "Settings", "Editor settings", and
  the Transform strip's "Delete picture" over an in-layer copy) is reused from v1.8 or older, and
  reused texts stay where they are. `LabelsV19Test`'s allowed pairs are Gate 1's (design 2.3.6
  and the F1a D10 ruling); its erase-mode list now reads each mode's note except
  `EraseLabels19.PARTIAL_NOTE`, a v1.9 text.

**Open bugs.** None from the lanes. Recorded edges, not changed: two region cuts pending on two
layers (while the first cut's own overlay shows, the second must land and is re-cut
synchronously, unbounded as before M3); `MaskComponentsSheet` invalidates twice in one frame on a
tap (harmless); a closed editor's `EditorPrefs` may follow one more restore before it is
collected (one `invalidateDoc` on a gone controller, harmless); A9's
`ShapeMoreRowHiddenWithBarsTest.withObjectsSelectedUnderShapeOrASelectionNoLabelShowsTwice` uses a
state the editor never shows (its rule still holds); A3's and A9's tests may drop their
`PILL_SLOT` / "Baseline shift" overflow exclusions; `SelectionDuplicate.shifted` and its test are
unused by product code. The CI flake of `FringePerfTest` is in "Testing and CI (v1.9)".

**Deferred past 1.9.**
- Product P4: the Eraser brush's path stroke on vector and shape layers routed to OBJECTS /
  CONVERT_THEN_OBJECTS keeps v1.8's route. `BrushTool.start` gives `StrokeHook.None` for a path
  and `CurveTool.commitObject` (and Shape's commit) paint the replay and append the eraser path
  in one `undoStepNamed` group; a hooked `VectorEraserRecorder` would double the effect and land
  outside that group, so it needs commit changes in `CurveTool` and `ShapeTool` (and would change
  `EraseRoutingTest` and `EraseRoutingV18Test`).
- Memory M4d: restoring the settings alone from the restore list (the service does it,
  `BackupService.restore(ids = empty, alsoSettings = true)`; the list needs a new label and a
  change to the pinned "Restore 0 artworks" check).
- A7b: a timeout counter in the frozen history region; `HistoryLabels` reporting a timed-out
  Undo or Redo; the `EditorSession.close` rework; a running byte total in `UndoManager`;
  `Filter.kernelRadius` tiling for the neighbourhood filters and streamed `ArrayCodec` sources;
  the design's wording.
- A6: moving `engine/live/WeakRegistration` beside `MemoryGovernor` (no behaviour change);
  `LayerToolRules` refusing the watercolor brush on an adjustment layer's mask (a behaviour
  change outside the requests).
- A3: `NODES_HINT` + " Tap the path to add a point there." (optional; the hint has no line limit
  and covers the canvas at font scale 2.0).
- A5 (lane strip): the optional one-time Partial hint for closed paths (a new label and a
  remembered flag).
- A8: a "More options" label constant, declined (a reused text; `LabelsV19.SHARED` would gain an
  entry for nothing).
- Shipped off, pending the Gate 3 device A/B: A6's P3 (`StrokeBelowCache.FOR_STROKES`), R11a
  (`StrokeBelowCache.FOR_ERASER`) and P4 (`StrokePreview.SHIPPED`, the "Drawing preview"
  setting hidden).

## v1.10 foundation (gates 0 and 1)

The lead's part of v1.10 (design sections 3 and 4). The lead builds the foundation on branch
`v110-foundation` from `main` at `ce5b7550` (v1.9.0): G0 (the ownership check and its tables),
F0 (the UI seams, the limit seam, identity and every region marker; tag `gate-v110-0`) and F1
(the engine contracts; tag `gate-v110-1`). Eleven areas then build in parallel, A1-A6 from
`gate-v110-0` and A7-A11 from `gate-v110-1`; each writes its section under "v1.10 areas" below.
The invariants are in "Invariants (v1.10)" above.

### Steps, areas and ownership

- Gates are tagged `gate-v110-0`, `gate-v110-1` and `gate-v110-2`. Areas work in `.wt/v110<x>`
  on branch `v110-<x>`. Lead commits are `v110F<n>: <text>` and `v110G2: <text>`; lead
  bookkeeping is `v110-lead: <text>`.
- The UI areas: A1 mirror sides, A2 object colors, A3 array behind and recolored arrays, A4
  Change Drawing Color on objects, A5 group snapping and the sharp / smooth cell, A6 Edit and
  Turn into shape. The engine areas: A7 budgets and limits, A8 tiles and residency, A9 the
  compositor on tiles, A10 swap and the automatic check, A11 streamed load and save.
- Default merge order: F0, F1, A7, A11, A5, A6, A1, A2, A4, A3, A8, A9, A10, then G2.
  `$Sequential` (rule O of `.wt/_tools/ownership-check-v110.ps1`) holds three files:
  `EditorSession.kt` (A11 before A10), `engine/LayerStructure.kt` (A7 before A8) and
  `tools/transform/DataLift.kt` (A1 before A8). The later area passes only once the earlier
  area's merge (recorded in `.wt/_tools/v110-merges.json`) is an ancestor of its HEAD.
- Each area keeps `.wt/v110<x>-progress.txt`, seeded by the lead from
  `.wt/_tools/v110-progress-template.txt`; rule C reads it.
- The test-count floor (`app/src/test/test-count.txt`, no trailing newline) is 4837 after F0:
  v1.9.0's 4787 and F0's 50 new non-Timing tests. The Timing run (`-PtimingOnly`) has 360 tests.
  Only the lead changes the floor, in a commit of its own after each step and merge.

### The UI seams (F0)

Every seam is hidden or a no-op until its area fills it, so the app at `gate-v110-0` behaves as
v1.9 except the Shape style labels (R3: "Stroke only", "Fill only" and "Stroke and fill", the
Curve tool's `CurveLabels17` texts).

- Texts and tags: `ui/common/LabelsV110.kt` (one constant per new text; areas use the constant
  and never retype it) and `ui/common/V110Tags.kt`, checked by `LabelsV110Test`.
- `vector/select/TapCycle.kt` (R1; A1): the spatial tap cycle, without a timer.
- `core/ColorUtils.recolor` and `recolorMatrix` (C3; A3, A4): v1.9's Change Drawing Color.
  `Filter.colorMapper` (A4) is null by default.
- `vector/ObjectColorSlots.kt` (I35, C4; A2, A3, A4): the one walker of object colors.
- `tools/ObjectColorEditor.kt` and `ColoredObjectTool` (R2; A2): what the pill's Object colors
  cell edits. The Shape, Curve and Text tools hold `objectColors = null` in their regions
  `v110-A2 colors`.
- `tools/points/PointCornerEditor.kt` and `CornerTool` (R12; A5): what the Sharp / Smooth cell
  edits. The Shape and Curve tools hold `pointCorners = null` in their regions
  `v110-A5 corners`.
- `ui/common/PaintModeControls.kt` (R3; A2): `PaintMode`, `PaintModeGlyph` and
  `PaintModeSegments`.
- `ColorPickerDialog(onPreview)` (R7): a live preview; Cancel and Back restore the start color.
- The coordinate pill: `PillCell.COLOR` starts row 2, under the move icon, and `PillCell.SHARP`
  sits under SEVERAL (never under the trash); `pillRow1`, `pillRow2` and `scaleRowMaxWidth`.
  `ui/tools/PillColorCell.kt` (A2) and `ui/tools/PillSharpCell.kt` (A5) are stubs with frozen
  signatures. Each cell shows only while its tool's editor is not null. Checked by
  `PillBudgetTest` (392 and 360 dp, folded, font scale 2.0).
- The bars (A6, with A3's entry point): `SelectionActionBar(onEdit, onArrayBehind)` and
  `VectorObjectBar(recolorIcon, onEdit, onToShape, onArrayBehind)`. Each new item is hidden
  while its callback is null. `EditorIcons.arrayBehind` is in
  `ui/common/icons/ArrayBehindIcon.kt`. `ObjectActions.editRefusal`, `openInEditor` and
  `turnIntoShape` are stubs; `CurveTool.kindOf` is the curve kind of a path and
  `CurveTool.openObject` is a stub in the region `v110-A6 openObject`. The editor screen passes
  `onArrayBehind` to both bars while `EditorController.arrayBehindAvailable` is true (A3's
  region; false at F0). A6 wires Edit and Turn into shape inside its own bar files.
- The long-note channel (R11): `EditorController.showNote(text, undoable)` sets `pendingNote`.
  The editor screen shows the note two frames later, so a tool switch made with it has settled.
  The note is a Long message with a dismiss action, plus "Undo" when undoable. Its Undo
  (`EditorController.undoNote`) works only while the note's step is still the newest. A later
  message waits behind the note instead of replacing it. Checked by `ShowNoteChannelTest`.
- `UiPrefs`: `hintArrayBehind` (`hint.arrayBehind`; A3) and `hintSidePicks`
  (`hint.sidePicks`; A1).

### The limit seam and identity (F0)

**The limit seam (C15).** `engine/LayerBudget.kt` answers every layer and canvas limit: for a
document `used`, `maxLayers`, `canAdd(doc, layers, extraBytes)`, `room`, `canAddAdjustment`,
`refusal(doc, suffix)` and `canTransformCanvas`; for a size before a document exists
`rawMaxLayers`, `canvasFits`, `canvasCheck`, `importMaxSide` and `capacity`; and the byte budgets
`canvasBudgetBytes` (canvas operations) and `renderCacheCap` (the vector render caches and the
array patches). `LayerBudget.current` is `LayerBudgetV19`, v1.9's formulas exactly (55 % of the
heap less three layer buffers, clamped to 2..100 layers; a mask counts as a layer and array
sources by their bytes; an eighth of the heap for the render caches), until F1 resolves the
engine. No policy reports more than `MAX_LAYERS_GUARD` layers.

- Sites that read it: the `layerCap` region of `EditorController` (`maxLayers`, `canAddLayer`,
  `effectiveLayerCount`, `layerLimitMessage(suffix)`) and `canAddAdjustmentLayer`; every
  literal refusal (`layerLimitMessage()`); the text tools' refusals
  (`layerLimitMessage(TextThreads.LIMIT_SUFFIX)`, the same text as `TextThreads.LAYER_LIMIT`,
  which stays declared); `ArraySources.hasRoom` (`canAdd(doc, newLayers, addBytes -
  freedBytes)`); `CanvasOps.memoryBudget()`; the render caches of `VectorLayers` and
  `ArrayRenders.patchBudget`. Code that reads the controller members is unchanged. The gallery,
  import and resize dialogs keep the heap-taking forms (`CanvasLimits`, `CanvasPresets`,
  `NewArtwork.for*`, `CanvasOps.validateSize`) in F0.
- `LayerBudgetV19ParityTest` compares every member with v1.9's formulas, copied, on 12 canvas
  sizes times 6 document states, then the controller members and the sites on a real editor,
  and shows with a stand-in policy that each site reads `LayerBudget.current`.

**Identity (C16 part 1).** The `Layer.bitmap` and `Layer.mask` setters bump
`Layer.pixelGeneration` when they replace the bitmap by another one (adding or removing a mask
too). Assigning the same bitmap and pixel edits in place do not (`contentVersion` counts those).
`Layer.owns(b)` is true for the layer's own bitmap or mask only.

- Staleness keys compare `pixelGeneration` with `contentVersion` instead of bitmap identity:
  the live adjustments' `BelowKey`, the pending renders of `ArrayRenders` and `VectorLayers`
  (and a vector edit's start), the `LayerBoundsCache` worker, the content-aware fill request,
  `CanvasSnapshot.matches` (through `LayerSnapshot.pixelGeneration`), the filter session's
  target and the adjustment stage's mask-factor cache. One generation covers the bitmap and
  the mask, so a mask added or removed on the filtered layer during a content filter session
  ends the session (v1.9 went on). A key also goes stale after a replacement that is taken back
  by assigning the old bitmap again (an edit session's blank swap), where v1.9's key was fresh:
  one redraw of the same pixels.
- Folder sentinels in `ColorSelectTool` and `MagneticLassoTool` test `isFolder`;
  `EditorController.verifyTree` and `Undo` keep comparing with `Layer.FOLDER_BITMAP`.
- Own-bitmap tests use `owns`: `copySelection`, the vector shift and the canvas operations'
  pending bitmap (`LayerSnapshot.owns`).
- `Undo`'s `DocumentBitmapsAction.Entry` defaults and the snapshot diffs of `CanvasOps` keep
  bitmap identity until F1 and A11.
- `GenerationReplacesIdentityTest` checks the setters, `owns` and the keys. Rule G of the
  ownership check refuses a new `.bitmap` or `.mask` identity comparison.

**Model seams (R4, R5).** `LayerStructure.below(layer)` is the insertion point directly below a
layer, at its index and in its folder; the caller copies the layer's clipping
(`BelowInsertionTest`). `ArraySpec.hideSource` and `ArrayLook.colorMode`
(`ArrayColorMode.TINT` or `RECOLOR`) are written only when set, so a v1.9 file reads and writes
the same; `sanitized()` and `mapped()` keep them (`ArraySpecFieldsTest`, and
`ArrayLookModelTest`, whose fixture of a look with every property changed sets `colorMode`).

**Frozen signatures.** `FrozenApiSignatureTest` (test root) reads the main sources and pins the
F0 declarations the areas build against: each signature as written (whitespace and a trailing
comma aside) inside its blocks, and the exact members of each frozen interface, enum and
object. F1 adds its part to `PARTS`.

### The engine contracts (F1)

Written at F1 (design 3.3): the frozen declarations listed in `.wt/_tools/v110-frozen-api.md`.

### Region markers and rules P, M, E, W, N, F and G

F0 placed every marker of design 4.2 around the code its area changes: `// region v110-<X>
<name>` ... `// endregion` in Kotlin, and `<!-- v110-<X> -->` ... `<!-- /v110-<X> -->` under
"v1.10 areas" below. One owner may repeat a name: `v110-A11 canvasOps` is three blocks in
`EditorController.kt`, `v110-A8 duplicate` two in `engine/LayerStructure.kt`, `v110-A1 copies`
five in `vector/VectorLayers.kt`, `v110-A5 snap` two in each of `ShapeTool.kt` and
`CurveTool.kt`, `v110-A5 sharpItems` three in `CurveStrips.kt`, `v110-A3 arrayExport` two in
`ExportSceneBuilder.kt`, `v110-A8 pins` two in `DataLift.kt` and `v110-A10 session` two in
`EditorSession.kt`. At least three lines separate a block from any other region. Markers never
change after F0 (rule R); `RegionMarkersTest` pins them with v1.9's. An area cannot add an
import to a file it does not own, so its code inside a region of such a file uses fully
qualified names.

`.wt/_tools/ownership-check-v110.ps1 -Area <F0|F1|G2|A1..A11> -Worktree <wt> [-Base <ref>]
[-ListPaths]` checks a branch against these rules (design 6.3). Always pass `-Worktree`: the
default is `.wt\v110<x>` for an area and the main checkout for F0, F1 and G2, never the current
folder. `-Base` defaults to `main`; a lead step passes the commit it started from. The checker
reads committed changes only. Each finding is one line, `RULE path:line message` (line 0 is the
whole file). Exit 0 is OK, 1 is at least one finding, 2 is a usage, setup or git error.
`-ListPaths` also prints each allowed path with its reason. `-SelfTest` runs the fixtures of
`.wt/_tools/ownership-v110-fixtures/` and exits 0 only when every fixture passes and every rule has
one fixture with a finding and one without.

Rules kept from v1.9:
- **O (owner):** every changed main path belongs to the running area (`$Owned` first, then
  `$Frozen`); a frozen, foreign or unowned path fails. A changed existing test is checked in
  this order: `$FrozenTests` fail; `$LeadTests` fail for an area; the area's `$ExtraTestFiles`
  pass; a `<Name>Test.kt`, `<Name>RobolectricTest.kt` or `<Name>UiTest.kt` beside a main
  `<Name>.kt` passes only for that file's owner (the mirror rule); then v1.8's folder rule; else
  it fails. New tests may go anywhere outside `$FrozenTests` and `$LeadTests`, and new test
  resources anywhere; an existing test resource fails for everyone. Repository files outside
  `app/src/main/java` are the lead's. An area fails on `.github/workflows/**`,
  `app/build.gradle.kts`, `app/src/test/test-count*.txt`, `.wt/_tools/**` and `README.md`. In
  `.wt/v110-full-backlog.txt` an area may only remove the lines that name it. The lead steps may
  change every main path. Existing tests: F0 only those design 3.2 lists (`$LeadStepTests.F0`),
  F1 any outside `$FrozenTests`, G2 any. A finding on a `$Sequential` path is reported as O.
- **R (regions):** in each file of `$Regions` and in this file, every changed line lies strictly
  inside one of the running area's regions. No marker line changes. A1, which owns
  `tools/transform/DataLift.kt`, may change any line there outside the other areas' regions. An area that
  changes a lead-owned region file where it has no region fails.
- **S:** no added main line outside `engine/` holds `doc.layers.add`, `insertLayer(` or
  `RemoveLayerAction(`. **L:** no added main line outside `ui/common/` holds a label of
  `LabelsV18.kt`, `LabelsV19.kt` or `LabelsV110.kt` (as at the base) as a literal, unless the text
  is also a literal elsewhere in the base's main sources. The check skips `LabelsV110.kt` while the base
  has no such file (before `gate-v110-0`). **T:** no `TextCodec.decode(` or `encode(` outside
  `TextCodec.kt`. **K:** for the areas and G2, no added `@Serializable`, `@EncodeDefault` or
  `@SerialName` and no changed `VERSION =` line outside `storage/backup/**`. Every new persisted
  field is the lead's.
- **C (commits):** area subjects are `v110<x>: k/n <text>`, with the lowercase id (`v110a7`) and n
  the plan size of design 4.4 in every subject. k starts at 1, never falls and rises by at most one
  per commit. A branch ends below n only when the base holds `v110-lead: cut v110<x> k..n`.
  `.wt/v110<x>-progress.txt` lists the same k values, each with a commit hash, and the cut (or
  `none`). Lead subjects are `v110F0: `, `v110F1: `, `v110G2: ` and `v110-lead: `.

New rules. They read only the lines a branch adds to the main sources (comments skipped), and the
lead steps are exempt from M and F:
- **P (pixel facade, I28):** a `.bitmap` or `.mask` read of a layer inside a loop or sequence over
  layers (`forEach`, `map`, `filter`, `sumOf`, `any`, `count`, ... on `layers`, `allLayers` or
  `visibleLayers`, or `for (... in ...layers)`) fails. So does a new read in a file that is neither
  a C-RES file nor listed in `.wt/_tools/v110-bitmap-allow.txt`, and a mask presence test by
  `== null` (`hasMask` comes with F1). A line ending in `// I28: one layer` passes, and the
  allow-list's exempt engine files (A8-A11) are not checked. A read whose text is also on a removed
  line of the same file is moved code and passes. The lead steps are exempt from the allow-list
  check, and F0 from all of P: it moved v1.9's `doc.layers.count { it.mask != null }` into
  `LayerBudgetV19`.
- **M (memory sources):** `maxMemory()`, `ActivityManager.MemoryInfo`, `getMemoryInfo(`,
  `StatFs(`, `Debug.getNative` or `getHistoricalProcessExitReasons` outside `engine/budget/**`.
  The existing lines (`GovernorPolicyV19`, `BrushTool`, `ContentAwareFillJob`, `ExitReasons`) are
  not added lines, so they never fail.
- **E (engine level, I31):** `openSession(` or `closeSession(` outside `EditorSession.kt` and
  `engine/EngineConfig.kt`; an assignment to `EngineConfig.residency`, `swapStore`,
  `memoryProbe`, `storageProbe` or `exitInfo` outside `engine/tiles/ResidencyPolicy.kt`,
  `engine/tiles/SlabSwapStore.kt`, `engine/EngineStartup.kt` and `engine/check/**`; `EngineLevel.`
  in `ui/**` outside `ui/engine/**`.
- **W (worker and main, I29):** `park(`, `unpark(`, `replaceTiles(` or `evict(` outside
  `engine/tiles/**` (`engine/Undo.kt` may call `replaceTiles(`, `engine/NativeWatch.kt` `park(`
  and `evict(`); `Deflater(` or `Inflater(` outside `engine/tiles/**`, `engine/check/**` and
  v1.9's codec files; `Thread(`, `Executors.` or `HandlerThread(` in `engine/tiles/**` outside
  `TileWorker.kt` and `SlabSwapStore.kt`.
- **N (no new project files, I34):** any change inside `ProjectFormat.isDataFile`,
  `referencedFiles` or `deleteUnreferenced` (with the `is*File` helpers and `*FilePattern` values `isDataFile` is made of);
  a `noBackupFilesDir` or `"swap"` path outside `engine/tiles/SlabSwapStore.kt`,
  `engine/tiles/SwapJanitor.kt`, `engine/check/**`, `engine/ExitWatch.kt`, `storage/engine/**`
  and `storage/backup/**`; a new file kind in a project folder (a file name the project writers
  add that is not one of v1.9's kinds).
- **F (format, I30):** `ProjectFormat.writtenVersion(` outside `storage/ProjectFile.kt` and
  `storage/ProjectRepository.kt`.
- **G (generation, I28):** a new `.bitmap` or `.mask` identity comparison (`===` or `!==`). A
  comparison with `Layer.FOLDER_BITMAP` passes, and so does one that is also on a removed line of
  the same file.

The signatures that are frozen inside area files (`PillColorCell`, `PillSharpCell`, the three F0
functions of `ObjectActions`, `CurveTool.openObject` and `CurveTool.kindOf`, and F1's) are
checked by `FrozenApiSignatureTest`, not by the script. `.wt/_tools/v110-frozen-api.md` holds
every frozen declaration as pasted from the code at each gate.

### CI: shards, `unit-full` and the two floors

Written at F1 (design 6.5).

## v1.10 areas

One block per area, in area order. Each area writes its section only inside its own block
(rule R), in its last plan step before its cut steps; the heading above each block stays.

### Mirror sides: tap cycle, linked isometries, detach, delete one side (A1)

<!-- v110-A1 -->

R1. Files: `vector/copies/CopyPick.kt` and `vector/copies/CopyEdit.kt` (new),
`vector/lift/CopyLift.kt` (new), `vector/convert/LegacyShapes.kt` (new), `vector/ObjectCopies.kt`,
`vector/VectorOps.kt`, `vector/lift/VectorLift.kt`, `vector/lift/VectorObjectLift.kt`,
`tools/transform/TransformTool.kt`, `tools/transform/DataLift.kt` (outside `v110-A8 pins`);
regions `v110-A1 copies` (`vector/VectorLayers.kt`: `hitTest`, `beginEdit`, `planEdit`,
`renderEdit`) and `v110-A1 overlay` (`vector/select/VectorObjectSelection.kt`: `drawOverlay`).
Labels: `MirrorLabels110` (`SIDE_HINT`, `SIDE_RESIZE_BLOCKED`, `SIDE_REFUSED`, `DELETE_SIDE`;
`PERSPECTIVE_SEPARATE` is for the Array sheet, a foreign file: a lead request). Undo labels:
`TransformTool.TRANSFORM_OBJECTS_LABEL` and `MirrorLabels110.DELETE_SIDE`. No field, no codec
change, no new preference beyond F0's `UiPrefs.hintSidePicks`.

**Every mirror type is a map list.** Mirror, Kaleidoscope, Rotation and Array (all but
Perspective) store their copies as `CopyMaps` on the one object (`VPath.copies`,
`ShapeObject.copies`: 3 x 3 row-major affine maps, identity first, 2..256 of them). Copy k is
the source mapped by `C_k`. `CopyPick.hitCopy(copies, p, tol, skip, test)` maps the tap back
through each `C_k⁻¹`, top-most k first, with the tolerance divided by the map's smallest stretch,
and runs the object's existing hit test there; `ObjectCopies.hits` and `VectorOps.hitCopy`
delegate to it. A stroke (`VStroke`) is always picked whole (k = 0). `CopyPick.side(o, k)` is
copy k as a plain object; `boundsOf(o, k)` its painted bounds. A Perspective array bakes its
copies into separate plain objects, so each one is an object of its own.

**The tap cycle** (`VectorLift.tap`, through F0's frozen `TapCycle`): spatial only, no timer.
- Depth 1, the first tap on a mirrored object in Transform: the side under the finger alone (the
  transient `sidePick`: layer, object id, k). The first `SIDE_HINT_PICKS` (3) picks on a phone
  show `SIDE_HINT` (`UiPrefs.hintSidePicks`).
- Depth 2, a tap on the same spot: all sides (the object as in v1.9). Depth 3 and on: v1.9's
  deeper cycle (the object below). A tap 16 dp or more away, a tap that no longer hits the
  picked object, a drag, pinch or handle gesture in between (`VectorLift.gestureEnded`) or
  choosing a tool again (`VectorLift.toolLeft`) starts a new cycle; ✕ alone does not.
- A tap on a picked side that has moved commits the move as one step and lifts all sides (it
  replaces v1.9's `inside && moved` guard for a picked side).
- Select shapes never picks a side. On a Perspective copy the first tap picks that (baked) copy
  and the same spot again goes on to the object below.

**One side lifted.** `VectorLift.begin(layer, ids, side)` with `side ≥ 0` asks
`VectorLayers.beginEdit(layer, ids, side)`: the floating bitmap is copy k alone (one side's
bounds, smaller than v1.9's union) and the hole is "the object without map k"
(`CopyEdit.dropCopy`) rendered in the side's tiles, so the other sides stay in place while it
moves. The lift is a `CopyLift` (a `VectorObjectLift` with `side`); `TransformTool.sidePicked`
is true while it is the session's lift. The overlay draws the picked side's box.

**Linked isometries** (`CopyEdit`, `CopyLift.mapped`). The Transform state gives a document map
M; a non-isometry is first snapped to the nearest isometry (`CopyEdit.nearestIsometry` about
the lifted box's centre: a residual drift never resizes). Then:
- k ≥ 1: `C_k' = M · C_k`;
- k = 0 (the source): `src' = M · src` and `C_j' = C_j · M⁻¹` for every j ≥ 1, so the other
  sides stay exactly where they were.

The moved side stays a map of the same object: later edits of the object show in every side.
Maps within `MERGE_EPS` (1e-4) of the identity or of an earlier map merge (`CopyEdit.merged`);
when one map is left the object is plain. A result `StrokeCopies.isUsable` rejects (a
coefficient past 1e7, a determinant under 1e-12) or a list outside 2..256 maps is refused:
`CopyLift.refused` toasts `SIDE_REFUSED`, nothing is recorded, ✓ keeps the side lifted and
every other end (✕, another tool, a tap) drops the move.

**Resizing one side is blocked (LA4, the user's answer).** While `sidePicked`: no scale handles
are drawn or hit (`sideHandlesShown`; a corner touch is a move), `objectScale` is null so the
pill hides its Scale row, `modeRefusal` names Distort and Free deform `SIDE_RESIZE_BLOCKED`
(dimmed in the mode menu, the reason under the row), skew, the scale setters and Fit to canvas
are refused, the two-finger gesture moves and turns only (`applyPinch` locks its scale to 1.0),
and the first refusal of a pick, or the first drag that starts at a box corner, toasts
`SIDE_RESIZE_BLOCKED`. A same-spot second tap lifts all sides, where
every handle and mode works as in v1.9. **Detach is not built:** a picked side always stays
linked (`CopyEdit.detachOne` does not exist).

**Delete one side.** `TransformTool` is a `DeletingTool`; `objectDeletion` is non-null only while
a side is picked, so the pill's trash cell shows then, named `DELETE_SIDE` (a whole lift still
has no trash). k ≥ 1 drops the map; k = 0 rebases (`src' = C_1 · src`, `C_j' = C_j · C_1⁻¹`);
a Mirror of two maps becomes a plain object where the other side was. One step `DELETE_SIDE`;
the object stays selected and the pick is forgotten (`VectorLift.sideDeleted`). The strip's More
row Delete still deletes the whole lift (`TransformStripItems` is foreign: a lead request).

**Undo (I2).** ✓ is one step `TRANSFORM_OBJECTS_LABEL` however many moves, turns and flips came
before it (`TransformTool.applyPending` → `VectorObjectLift.commit` → one `VectorLayers.update`);
✕ records nothing; undo restores every map bit for bit.

**Legacy shape layers** (`LegacyShapes`, C9). A Transform tap on a legacy one-shape layer whose
shape has live copies (`DataLift.tap`) converts the layer in place with `LayerConvert` without a
step, holds the conversion (one per editor) and hands the tap to the vector lift. ✓ after a
move, or Delete one side, runs inside `LegacyShapes.fold`: one `groupUndo` holding the
conversion and the edit, rendered at once (`Policy.SYNC`); undo gives the shape layer back. ✕, an
unchanged ✓, a same-spot tap on an unchanged side, another layer or leaving the tool
(`release`) put the shape data back with no step. A shape layer without copies keeps v1.9's
`DataLift`. (The design's in-memory conversion became this convert-in-place-and-hold: the
transform lift needs a real vector layer; a save while the side is lifted stores the vector
layer.)

**Compatibility.** A moved side is another matrix in the stored list, a deleted side fewer maps;
every map written is an isometry. v1.9 and older read and render the file as v1.10 draws it
(`OneSideCompat19Test`, with the frozen v1.8 codec until `compat19` is on main).

**Performance.** `hitCopy` costs at most 255 3 x 3 inverses per tap. `SidePickTimingTest`: tap
to box at 256 copies, PC 16 ms (2048 x 2048 locally, 512 x 512 on CI); `OneSideDragFrameTimingTest`:
a drag frame of one side of 8 copies (the move and the preview drawn), PC 5.5 ms. Both x 4 on CI.

Tests: `v110/a1/**` (`CopyPickTest`, `StrokeStaysWholeTest`, `SideTapCycleTest`,
`OneSideMovedThenAllSidesTest`, `PerspectiveCopyTapCycleTest`, `SideHintFirstThreePicksTest`,
`CopyOffsetMathTest`, `OneSidePinchNeverScalesTest`, `OneSideResizeBlockedTest`,
`SideEditPlanTest`, `SideRefusedTest`, `DeleteOneSideTest`, `CoincidingMapsMergeTest`,
`OneCheckOneStepTest`, `LegacyConvertOneStepTest`, `OneSideCompat19Test`,
`MovedSideFollowsLaterEditsTest` (a moved side takes later point, color and size edits where it
is), `SelectShapesPicksWholeTest`, `CopyEditIsometryTest`, the two timing tests)
and `qa110/Qa110OneSideUiTest` (392 and 360 dp, font scales 1, 1.3 and 2.0).

<!-- /v110-A1 -->

### Object colors: the sheet, the delegates, the pill cell, one paint-mode control (A2)

<!-- v110-A2 -->

(A2 writes this section.)

<!-- /v110-A2 -->

### Array behind and Recolored arrays (A3)

<!-- v110-A3 -->

(A3 writes this section.)

<!-- /v110-A3 -->

### Change Drawing Color on objects: `colorMapper` and object mode (A4)

<!-- v110-A4 -->

(A4 writes this section.)

<!-- /v110-A4 -->

### Group snapping and the sharp / smooth cell (A5)

<!-- v110-A5 -->

`tools/points/PointGroupSnap.kt` (R8) snaps a set of moving points as one object. Shape's
Points mode and the Curve, Polyline and Path tools call it from their regions `v110-A5 snap`
(two in each of `ShapeTool.kt` and `CurveTool.kt`) for a drag of selected points and for the
point gizmo.

- Per axis the candidates are the moving box's min, centre and max, then the moving points
  themselves (the grabbed one first). The smallest correction within the snap tolerance wins
  (`SnapSession.snapValue`, the session's object lines); without one, the grid (the grabbed point,
  or the box min), then the length step. Every point moves by the same correction.
- The moving points are never targets: the tool begins its `SnapSession` with only the other
  points as `extra` point lines (Curve: `beginSnapWithout { it in moving }`; Shape: the
  non-selected vertices, also in the new `Mode.GIZMO` case of `beginSnap`), so a group never
  snaps to itself.
- Cause E: a reopened Curve, Polyline or Path that is its layer's only object begins the session
  with that layer in `exclude` (`CurveTool.ownLayerExclude`): until ✓ the layer's pixels still hold
  the path as it was, so its content bounds were the path's own old extent. With other objects
  on the layer, the layer stays a target.
- Above `PointGroupSnap.MAX_POINT_CANDIDATES` (256) moving points only the three box lines are
  candidates: a drag stays one linear scan per candidate (`GroupSnapPerfProbeTest`, PC budget
  4 ms, device 24 ms).
- Gizmo scale handles snap the edge they drag, objects only (as `TransformTool`):
  `PointGroupSnap.scale(m, part, box, pivot, keepProportions)` rebuilds the factor about the
  pivot so that edge lands on the target; a corner keeping proportions lets the closer edge decide
  both axes. Rotate is unchanged.
- The guides show for the snapped lines (`SnapSession.showGuidesFor`); `SnapSession.draw` gets
  the moving box (Shape: the shape's box; Curve: the grabbed point, or the group box's centre) to
  keep its labels off it. Undo is unchanged: a group drag is still the drag's one in-tool step,
  and ✓ commits the edit as one step (`GroupSnapAndSharpRoundTripTest`).
- The box is measured once per group edit (`groupSnapMeasure` in Shape, `beginGroupSnap` in
  Curve), not per move.

The Sharp / Smooth cell (R12) is the pill's `PillCell.SHARP`, at the end of row 2 under
"Select several" (F0's `pillRow1` and `pillRow2`; never under the trash, folded the last cell of
the one row).

- `tools/points/CornerEditors.kt`: `CurveCornerEditor` (Curve, Polyline, Path) and
  `ShapeCornerEditor` (Shape's Points mode) implement `PointCornerEditor`; the tools return them
  from `pointCorners` in their regions `v110-A5 corners`.
- `corner` is a `derivedStateOf` over the selection and the corner flags (`VAnchor.sharp`,
  `VSplinePoint.sharp` for a Path, `ShapeAnchor.smooth`), so a point drag does not recompose the
  pill. Null (no selected point, or Shape outside Points mode) hides the cell. `SMOOTH`,
  `SHARP`, `MIXED` (a tap makes every selected point sharp: Mixed means Sharp, UQ9, in every
  tool), `ENDS_ONLY` (only the ends of an open Path, which are always sharp) and `ALWAYS_SHARP`
  (Polyline, UQ18).
- `setSelectedSharp` is one in-tool step (`CurveTool.setSharpPoints`,
  `ShapeTool.setSelectedSmooth`) and returns false, with no step, when it changes nothing, for a
  greyed state, or while a group edit runs (`cornerLocked`, C10).
- `ui/tools/PillSharpCell.kt` draws the face of the state (the glyph shows what a tap does):
  "Sharp corner", "Smooth", `PointLabels110.MIXED_MAKES_SHARP`, and greyed
  `PointLabels.ENDS_SHARP` or `PointLabels110.POLYLINE_ALWAYS_SHARP`. Tag `V110Tags.PILL_SHARP`;
  a long press shows the name; a greyed cell is disabled for accessibility and a tap shows its
  name in the long-press bubble. Every face's click-action label is its name (v1.7's
  `qa17/Qa17PointsGroupPropsUiTest` presses the Mixed face by `MIXED_MAKES_SHARP`).
- The More rows lost their Sharp / Smooth items (regions `v110-A5 sharpItems` in
  `CurveStrips.kt` and `ShapeStrip.kt`). The code stays behind `CornerEditors.SHOW_IN_MORE`
  (false), which only the lead may flip; while it is false `ui/vector/CurveStripRulesTest` finds
  none of the item's names on the Curve and Path More rows in any state. In Curve and Path the removed item's place stays:
  `sharpSlotItem` (key `curve.sharpSlot`, 40 dp, `shown = false`, so the host reserves the width
  and draws nothing) takes it under the same condition (`f.sharp != NONE`). The user sees it as
  an empty 40 dp place where an item comes before it on its More line (Path: between Endpoint
  and Select all, where the Sharp item was); first on a line it reads as the line's slack (More
  lines are end-aligned). The host packs the
  More lines greedily and "All points 100 %" comes and goes before the thickness slider with the
  slider's own use: without the 40 dp, at 392 dp the slider ended line 1 while every point was at
  100 % and jumped to line 2 at the drag's first change, away from the finger (and its
  semantics, 10 dp past its layout, met the line's clip). `v110/a5/ThicknessKeepsItsLineTest`
  (pure) and `ThicknessDragKeepsItsPlaceUiTest` (a drag in the editor) hold v1.9's place. The
  slot goes when the thickness item comes before "All points 100 %" (Polyline's order, outside
  the A5 regions: a LeadRequest). Shape's strip has no slot: no test showed a Shape control
  that changes place without its Smooth item.
- The point sheets list Sharp before Smooth (region `v110-A5 cornerChips` in
  `CurveToolOptions.kt`, and `ShapePointOptions.kt`), and Shape's Mixed chip makes the points
  sharp.

Tests: `v110/a5/` (`GroupGizmoSnapReproTest`, `GroupPointSnapOtherPointTest`,
`CurveSelfSnapExcludeTest`, `GroupSnapCandidateCapTest`, `GroupScaleEdgeSnapTest`,
`GroupSnapPerfProbeTest`, `SharpMixedMakesSharpTest`, `SharpIgnoredDuringGroupEditTest`,
`SharpEndsOnlyTest`, `SharpOneStepPerTapTest`, `SharpNeverUnderTrashTest`,
`PolylineSharpCellDisabledTest`, `PillSharpPlacementTest`, `GroupSnapAndSharpRoundTripTest`:
undo and redo of a snapped group move, save and reopen, a v1.9 artwork unchanged;
`ThicknessKeepsItsLineTest`, `ThicknessDragKeepsItsPlaceUiTest`: the slot above),
`tools/points/PointGroupSnapTest`
and `qa110/` (`Qa110SharpCellUiTest`, `Qa110GroupSnapUiTest`).

<!-- /v110-A5 -->

### Edit and Turn into shape on the bars (A6)

<!-- v110-A6 -->

R10 (Edit), R11 (Turn into shape), R3 row 6 (the Recolor icon) and the bar items of R4 (Behind).
Code: `ui/vector/VectorObjectBar.kt` (the object bar), `ui/editor/SelectionBar.kt` (the
selection bar), `vector/select/ObjectActions.kt` (what the buttons do),
`vector/convert/PathToShape.kt` (the mapping), the regions `ShapeTool.kt` `v110-A6 open` and
`CurveTool.kt` `v110-A6 openObject`. No document change: a converted path is an ordinary
`VShape`, so v1.8 and v1.9 read it.

**Ranks and rows (J26).** `ObjectBarSlot` holds each button's width in units and its rank: Array 1,
Behind 1, Detach 2 (2 units), Turn into shape 2 (2 units), Forward and Backward 3, Front and Back
4, Duplicate 5, Recolor 6, Transform 7, Edit 8, Deselect 9, Delete 10. `ObjectBarSlot.shown` gives
the row order (Edit right after Transform, Behind right after Array, Turn into shape after
Detach); `ObjectBarSlot.split` empties row 1 a whole rank at a time, lowest first, into the More
row's lines (`ObjectBarLayout.split`).
- At 392 dp one selected object with an editor keeps eight buttons on row 1 at 40.5 dp (324 dp
  beside the 44 dp toggle). At 360 dp Front and Back move to the More row (six at 46 dp), so
  "Turn into shape" starts the More row's second line there.
- Without Edit (several objects, or a brush stroke) the bar is v1.9's seven at 46 dp.
- Recolor's icon is `Icons.Outlined.Palette` (`ObjectBarRecolorIcon`). Behind
  (`ArrayLabels110.BEHIND`, `EditorIcons.arrayBehind`) shows only while the editor passes
  `onArrayBehind`, that is while `EditorController.arrayBehindAvailable` (false until A3 merges,
  UQ4).

**Edit on the object bar (R10).** `ObjectBarLabels110.EDIT`, tag `V110Tags.OBJECTS_EDIT`.
- Shown when exactly one object is selected and it is not a `VStroke` (`ObjectActions.editShown`).
  Dimmed while `ObjectActions.editRefusal` names a reason the user can act on: a path of several
  parts, or of fewer than 2 anchors. A tap on a dimmed Edit shows that reason as a toast.
- `ObjectActions.openInEditor` commits the current tool's pending work, then waits for the renders
  in flight (`PendingRenders.whenIdle`, key `EditOpening`; a second tap while it waits is
  ignored). It then clears the object selection and opens the object in its own tool: a `VShape`
  through `ShapeTool.editShapeObject`, a `VPath` through `CurveTool.openObject(layerId, id, kind)`
  with `kind = CurveTool.kindOf(path)` (Curve, Polyline or Path).
- A locked or hidden layer refuses Edit with the layer's message (`EditorController.checkEditable`)
  before the selection or the tool changes.
- Both tools ignore a second open of the object they are opening or already have open. The Shape
  tool keeps `openingObject` with its `reopenToken`; the Curve tool keeps `openingObject` and
  `isOpening`, and an open it refuses (another path still opening) leaves that guard as it was.
  So a double tap never applies the open edit and opens it again.
- The button records no step: the editor's ✓ records its own ("Edit shape", "Edit path"), and ✕
  records none. The user stays in that tool afterwards (UQ10).

**Edit on the selection bar (R10, finding 27).** `SelectionBarEdit.target` finds what to open:
- a legacy shape layer (opened with `LayerOps.editShape`);
- or the one text of a text layer that the selection meets (opened with
  `TextTool.editLayer(layer, openEditor = true, index)`; a frame of a linked story opens through
  `LayerOps.editText`). Two texts met means no Edit.

While Edit shows (tag `V110Tags.SEL_EDIT`) it is the bar's first item, before Copy. The Array
pair ("Array", then "Behind" while passed) comes before Save, Duplicate and Hide.

**Turn into shape (R11).** `ObjectBarLabels110.TO_SHAPE` on the More row, as wide as Detach (tag
`V110Tags.OBJECTS_TO_SHAPE`), shown when exactly one `VPath` is selected
(`ObjectActions.toShapeShown`).
- `ObjectActions.turnIntoShape` commits pending work and waits for renders (`edit`).
- A refused path shows `PathToShape.refusal`'s reason and changes nothing. Refused: several
  pieces; fewer than 2 anchors, non-finite or all on one spot; more than
  `PointBevel.MAX_POINTS_SHAPE` anchors.
- Otherwise the path is replaced in place by `VectorContent.replaced`: same id, same z-position,
  its `CopyMaps` (S2 offsets included) as they were. This is ONE step labelled `TO_SHAPE`.
- It then clears the object selection, selects the Shape tool and opens the shape
  (`editShapeObject`).
- Only after that it calls `EditorController.showNote(notes joined by "; ", undoable = true)`, so
  the tool's own opening message never hides the note.
- The note waits through later messages. Its "Undo" (`undoNote`) drops the open shape and takes the
  step back, and only while that step is still the newest.

**The mapping (`PathToShape.convert`).**
- Anchors are read as the renderer reads them (`VectorOps.curveAnchors`, so a NURBS path uses its
  Bézier form). Handles come from `CurveGeometry.handles`.
- A segment whose control points lie on its chord gets zero handles, and so do an open path's
  outer ends. Polyline points are all sharp. A sharp point without handles becomes a plain
  `ShapeAnchor`.
- `ShapePoints.fit(0, anchors, closed)` gives the box and the shape's own points. A closed path of
  3 or more points becomes a custom closed outline (type RECTANGLE with points); anything else a
  LINE with points.
- Copied: stroke color, width (times the anchors' common width, or their mean when they differ),
  cap, join (MITER to SHARP, BEVEL to BEVEL, otherwise ROUND), brush tool and preset (with the
  brush seed), a closed path's solid fill, and the opacity.
- The Shape tool opens a shape with its line color as the main color. So a fill without a line
  also gives the shape its line color (not drawn, style FILL): the main color is the fill's while
  it is open, never transparent. A fill of the line's color (or none) follows the main color
  (`fillFollowsColor`), as a fill the Shape tool drew does; a fill of another color keeps its own.
- A path whose widths vary is drawn by the renderer as a filled outline with round ends and
  joins, so its shape gets ROUND cap and corners. A gradient fill becomes its first stop's color.
- Notes, in this order: `NOTE_GRADIENT`, `NOTE_WIDTH` (widths vary, or a taper), `NOTE_GENERATED`
  (a generator path), `NOTE_OPEN_FILL` (an open path loses its fill).
- Lost without a note: the path's miter limit (the shape uses its own, 10). An open path's miter or
  bevel joins also become round, because line-like shapes always join round.
- The result passes `ShapeObject.sanitized`. The cost is O(anchors): about 0.6 ms for 1,000
  anchors on the PC (`PathToShapeSpeedTest`).

**Not built.** The optional second entry, in the Curve tool's settings sheet (region
`CurveToolOptions.kt` `v110-A6 turnIntoShape`, cut 7), was not built. Two reasons:
- The open path's layer and id are private to `CurveTool`.
- A path changed in the tool would convert as two steps (the edit's own, then "Turn into shape").

Tests: `test:v110/a6/` (the layout, Edit, PathToShape and TurnIntoShape classes; a converted
path saved and reopened through the project repository: `TurnIntoShapeSaveReopenTest`) and
`test:qa110/Qa110EditUiTest`, `test:qa110/Qa110ConvertUiTest` (392 and 360 dp, font scale 1, 1.3
and 2.0).

<!-- /v110-A6 -->

### Budgets J and R, `LayerBudgetV110`, limits and the layers panel (A7)

<!-- v110-A7 -->

(A7 writes this section.)

<!-- /v110-A7 -->

### Tiles, residency, activation and undo on tiles (A8)

<!-- v110-A8 -->

(A8 writes this section.)

<!-- /v110-A8 -->

### The compositor on tiles, projections, `PixelReads`, thumbnails (A9)

<!-- v110-A9 -->

(A9 writes this section.)

<!-- /v110-A9 -->

### Swap, the automatic check, safety nets, "Layer storage" settings, `EngineStats` (A10)

<!-- v110-A10 -->

(A10 writes this section.)

<!-- /v110-A10 -->

### Streamed load and save, format 4, multi-layer operations, import and export (A11)

<!-- v110-A11 -->

(A11 writes this section.)

<!-- /v110-A11 -->

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
- **`AddLayerAction(layer, index, label, activeBefore)`** is the v1.6 add: a plain layer at the root of a document without folders.
  - `LayerStructure.placed` captures the active row before the insert.
  - Undo removes the layer and selects `activeBefore` again, by reference, as `LayerTreeAction` does. When that layer is gone, the index is clamped as before. Redo selects the added layer.
  - This covers every plain add, cut or copy to a new layer, import and duplicate without folders.
  - It is the only intended change to v1.6 undo behaviour; the actions and file bytes are unchanged.

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
- Vector arrays export each copy's objects, and text arrays each copy's outline parts (`TextSource.parts`), with the `ArrayLayout` matrix applied to the geometry (`SceneLayer` has no transform). The matrices are measured from `ArrayDraw.sourceBounds`, as the cache is, and the copies run N − 1 first with the source on top. A text array is never written as `<text>`, so manual kerns and Font kerning show in every copy. Shape and raster arrays, and a text array with color emoji (letters without outlines), export the cache picture. The payload is unchanged: an arrayed layer is its cache plus its array.
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

**Audits and flows with the other v1.7 areas (integration).**
- **Audits.** `FolderAuditTest` uses the audit's nested document (F1 holding A and the isolated F2 holding B). On top of the v1.6 whole-document operations, it covers the following, each ONE step with exact undo and redo of the tree, pixels, layer data and composite, `LayerTree.check` clean, and nothing drawn into `FOLDER_BITMAP`:
  - an array made from a selection inside a folder (it lands directly above its source, in the same folder), and Apply array (pixels, and a shape array that becomes a vector layer);
  - Free deform of a folder's child (a folder refuses it with "Free deform works on one layer");
  - saved selections carried through a canvas rotate, flip and resize;
  - Pathfinder on shape layers in two folders (the result goes into the top operand's folder).
- **Cross-area flows.** `FolderFlowsRobolectricTest` runs these flows end to end:
  - a selection array and a shape-layer array in an isolated folder, moved and scaled with the Transform tool (FolderLift), saved and reopened: the arrays stay live with the mapped spec, the composite is unchanged, and one undo restores everything;
  - a Rotation × 4 stroke on a child of an isolated folder at 60 %: one step, and the composite shows all four copies;
  - two shape layers in a folder united by Pathfinder: "Pathfinder 1" goes in that folder, and one undo restores both operands.

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

### Fringe colors (v1.8 item 5, area A)

The filters are Chromatic aberration (Moving and Zooming) and Glitch. The code is in `filters/art/FringeColors.kt`, `ChromaticAberration.kt`, `GlitchFilter.kt` and `ui/filters/FringeControl.kt`.

**Value.** `FilterParam.Fringes(key = "fringes")` holds an `IntArray(3)` of ARGB swatches:
- index 0 is the first fringe (moves forward: along the angle, or outward when zooming);
- index 1 is the middle fringe (stays);
- index 2 is the last fringe (moves back).

`0` means "No color". Only the R, G and B bytes count; a swatch's alpha is ignored.

The presets (`FringeColors.PRESETS`) are RGB (the default: red, green, blue), Red/Cyan, Magenta/Green and Blue/Yellow. The last three have no middle color.

`FringeColors.resolve(values, p)` chooses the colors in this order:
1. the stored 3-int array;
2. otherwise, for Chromatic aberration only (`legacyOrderKey = "order"`), v1.7's order index mapped to permuted primaries (`legacyOrder`: letter i of `ArtMath.colorOrderNames[index]` is the primary of fringe i);
3. otherwise the default.

An out-of-range order gives the default. The control shows the same resolved colors, so a v1.7 order is displayed as its colors until the next change stores them.

Glitch adds `FilterParam.Color("static_color", "Color of the static", white)`.

**Choosing the path.** `allNone` is checked first:
- Chromatic aberration returns `src.copy()`.
- Glitch's bands only slide, with no channel split.

Otherwise `oneHotFactors(colors)` returns v1.7's direction factors `k[c] = 1 - i` (+1 first, 0 middle, -1 last) when all of these hold:
- every swatch channel is 0 or 255;
- each channel is 255 in exactly one swatch;
- each colored swatch holds at least one channel.

Otherwise it returns null and the general path runs.

**Fast path.** It runs v1.7's loop unchanged, with `k` as the order factors:
- `ChannelShift.fast(...)` in both Chromatic aberration filters;
- in Glitch, `splitRgb` (v1.7's loop verbatim) for RGB, and `split` with per-channel offsets `-k[c]*cs` for the other one-hot sets.

It is bit-exact with v1.7 for the six v1.7 orders: `FringeGoldenTest` checks this against F0's frozen copies. On the two-color presets it equals the general path byte for byte (`FringeGeneralTest`).

**General path.** P_i,c is sample i's alpha-weighted channel c (bilinear, coverage-weighted exactly as v1.7's `sample()`), a_i its alpha, and F_i,c channel c of swatch i.
- A = the largest a_i among the samples whose swatch is colored. A pixel with A under half a level is transparent.
- A covered channel (Σ_i F_i,c > 0): out_c = Σ_i w_i,c · P_i,c / A, with w_i,c = F_i,c / Σ_j F_j,c (`FringeColors.Mix.w`).
- An uncovered channel (no swatch has it; `Mix.uncovered[c]`): out_c = P_1,c / max(A, a_1), the middle sample's channel. Two cases:
  - Where the colored samples are at least as opaque as the middle, this is v1.7's coverage rule.
  - Where the middle is more opaque, the channel is capped at the middle's own straight value, so it never comes out brighter than the middle.

In Chromatic aberration, `recombineMix` does this:
- It samples separably. The column data (left, right and fraction for each of the three samples) is computed once per picture, and the row data once per row, with `sample()`'s arithmetic.
- `BYTE_FLOAT` turns int-to-float conversions into a table lookup.
- The pixel loop has no branches on the colors. All three samples are always read, and a swatch's alpha weight is 1f or 0f.
- Each channel's divisor is `c_c / A + u_c / max(A, a_1)`, where c_c and u_c are the 1f/0f covered and uncovered flags. That is exact for covered channels.
- It measures about 0.5x v1.7's time.

In Glitch, `mixRow` does this:
- When a pixel's three samples share their alpha, it uses 16.16 integer weights (`Mix.fixed`, each channel's three summing to 65536). A uniform picture stays exact, and A = a_1 there, so the uncovered rule changes nothing.
- Otherwise it uses the float formula inline, with the same per-channel divisor.
- `wrap()` skips `floorMod` when the index is already in range.

**Glitch static.** White uses v1.7's `addStatic` verbatim, so the bytes are v1.7's. Any other color uses `addColoredStatic`, which mixes toward `(n·255)·(S_c/255)`; the color's alpha scales the amount. It adds no random draws, so a seed keeps its bands and blocks.

**Control.** `FringeControl` shows:
- preset chips in one horizontally scrolling row (like v1.7's ChoiceChips), tagged `V18Tags.FRINGE_PREFIX + name`. Each chip shows three 6 dp dots, with a ring for no color. A chip is selected when its colors equal the current ones.
- three 40 dp `ColorSwatch`es, with descriptions FIRST, MIDDLE and LAST, and a state description of the hex or "No color";
- a `FringeLabels18.NO_COLOR` chip after the middle swatch.

A swatch tap opens the frozen `ColorPickerDialog`, unchanged and titled after the swatch. Every change is a whole step: `update`, then `onChangeFinished`.

Applying the filter is the ordinary filter session: one undo step that stores pixels.

**Budgets** (`FringePerfTest`, `Timing`; a small warmed picture, the median of interleaved batches, up to 5 tries):

| Path | Budget | Guarded against |
|---|---|---|
| Fast path | v1.8 dispatch ≤ 1.05x | its own `fast(...)` loop |
| General path | ≤ 1.3x (Glitch on CI: ≤ 1.6x) | the frozen v1.7 copies (Moving, Zooming, Glitch) |
| Colored static | ≤ 1.05x | the frozen v1.7 copy |

The fast path is guarded against its own loop because two separately compiled copies of the same loop measured 0.94 to 1.26x each other in one JVM. The ±5% against the v1.7 release is checked on the phone, together with Q9.

On CI the two Glitch rows of the general path assert 1.6x (`FringePerfTest.GLITCH_CI_LIMIT`); Moving and Zooming keep 1.3x. v1.7's Glitch row is three `Math.floorMod` divisions and masks, so its ratio measures the runner's division against its multiplies. On an EPYC 9V74 runner a loop that only loads the three samples and stores was already 1.24x v1.7, and lookup tables, branchless maxima and a deferred translucent loop gave nothing measurable on CI. The class comment has the runner table. The 1.3x target holds on the desktop, and the phone checks it: Q9, plus Glitch with a custom color set.

**Compatibility.** Nothing stores these values today (V11): Chromatic aberration and Glitch have no `pixelMapper`, so they are not adjustment-capable, and `FilterValuesCodec` is used only by adjustment layers. A v1.7 Chromatic aberration's "order" still opens as its colors.

**Tests.**
- `FringeGoldenTest`: bit-exact against the v1.7 frozen copies.
- `FringeGeneralTest`: the general path's formula, and the presets equal to the fast path.
- `FringeTranslucentTest`: the general path on translucent pictures, including the uncovered cap and Glitch's float branch.
- `FringeLegacyOrderTest`: the "order" mapping and the codec.
- `GlitchStaticColorTest`.
- `FringesParamCodecTest`.
- `FringePerfTest` (Timing).
- `FiltersLabelsTest`.
- `FringeControlUiTest`: 392 dp, the bare control.
- `FringePanelSessionUiTest`: 360 dp, the real panel. It covers slide-to-preset, the picker hex, Apply as one undo step each, and exact undo and redo.

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

## Shapes: roundness, Turn into path, several points (v1.7, area C: `tools/vector/Shape*`)

### Shapes: per-point roundness (v1.7 item 2, area C)
`ShapeAnchor.radius` (document px, null = none) is a point's own corner radius. `ShapePoints.outline` uses each corner's own radius first, then the shape's "Corner radius" when the corners are treated. With every radius null the outline is exactly v1.6 (`ShapeOutlineParityTest`).
- `ShapeRoundness` (pure Kotlin) handles targets, values, typed text (`*2`, `/2`), shifting and reset. Only a corner between two straight sides can be rounded (`ShapePoints.roundable`); otherwise the message is "Only corners between straight sides can be rounded".
- In the Shape tool's Points strip, "Point roundness" is a `MixedNumberField` with `MixedEdit.shifted`, next to "Reset point roundness".
  - `MixedNumberField` (`ui/points`) is `.width(IntrinsicSize.Max)`, as wide as its label, value, unit and arrows: in a horizontally scrolling strip the Row has no max width, and its weighted value text was given 0 width ("Point thickness" showed no value, "Point roundness" lost its unit). "Point roundness" is `widthIn(min = 220.dp)`. Guard: `Qa17ShapesVectorCornersUiTest`.
  - "Select several" and "Select all points" are 44 dp (`FilterChip` with `heightIn(min = 44.dp)` and `ToolIconButton`), as in the Curve tools.
- In a group, a point lying exactly on a gizmo handle wins the touch (closest wins, ties to the point), in the Shape tool, `CurveTool.groupDown` and Free deform alike: a tap toggles it with "Select several", and a drag moves the group instead of scaling it. The other handles, the knob and a pinch still scale or turn the group.
- Each edit is ONE in-tool step.
- I13: `ShapePoint.radius` is `@EncodeDefault(NEVER)`. A point without one writes no `"radius"` key, and `ShapeCodec.VERSION` is 2. Radii are saved with the shape (`ShapeCodec`) and survive save and load, also on a `VShape`.

### Shapes: Turn into path (item 6, §3.6)
`ShapeToSpline.convert(shape, selected)` returns a `ConvertResult`: a `VPath` with an order-4 `VSpline`, plus `selectedSplineIndices`. The conversion is exact:
- sharp vertices become sharp control points;
- curved segments become clamped cubic pieces;
- rounded corners become rational quadratic arcs with weight cos(φ/2), split when the weight would fall below `MIN_ARC_WEIGHT`;
- selected corners become editable arcs at their own cut or at `SELECTED_CUT` (25 % of the shorter side).

`ShapeTool.turnIntoPath()` ("Turn into path"):
- A pending edit is committed first, as its own step.
- The conversion is then ONE undo step, "Turn into path":
  - a shape layer becomes a vector layer holding the path; a live array is kept (the cache is `ArrayDraw.effectiveVector`) and so is its folder parent;
  - a shape object of a vector layer is replaced in place (same id, opacity and seed).
- The Path tool then opens the path through `CurveTool.openPath(layerId, objectId, select)` (body: area B).
- `turnIntoPathRefusal` refuses before anything is placed:
  - arrows ("Arrows can't become paths");
  - outlines painted by a tool that moves pixels (smudge, blur, watercolor), which a vector layer can't hold. The message is the v1.5 vector-layer one: `<Tool> outlines need a raster layer: choose "Plain line" or a painting brush`.
- On refusal the strip's button is disabled and shows the reason as a hint (`canTurnIntoPath`).

### Shapes: several points in Points mode (item 1, §3.1)
The selection is a `PointSelection` (tools/points), kept in each in-tool step (`PendingState`).
- "Select several" toggles points by tap and box-selects by a drag on empty canvas. "Select all points" and "Deselect all points" select or clear every point.
- Group edits go through `PointGroupMath` and `PointGizmo`: drag, handles with a 56 dp minimum, and pinch. Each gesture is ONE in-tool step.
- Smooth is three-state ("Mixed").
- `deleteSelectedPoints` refuses below the minimum with "A shape needs at least N points".
- `historyMark()` / `rollbackHistory(mark)` (§3.10) restore the options, the pending shape, its points, mode and selection, and the redo stack. A NEW shape that a Numbers field created after the mark is discarded.

### Shapes: the coordinate pill (§3.1, §3.9, §3.13)
`ShapePill.kt` holds the Shape tool's three pill sources, each owned for the tool's lifetime:
- `ShapePillPosition` (`PillPositionTool`): X/Y of "Point N", else "Selected points" (box centre), else "Center".
- `ShapeObjectScale` (`ScaledTool`): Scale in % of the selected points' box, or of the shape's own width and height about its centre. 100 % is taken when the shape opens or the selection changes.
- `ShapeObjectDeletion` (`DeletingTool`): "Delete selected points" (one in-tool step) or "Delete shape". Deleting a shape layer uses `deleteLayer` and is one controller step, which one undo restores with its data.

Each pill edit (`begin…end`) is ONE in-tool step. A never-placed pending shape is discarded as ✕ discards it (the frozen `undo()` holds in-tool steps only while `hasPendingWork` is true).

### Transforming data layers: shapes (item 11, §3.11)
`ShapeTransforms.mapped(shapeData, m)` maps a shape through `ShapeAffine.mapped`, so a shape stays a shape under every affine map; a skew gives a custom-points shape. `keepingWidths` keeps the stroke width, the corner radius, each point's radius and the outline's brush in document px, whatever the map's scale.

### Shape tool and folders (§3.8)
- A new shape with "Editable (own layer)" on goes into its own shape layer at `controller.structure.insertionPoint()`. With an open folder active, that is the folder's top child.
- With it off, or with a smudge or blur outline, the shape would paint the folder itself. It is refused with `FolderLabels.PAINT_REFUSAL` ("Choose a layer inside the folder to paint"):
  - on drag (`checkCanPlaceNew`);
  - on `ensurePending`;
  - in `update`, when the option is turned off while a new shape is pending; the option is then kept.
- The preview decisions never treat a folder as plain:
  - `isPlain(folder)` and `liveBrushForNewLayer(folder)` are false;
  - `newLayerPreviewInOverlay(open folder)` is false, so the preview is drawn inside the folder through a render override on it.

### Shape layers with a live array (I14)
- "Edit shape" replays the brush outline inside the `updateShapeLayer` draw (`ShapeTool.outlineReplay`), and `ArrayDraw.drawWithArray` repeats that draw per copy.
  - The replay uses `StrokeRaster.render` with the shape's brush (`VectorOps.brushPresetOf`), the session seed and the same samples as `VectorLayerRenderer`, ending with the last point once more.
  - Dabs are clipped and cut at the document ∪ `StrokeRaster.strokeBounds`.
- The source's pixels equal the same edit on a layer without an array, and the edit is one undo step.
- An outline whose tool moves pixels (smudge, blur, watercolor) is painted by that tool on the source alone, as in v1.6.
- While the layer is open for editing, `EditOverride` draws the edited shape once per copy, placed from `VectorOps.bounds(VShape(shape))` as `ArrayDraw.sourceBounds` places it. Its tile regions include `ArrayLayout.bounds`, and the overlay shortcut is off for arrayed layers.
- On an arrayed VECTOR layer, a brush-outlined shape previews as a guide rather than a live stroke. The commit re-renders the copies.

## Shapes in v1.8: the cog, object copies and the Shape strip (items 7 and 1a, area F: `tools/vector/Shape*.kt`, `tools/vector/CogGeometry.kt`, `ui/vector/Shape*.kt`)

### The cog and its v1.7 fallback

**Geometry (`tools/vector/CogGeometry.kt`, pure, thread-safe).**
- The cog is built on the unit circle and scaled into the box: x by w/2, y by h/2, so the tips touch the box edges.
- Values are clamped to the `CogParams` ranges (`clamped`): teeth 3..64, depth 0.05..0.5, tooth width 0.2..0.8, taper 0..0.9, hole 0..0.8. A non-finite value takes its default.
- Pitch p = 2π/N; base b = toothWidth·p; tip = b·(1 − taper); root radius r = 1 − depth.
- Tooth k is centred at −90° + k·p. The first tooth points up; the rest follow clockwise on screen.
- Each tooth has 4 vertices: root start, tip start, tip end, root end. The root arc between two teeth adds m − 1 vertices, with m = ceil(gap / 20°). The outer contour therefore has N·(3 + m) ≤ 320 vertices.
- The hole (hole > 0) is a circle of radius hole·r with 48 vertices. It runs counter-clockwise, so it stays a hole under both the non-zero and the even-odd rule.
- `ShapeGeometry.vertices(COG)` = `CogGeometry.outer`, the outer contour only.
- `CogGeometry.local` / `contours` = the outer contour with the shape's Corners (`ShapeGeometry.cornerPath`), then the hole, which has no corners.
- Budgets (`CogPerfTest`, Timing): a 64-tooth build ≤ 1 ms; a "Number of teeth" drag ≤ 8 ms per frame.

**In the tool.**
- `pickerTypes` = every type, so "Cog" is last in the dropdown. `hasCorners` includes COG.
- Points mode starts from `vertices(COG)`: the points are the teeth, sharp, and the outline is unchanged within 0.01 px.
- With a hole, Points is refused (D21). `ShapePoints` returns `CogLabels18.HOLE_REFUSAL`, shown as a toast and kept as state.
- Turn into path on a field-drawn cog with a hole (`ShapeToSpline.isCompound`) gives ONE plain compound `VPath`: non-zero fill, no spline, so the Path tool cannot open it as points. A `CogLabels18.COMPOUND_HINT` toast says so. The Shape strip's More row offers it although Points is refused ("The Shape strip" below).
- With a brush, `ShapeOutlines.brushContours` splits the outline into its contours: the teeth, then the hole. The commit strokes each contour, seeded with the shape's seed plus the contour's index (`commitContours`, D21). The live preview strokes the teeth and draws the hole as a guide, on a raster layer and on a vector layer. On a vector layer, `VectorLayerRenderer.shapeBrushReplays` replays every contour the same way (seed + index), so the hole's line shows there too (`CogBrushOutlineTest`).

**The v1.7 fallback (D23).**
- `ShapeObject.forWriting()` is called by `ShapeCodec.encode` and by `VectorCodec.encode` for each `VShape`. For a COG without its own points, it writes normalized `points` (the outer contour with corners, no hole) and sets `cogFallback = true`.
- v1.7 coerces COG to RECTANGLE and draws those points: the teeth, without the hole. A v1.7 save keeps a custom-points rectangle. Both hold once v1.7 re-renders or edits the layer: until then it shows v1.8's saved pixels (hole included), and a layer it never edited keeps the cog when it saves ("Formats and compatibility (v1.8)").
- On read, `sanitized()` drops the flagged points and the flag. It runs in `ShapeCodec.decode`, in `VectorCodec.sanitizedShapes` (inside `VectorCodec.decode`) and in `PayloadImport`. v1.8 therefore reopens the cog itself, hole included.
- A plain v1.7 shape round-trips through v1.8 byte for byte (`CogFallbackCodecTest`, `CogFallbackWriteTest`).
- A mirrored cog with a hole survives `ProjectRepository` save → load with the same data and pixels, on a shape layer and as a vector object (`ShapeCopiesSaveReopenTest`).

### Object copies: shapes

**Live copies (`ShapeTool`, I15).**
- A new shape takes the ruler's maps once, at its start point (`ObjectCopies.mapsAt`).
- Affine maps become the shape's `CopyMaps` (`openCopies`). They are stored with the shape on a shape layer or a vector layer and drawn per copy.
- A Perspective array is not affine, so its maps (`openPerspective`) are not kept; they bake on commit:
  - on a raster layer or an Editable shape, pixels on the current layer (the new shape is then not kept as a shape layer);
  - on a vector layer, one extra plain path per copy on the canvas beside the shape (`ObjectCopies.baked` with `bakeCanvas`: of up to 256 maps, many put a copy wholly off the canvas, where it paints nothing, and such a copy is dropped; I15).
  - A one-time hint (`MirrorLabels18.PERSPECTIVE_PIXELS` / `PERSPECTIVE_BAKED`) says which.
- A reopened shape keeps its stored maps even with the ruler off, and tapping a copy opens the shape. Every edit moves the copies along.
- `PendingState` carries the copies, so in-tool undo restores them.
- "Remove mirror copies" (`MirrorLabels18.REMOVE`, on the More row while `hasCopies`) is ONE in-tool step.
- Every commit with copies is ONE editor step that undo takes back whole and redo brings back exactly (`ShapeCopiesRedoTest`).

**Drawing (`tools/vector/ShapeCopies.kt`).**
- An affine map moves the path's points and control points exactly. A perspective map moves the flattened path (0.25 px tolerance).
- `eachSpecs`: one spec per copy, source first. This is how the vector renderer and `ObjectCopies.drawEach` draw a shape layer's copies.
- `unionSpec`: every copy's paths in ONE spec per item (fill, line, arrowheads), painted into a raster layer in one call, so translucent copies do not darken where they overlap.
- `mappedAll`: the brush guide with every copy.
- A shape layer's brush outline follows its copies:
  - on a move, `outlineReplay` replays it on every copy;
  - for a tool that moves pixels (smudge, blur), it is one stroke through `BrushTool.beginPath(..., maps)` (`ShapeLayerCopiesBrushTest`).
- The rulers' affine maps are isometries, so a mapped path stroked at the shape's width matches drawing the source under `canvas.concat(map)`.
- Budget (`ShapeCopiesPerfTest`, Timing): a box drag on a vector layer with 32 rotation copies, frame = move + document + overlays at 512 × 512, ≤ 33 ms per frame. The vector-layer preview draws its specs synchronously through the render override.

**Readers (I17).**
- `ShapeToSpline` reads only the source (`ObjectCopies.source`). It contains no `ObjectCopies.` and no `.copies`.
- `ShapeTool.converted` puts the live copies back on the resulting path, so Turn into path keeps them:
  - a new shape on a raster layer: two steps, the shape layer, then the vector layer;
  - a vector object: replaced in place, same id, ONE step.
- With a Perspective array, Turn into path places the source in a shape layer, then bakes the source path plus one plain path per copy on the canvas into the vector layer (`ShapeCopiesTurnIntoPathTest`).

### The Shape strip (§3.9)
- `ui/vector/ShapeStrip.kt` builds the converted two-row `StripSpec`.
- Row 1: Shape type 56, Shape style or Line ends 56, width 64 (AUTO 1: at 360 dp with the VECTOR cell it alone moves to the More row), Points 72.
- The More row holds the v1.7 handles plus the Points-mode actions.
  - "Stroke with" is a 40 dp icon menu; "Handles" is a 40 dp icon popup.
  - "Point roundness" (`ShapeRoundnessCell`) is a 96 dp scrub cell whose typed dialog is an overlay.
  - "Remove mirror copies" shows while the shape has copies.
  - "Turn into path" (`ShapeStripItems.TO_PATH`) shows in Points mode, and also for an open shape whose Points mode is refused (`ShapeStripInfo.toPathShown`, `ShapePoints.refusal`): a cog with a hole (D21). Before the v1.8 integration it showed in Points mode only, so a holed cog could not reach its Turn into path. A tap gives one compound plain `VPath` and the `CogLabels18.COMPOUND_HINT` toast. A cog without a hole keeps v1.7's rule: the item waits for Points mode.
  - `CogTurnIntoPathStripTest` (`qa18`, 360 dp) covers both cogs.
- The cells are in `ShapeStripParts.kt`. Every icon cell, dropdown cells included (`ShapeMenuCell`), shows its label on a long press (`Modifier.longPressLabel`).
- The Shape settings and Numbers sheets are `overlays`, so they outlive the More row. Each item calls `StripContext.closeMore` before it opens a sheet (a no-op since v1.9, R3: the sheet covers the row, which shows again when it closes).
- `movedInto` maps the v1.7 handles that now live in a dropdown or popup.

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

## Curves: points, conversion and paint (v1.7, area B: `tools/vector/CurveTool.kt`, `tools/vector/spline/`)

### Path conversion (area B, v1.7)

**Where.** `tools/vector/spline/SplineBezier.kt` (`Converter.span`).

**Constant widths.** A span whose control-point widths are all equal (`constantWidth()`) converts exactly as in v1.6: the exact Bézier for order ≤ 4 with constant weights, otherwise the fitted pieces with v1.6's `CHECK_SAMPLES = 16` and `MAX_PIECES_PER_SPAN = 16`. The v1.6 digests (`NurbsPiecesTest` V16_EXACT / V16_ALL over uniform-width splines) were checked bit for bit against 22a791d's converter.

**Varying widths (item 5).** After the geometric conversion, each piece is checked at `WIDTH_CHECK_SAMPLES = 8` parameters:
- The true width there is the rational blend `h[3]/w`, from homogeneous de Casteljau.
- It is compared with what `CurveGeometry` draws: a smoothstep blend of the two anchor widths at the ARC-LENGTH fraction of the sample. Arc length comes from 16-step flattening of the piece.
- The piece with the worst miss above `WIDTH_TOL = 0.01` (relative) is split by homogeneous de Casteljau at the parameter of its worst sample. Every new anchor carries the exact width there.
- Splitting stops at `WIDTH_MAX_PIECES_PER_SPAN = 32` pieces per span. At the cap, the residual error between anchors is accepted.

Result: a 3-point Path at 0/100/0 % draws a lens immediately (v1.6 drew nothing below 5 points).

**v1.6-saved paths.** They keep their stored subpaths, and their old look, until their first edit; then they re-convert. They still reopen as Paths: I9's `curvesClose` ignores widths.

**Sharp points and prepend.** Sharp points split the spline into pieces (`NurbsGeometry.pieces`, F3). Each sharp point is one anchor with broken tangents; the ends are always sharp. Path prepend (§3.19, F3): with the first point of an open Path (≥ 2 points) selected, a tap inserts at index 0 and keeps it selected. Curve and Polyline still append.

### Points editing in the curve tools (area B)

**Selection.**
- `CurveTool` (Curve, Polyline, Path) implements `PointEditor`. Its selection is a `tools/points/PointSelection` sized to `pointCount`. `primary` is the v1.6 `selected` / `selectedPoint`.
- "Select several" (`PointLabels.SELECT_SEVERAL`) makes taps toggle points. A drag outside the gizmo box draws a marquee (`Drag.MARQUEE`).
- With ≥ 2 points selected, a `PointGizmo` box is shown. A drag inside it moves the group (`Drag.GIZMO`, it wins over the marquee). A corner drag or a pinch inside the box scales the group about the opposite corner or the centre, honouring the pill's `pill.keepProportions`. A tap inside the box clears the selection.

**One in-tool step per gesture.**
- A group gesture snapshots the anchors or spline once (`GroupEdit`). Each move maps that base through one `Affine2` (`CurveGroupMath.mappedAnchors` / `mappedPoints`; handles move with their anchor; non-finite values are clamped).
- On lift it pushes ONE history entry (label "Edit path").
- The strip's group rows each push one entry: Width ("Mixed" via `Mixed.of`), "Sharp corner" (Mixed), "All points 100 %", "Delete point".
- I12: with one point selected and Select several off, the strip and gestures are v1.6's.

**Long paths.** Above `LIVE_OUTLINE_MAX_POINTS = 500` points, a group drag draws only the 1 dp centre line. The width outline is rebuilt on lift.

**Deletion.** `deleteSelectedPoints()`:
- every point selected: `deleteObject()`;
- one point: `deleteAnchor` (v1.6, no minimum);
- several points that would leave fewer than `MIN_POINTS = 2`: refused with a toast.

### The pill for curves (area B side)

**Interfaces.** `CurveTool` is a `PillPositionTool`, `ScaledTool` and `DeletingTool`, so the generic `coordinateSourceOf` routes it through its first branch.

**Pill label.** `pillPosition` is labelled:
- "Point N" with one point selected;
- "Selected points" with several (it moves the group's centre);
- "Center" with none (it moves the whole object).

`splinePointPosition` returns the same source in PATH mode.

**Scale row and trash.**
- The Scale X/Y row scales the selected points about their centre; with 0 or 1 selected it scales the whole object.
- The trash reads "Delete selected points" for a proper subset, otherwise "Delete path/curve/polyline" (`PillDeletion`).

**History.** `historyMark()` / `rollbackHistory(mark)` capture the tool's history epoch and session identity, so the app's 2- and 3-finger undo over the UI can roll back in-tool steps exactly.

**`openPath(layerId, objectId, selected)`.** Used by the Shape tool's "Turn into path": it selects the layer and the PATH tool, commits pending work, and reopens the object with those points selected; out-of-range indices are dropped.

### Paint mode (item 8)

`CurvePaint { STROKE, FILL, BOTH }` is derived from the existing `CurveSettings` (`stroke`, `fill`); there is no new document data.
- `setPaintMode` keeps the last stroke kind in `CurveSettings.lastStroke`, a tool preference (not I13).
- The segments are "Stroke only" / "Fill only" / "Stroke and fill". Fill needs a closed object or a Path.
- The stroke kind is a 2-item dropdown (Brush / Plain).

## Curve generators (v1.8 item 6, area E)

**Data.** `VPath.generator: CurveGenerator?` (`vector/CurveGenerator.kt`, `@EncodeDefault(NEVER)`) holds:
- kind, centre, size, angle, clockwise;
- turns, inner, growth, waves, amplitude;
- petalsN / petalsD;
- density ("Points per turn"; 0 = the kind's default).

`sanitized()` clamps the values (turns ≤ 50, density ≤ 64, petals 1..24). v1.7 drops the field and keeps an ordinary path (once it edits the layer: an untouched layer keeps v1.8's file, generator included).

**The kinds** (`tools/vector/gen/CurveGenerators.Analytic`, document px, y down; c centre, R size, α angle, dir = +1 clockwise):
- Archimedean: φ in 0..Φ (Φ = 2π·turns), r = R·(inner + (1 − inner)·φ/Φ), angle α + dir·(φ − Φ). The outer end is at c + R·(cos α, sin α).
- Logarithmic: r = R·growth^((φ − Φ)/2π). Golden: growth = φ⁴ per turn (the golden ratio per quarter turn).
- Fermat: both arms through the centre as ONE curve, u in −1..1, r = R·u, angle α + dir·Φ·(u² − 1). It has no inner size.
- Sine wave: c is the middle, R the length along α, height·sin(2π·waves·t) across.
- Rose: r = R·cos(k·t) with k = n/d reduced, over one period (π·d when n·d is odd, else 2π·d), so it is always closed.

**The fits** (`CurveGenerators.build(g, kind): CurveBuild(subpath, spline)`):
- Curve (Bézier): an adaptive Hermite fit. Anchors start at parameter steps (Points per turn, default 4 per turn, wave or petal). Each anchor is smooth, with both handles h = P'(t)·Δt/3. The span that misses most (8 samples per span) is halved until every span is within 0.25 px, at most 400 anchors.
- Path (NURBS): Points per turn samples (default 8, at most 400) are interpolated exactly by the Path tool's cubic B-spline. It is clamped (order 4, endpoint on), with the end tangents from the derivative and a Thomas tridiagonal solve; the rose is cyclic (a cyclic tridiagonal solve). The subpath is the spline's Bézier form.
- Polyline offers no generators. `build` stays total (the Curve fit's anchors, made sharp).

**I16: the generator holds only while the geometry is its build.**
- `CurveTool.editGeometry` is THE one place where a hand edit clears the generator, in the same in-tool step (undo brings it back). The hint `CurveGenLabels18.EDITED_HINT` shows once per editor session, when the touch ends (a cancelled touch shows none). The Curve, Polyline and Path tools share it: `CurveTool.toastOncePerSession` asks all three.
  - These go through it: move, add or delete points, tangent handles, Sharp / Smooth, auto tangent, handle lengths, weights, order, Endpoint, Cyclic, Closed.
  - These do not: thickness, color, stroke.
  - Moving, turning or uniformly scaling EVERY point maps the generator instead (`mapGenerator`).
- On reopen, `CurveGenerators.matches` (0.01 px, on `ObjectCopies.source`) keeps the generator only when the geometry is its build:
  - A Bézier path must be a Hermite fit of the curve. Each anchor's parameter is read from its out-handle length (|h| = |P'|·Δt/3). The projection then refines it only while the anchor misses by more than tol/4, and moves at most half a span per step. Without that clamp, float rounding in a logarithmic spiral's sub-pixel inner turns threw a full Newton step to the curve's end (found by GeneratorPerfTest; covered by `GeneratorMatchTest.theMostTurnsAndPointsStillMatch`).
  - A Path must hold exactly the built spline.
  - Per-point thickness never counts.
- `VectorOps.transformed` (lead) maps the generator under a similarity and drops it otherwise.

**The session.**
- `CurveTool.startGenerator(kind, viewArea)` works only with nothing open, in Curve or Path. It places the curve at the centre of the canvas seen. The size is `GENERATOR_VIEW_FRACTION` (0.3) × the short side; a wave is twice that. It uses the last settings (pref `curve.generator.last`) and is one in-tool step.
- `changeGenerator(g)` rebuilds the open curve. Between `beginNumericEdit` and `endNumericEdit` a slider drag is ONE in-tool step, and the settings are saved at its end.
- `generatorHandles`: the centre (move) and the size handle (out scales, around turns), drawn in `drawOverlay`.
- The commit is one LayerDataAction or PixelEditAction, as for any curve.

**UI** (`ui/vector/CurveStrips.kt`, `CurveGeneratorSheet.kt`):
- While nothing is open, the "Curves" dropdown leads row 1. Visible text `CurveGenLabels18.CURVES`; tests reach it by its description `CURVES_DESC` (R15: "Curves" contains "Curve"). The entries come in `CURVES_MENU` order.
- While a curve is generated, the chip shows the kind's name, with description `CHANGE`. It is on row 1 for Curve (AUTO 1) and on the More row for Path.
- The sheet is titled "Curves", because the kind's name is already the chip's text. It shows `generatorSettings(kind)` and Points per turn.
- (v1.8 polish) Two `Hint`s, always shown so the sheet keeps its height. Each fits one line at 360 and 392 dp at the default font size; nothing caps them (no `maxLines`), so at a large font scale the longer `pointsAtLimit` may wrap and the sheet grows one line while it shows. Under Petals, `CurveGenLabels18.petalsDrawn(CurveGenerators.rosePetals(g))` ("Draws 8 petals" for 4 over 1: n·d even, n / d reduced, draws 2n). Under Points per turn, `pointsLimit(MAX_ANCHORS)` ("At most 400 points in all"), or `pointsAtLimit` ("At the 400-point limit: more add nothing") once `CurveGenerators.atAnchorLimit(g, kind)` holds. That is the fit's first spans reaching its cap (Curve, Polyline) or the spline's samples reaching 400 (Path), where a higher value builds the same curve. The 400 counts the curve's samples: an open Path at the limit holds 402 control points, the 400 samples plus the clamped spline's two end-tangent points (`Qa18PolishA11yVerifyCurvesUndoUiTest`, which also takes the line through the top row's Undo and Redo). Both texts are composed functions with a digit, not counted labels (`Qa18PolishA11yCurvesMathTest`, `Qa18PolishA11yCurves*UiTest`).

**Budgets** (`GeneratorPerfTest`, Timing; the T606 itself is checked on the device):
- A build of at most 400 anchors: ≤ 5 ms.
- The I16 check on reopen: ≤ 2 ms.
- A slider frame (the change and its outline, not the drawing): ≤ 8 ms.
- Local only: T606-size probes (a 614 px spiral; Q10's Golden drag; Q1's Kaleidoscope 8 commit at Points per turn 25, about 300 anchors, ≤ 50 ms).

## Mirrors for Curve, Polyline and Path (item 1a, area E)
- **Maps.** Taken once, at the path's first point (`ObjectCopies.mapsAt`), whatever the ruler does later. A reopened path keeps its stored copies, whatever the ruler is now. `CurveCopies.others` drops the identity and singular maps.
- **Live preview** (`CopiesPreview`). The path's plain items are drawn once per copy under `canvas.concat(M_k)` (homographies too). Brush strokes replicate through `BrushStrokePreview.request(maps)`.
- **Translucent paint on a raster layer** (preview and commit, `CurveTool.groupAlpha`). When the line alpha equals the fill alpha, all copies go inside ONE `saveLayerAlpha`, so overlaps never darken. When they differ, `groupAlpha` is 255 and each copy is composited on its own (known gap; the per-copy order then matches the vector renderer).
- **Commit.**
  - Vector layer: the path stores affine `CopyMaps` (`ObjectCopies.fromSymmetry`). Perspective copies are baked into plain paths, those on the canvas (`ObjectCopies.baked`, I15). The hint `MirrorLabels18.PERSPECTIVE_BAKED` shows once per editor session, shared by the three curve tools (`toastOncePerSession`).
  - Raster layer: every copy is painted in the commit's one step.
- **Reopen.** A tap on any copy's line reopens the source (`ObjectCopies.hits`).
- **Removing copies.** `MirrorLabels18.REMOVE` (More row) drops the open path's copies as one in-tool step; the generator stays (copies are no geometry).

## The curve strips (§3.9, area E)
- `curveSpec(tool, CurveStripFacts, CurveStripUi)` is pure and fully converted. The keys are `CurveKeys.*`.
- `curveFacts` clamps `points` to 2. The spec asks only "open" and "two or more", so a generator slider frame that changes the point count rebuilds no spec (`GeneratorRoundTripTest.aTurnsChangeKeepsTheStripFacts`). Keep every `points` test as `> 0` or `>= 2`.
- The paint modes are in the Stroke kind dropdown (`movedInto`); Order, Handles and Point weight are popups. Sheets and typing dialogs call `closeMore` (a no-op since v1.9, R3: a sheet covers the row); popups and toggles keep it open.
- Items that come and go sit after the popups in their line. (They were placed so while StripHost composed its cells by position. Since the merge it keys each cell by `StripItem.key`, so an item coming or going before an open popup no longer resets it: `StripHostStateTest`.)
- Tests:
  - `CurveStripRulesTest` (every state, pure).
  - `CurveStripTest` (360 and 392 dp, raster and vector).
  - `CurvesDropdownUiTest`, `CurvesLabelsTest`.
  - `GeneratorRoundTripTest` (5): save and reopen with copies, handle-drag undo / redo, Remove copies, the raster step, the strip facts, the edit hint once per editor.
  - `CurvePerspectiveBakeTest` covers the bake hint once per editor across the three tools.

**Formats and compatibility rows (a v1.8 file opened in v1.7):**

| What v1.8 wrote | What v1.7 shows | What a v1.7 save keeps |
|---|---|---|
| Curve, Polyline or Path with mirror copies (vector layer) | the source only | the source only |
| Perspective-array curves (baked) | identical | identical |
| Raster curve commits with mirrors | identical | identical |
| Generated curve | an ordinary path, identical geometry | the path, without its settings |

These rows are the design's, and the first and last hold only once v1.7 edits the layer. v1.7 first shows v1.8's saved pixels (copies included), and a layer it never edited keeps its copies and generator when it saves. "Formats and compatibility (v1.8)" has the corrected rows.

## Live arrays (`array/`, `tools/array/`, `ui/array/`, v1.7)

**Data vs cache.** A layer's array is DATA: `Layer.array: LayerArray(spec: ArraySpec, pixels: ArrayPixels?)`, carried in `LayerData`. Undo, duplicate and merge carry it with the rest of the data.
- For text, shape and vector layers the source IS the layer's own `textData` / `shapeData` / `vector`.
- For an array made from a selection, the source is `ArrayPixels`: the selected pixels cropped to their bounds, plus left/top.
- The layer bitmap is only the cache (I1).
- `ArrayLayout.matrices(spec, sourceBounds)` (pure) places the copies. Copy 0 is the identity.
  - **Line:** relative % of the source size plus constant px.
  - **Circle:** a centre, "Sweep" and "Rotate copies".
  - **Curve:** a guide `VSubpath` of at most 64 anchors, either drawn and fitted by `GuideEditor` or copied from a tapped path; plus "Copy spacing" and "Align to curve".
  - **Transform:** copy k = Mᵏ about the pivot.
- `sourceBounds` is always the source's CURRENT bounds, so relative offsets follow source edits.
- Limits: `MAX_COUNT` 200; "Apply array" is refused above 20 000 expanded objects ("Too many copies to apply: lower the count").

**`ArrayDraw` is the one draw seam** (`engine/ArrayDraw.kt`, frozen; v1.8: reads `ArrayInstances`, see Array instances and looks). Every cache writer draws through it, so no re-render can leave the copies out.
- `drawWithArray` wraps `updateLayerData`, `updateTextLayer`, `updateShapeLayer` and `addLayerWithContent`. It draws the copies k = N−1 down to 0, so the source is on top.
- `drawPixels` draws a raster cache (bilinear). In "Edit source pixels" mode it draws copy 0 only.
- `effectiveVector` is used by `VectorLayers`. Copy k of object `id` gets the id `id + (k shl 53)`; these ids are never encoded.
- `updateLayerData` clears and redraws `dirty ∪ cacheBounds(before) ∪ cacheBounds(after)`, both computed from the `LayerData`.
- **Background array renders (`array/ArrayRenders.kt`, v1.7 integration).** "Edit array" (`ArrayOps.edit`/`setArray`: handles, sliders, typed values, the sheet's buttons) on a raster, text or shape array calls `ArrayRenders.update`. It works like `updateLayerData` for the cache.
  - **Where it renders.** The cache renders on one worker (`limitedParallelism(1)`) when three things hold: the estimated main-thread cost (cost units × learnt ns per unit) is over 25 ms, the Array tool is current, and nothing else is under way. Otherwise it renders on the main thread. The worker draws from immutable inputs only (the after data, its `ArrayPixels`, text prepared on the main thread), never from the layer bitmap. It draws into a patch the size of the area (dirty ∪ cacheBounds before and after, grown 1 px). The patch is reused and released 3 s after the last render.
  - **Landing.** The main thread swaps the patch in together with the data as ONE step (undo-tile snapshot, SRC copy, `LayerDataAction`). Until then the layer keeps its old data and pixels, and the tool's preview shows the new copies. A preview changed after the commit (a finger still on a slider or handle) stays up through the landing, and its release is the next step. A commit that lands at once ends the preview it replaced. A two-finger history tap counts such a move as the tool's own change and takes it back.
  - **Ordering.** A pending render is a `DeferredStep`, so any other step, undo and redo land it first. So do `beginEdit` on the layer, the Array tool deactivating, and `settleVectorWork` (save, close, export, canvas ops). A newer "Edit array" on the same layer supersedes it: the old render is dropped with no step and gets `onDone(false)`.
  - **"Rendering array…"** shows once a render has run 300 ms (`isSlow`).
  - **Memory.** The patch is never document-sized unless the area is. If the patch is over maxMemory/8 or fails to allocate, the render falls back to the main thread. When that render is estimated over 300 ms, it first shows "Rendering array…" and waits two frames (at most 150 ms).
  - **Speed.** The estimate starts at 40 ns per raster pixel and 6 ns per text or shape unit (the JVM measured about 10–24 and 1–2, scaled by the phone factor). It learns from this device's renders: the whole-edit time for synchronous ones, and worker plus swap time for background ones.
  - Under Robolectric the policy is SYNC. Tests opt in with `policy`, `workerHook` and `patchBudget`.
  - **Accepted residual (lead, v1.7).** The §6.3 raster rows measure the whole render on the JVM: 200 copies of 500 × 500 on 4000 × 5000 took 1376 ms (budget 600 ms), and 64 copies of 1000 × 1000 took 1863 ms (budget 1.5 s). Those renders run on the worker; the main thread pays only the swap (about 8–15 ms). The budget is read as the main-thread hitch, and the wall-clock time on the T606 is a device check.
- Vector arrays go through the async vector renderer; meanwhile the Array tool shows "Rendering array…".
- Text and shape tool commits (`updateTextLayer`/`updateShapeLayer`), `makePixelArray` and `fromLayer` still render their caches synchronously.

**The bake in the same step (I14).** Any raster pixel edit of an arrayed layer bakes the array inside that edit's own step (`rasterizedContent()` drops it), with the toast "Array applied"; undo brings the live array back.
- The exception is a raster array in "Edit source pixels" mode (`ArraySpec.editingSource`): the cache is the source alone, painting changes the source, and "Finish source edit" takes the layer's non-transparent pixels as the new `ArrayPixels`.
- Opening the Array tool on such a layer finishes the edit.

**`ArrayOps`: one step per user action**, data and pixels together. The current tool is paused around each operation, and each operation re-reads the array after the pause.
- **Making ("Array"):**
  - `fromSelection` cuts the selection from the source and places "Array N" directly above it, at its folder level, through `controller.structure.placed` (rule S), all in one step.
  - `fromObjects` moves vector objects into a new vector layer directly above.
  - `fromLayer` arrays a whole text, shape or vector layer in place.
  - A new array is `ArraySpec()`: count 3, Line mode, relative X 100 %.
  - Undoing a new array layer selects its source again. `reselectOnUndo` is recorded BEFORE the insert, so its undo runs after the layer is gone.
  - Since v1.7, `AddLayerAction` and `LayerTreeAction` both restore the row that was active before an insert. In the usual case the source is already selected, and `reselectOnUndo` changes nothing. It stays because the array's source is not always that row: `paused()` runs the tool's `onDeactivate` after the source was chosen, and that may commit work that inserts and selects a layer; `fromObjects` takes `vectors.selectedLayer`.
- **`edit` ("Edit array").**
- **`apply` ("Apply array"):**
  - a vector array becomes real objects with new ids;
  - a shape array becomes a vector layer with one `VShape` per copy;
  - a text array becomes pixels, after "Apply turns the text into pixels";
  - a raster array becomes plain pixels.
- **`remove` ("Remove array")** keeps only the source.
- **`editSource` / `finishSource`.**
- **Refusals:** folders, adjustment layers and linked text frames; a plain raster layer without a selection; and the memory rule below.

**`ArrayTool`** (`ToolId.ARRAY`) owns the canvas handles (`ArrayHandles`), the guide input and the Array sheet (`ArraySheet`, `arrayStrip` (`ui/array/ArrayStrip.kt`), `ArrayCurvePicker`). Dragging a handle, or a slider or field, previews live (I7):
- the render override HIDES the layer: its display tiles re-render once, when the preview starts;
- the tool's overlay draws the copies over the canvas, in screen space, from a source proxy of at most 1024 px, at the layer's effective opacity;
- later frames re-render no tile, so a frame costs a few GPU bitmap draws instead of re-compositing every copy at document resolution on the CPU;
- the release commits ONE "Edit array" step;
- a vector commit keeps the preview up until its render lands; if the tool stops being current meanwhile, `showAll` hands the copies to the tiles;
- a two-finger history tap rolls a preview back (`historyMark` / `rollbackHistory`).

`ArrayTransforms` is the Transform tool's data lift. It maps an arrayed layer's spec AND its source, so the layer stays a live array. A text source needs D's `TextTransforms`.

**Canvas operations.** A vector array's spec is mapped (`ArraySpec.mapped`) along with its content. Every other array is baked, just as canvas operations drop text and shape data.

**On disk (no format bump).** An arrayed layer is written as a PLAIN RASTER entry (its cache, with no `textData`, `shapeData` or `vectorFile`), plus two keys:
- `"array"`: the `ArrayCodec` spec JSON;
- `"arrayFile": "array_<id>_r<rev>.bin"`: a BWAR container. It holds the magic "BWAR", a version, a kind, then the source:
  - PIXELS: `LayerCodec` bytes plus left/top;
  - VECTOR: `VectorCodec` bytes;
  - TEXT / SHAPE: the codec JSON in UTF-8.

v1.7 rebuilds the source on load. A project saved in "Edit source pixels" mode reopens in it.

**What v1.6 sees.** A consistent raster layer showing the copies.
- Editing and saving there drops `array` / `arrayFile` (unknown keys). The container becomes an orphan, which v1.7's next save deletes.
- A damaged container loads as a raster layer, with "The array of layer “X” could not be read; its copies are kept as pixels".

**Memory.** `ArrayPixels` bytes count toward `doc.effectiveLayerCount` against `maxLayers` (`ArraySources.hasRoom`). A new array that would pass `maxLayers` is refused with "Not enough memory for another layer".

## Array instances and looks (`model/ArrayInstances`, `engine/ArrayDraw.kt`, `tools/array/`, `ui/array/`, v1.8)

**One list of copies.** `ArrayInstances.of(spec, source)` is pure and thread-safe. It is the only source of per-copy data for every reader.
- It returns `ArrayInstance(k, matrix, look: CopyLook?)` in DRAW order, k = n−1 down to 0, so the source is on top.
- The matrix is v1.7's `ArrayLayout.matrices(spec, source)[k]`. With a look it becomes `M_k·T(c)·R(turn_k)·S(size_k)·T(−c)` about the source's centre c, computed in double.
- With `spec.look == null` every matrix is bit-identical to v1.7 and every look is null (**I19**). The source (k = 0) never has a look.
- `ArrayInstances.bounds` is the union of the mapped copies. It equals `ArrayLayout.bounds` without a look.

**The look** (`ArraySpec.look: ArrayLook?`) has these fields:
- Start/End opacity, size and turn.
- `easing`, the Pace: Even, Speeds up, Slows down, S-curve = linear, t², 1−(1−t)², 3t²−2t³.
- `tint: List<VStop>`, at most `MAX_STOPS` = 8.
- `tintStrength`.

**Where each copy sits.** Copy k ≥ 1 sits at t = ease((k−1)/(n−2)), or t = 1 when n = 2. n counts the PLACED copies: a Curve array drops copies past the guide. Copy 1 is "Start" and copy n−1 is "End".
- `CopyLook(opacity, tintColor, tintStrength)` is what a copy's draw applies besides its matrix.
- `ArrayTint.sample` picks the tint color at t.
- `ArrayTint.apply` is the color math for vector restyling. `CopyLook.colorMatrix()` is the same tint as a 4×5 `ColorMatrix` for paints.
- `ArrayLook.sanitized` turns a look that changes nothing into null, and `ArrayCodec` then writes no look. `isIdentity` ignores the easing.

**The readers (`engine/ArrayDraw.kt`).**
- **`drawPixels` (raster cache).** Each copy draws with `paintOf(look, base)`: the bilinear base paint, alpha × opacity, and a `ColorMatrixColorFilter` when tinted.
- **`drawWithArray` (text, shape and `addLayerWithContent` caches).**
  - A copy that `changesPixels` (opacity < 1 or a tint) draws inside its own `saveLayer`.
  - The layer's bounds are the copy's mapped source bounds, grown by `layerMargin` = max(4, 10 % of the longer side).
  - Other copies draw directly, as in v1.7.
- **`effectiveVector` (R12).** Each copy's objects are restyled: colors through `ArrayTint.apply`, alphas × the copy's opacity. Copy ids remain `id + (k shl 53)`.
- **Opacity 0.** Every reader leaves out a copy at opacity 0: draw, vector copies, bounds users, apply and export.
- **Bounds.** `ArrayOps` and `ArrayRenders` (cache bounds, background renders) read `ArrayInstances.bounds`, so a growing size gradient never clips.
- **Apply bakes the looks.**
  - A vector array becomes restyled objects placed by the instance matrices.
  - A shape array becomes one restyled `VShape` per copy.
  - Text and raster arrays become pixels drawn as above.
- **Export** (`ExportSceneBuilder`, SVG/PDF) writes each copy restyled. A text copy's opacity applies per part (glyph runs and decorations), because ExportScene has no group item.

**The preview and the draft rule (`ArrayTool`).**
- The overlay preview builds one `Frame`: the instances' screen matrices plus one `Paint` per copy from `ArrayDraw.paintOf`.
- **Draft rule (§3.3 c).** A slider or handle may drag a preview of more than `DRAFT_ABOVE` = 64 instances with a tint. The copies then show their opacity, size and turn but WITHOUT the tint (`previewIsDraft`).
- The release, a rollback and every discrete commit (chips, Reset look, typed values) are never drafts.

**History.**
- `ArrayOps.labelFor(old, new)` names a step "Array look" (`ArrayLabels18.HISTORY_LOOK`) when only the look changed, and "Edit array" otherwise.
- A field previews while it moves and records ONE step on release (`preview` / `commitPreview`).
- A chip and "Reset look" are one step at once.

**The sheet.**
- `ArraySheet` has a Placement | Look switch (`ArrayLabels18.PLACEMENT` / `LOOK`).
- `ArrayTool.sheetTab` keeps the tab when the sheet closes and reopens. It resets to Placement on another layer.
- `ArrayLookTab` (tag `V18Tags.ARRAY_LOOK`) holds:
  - **Field pairs:** Start/End opacity 0..100 %; Start/End size `ArraySpec.MIN_SCALE..MAX_SCALE` as %; Start/End turn ±3600°.
  - **Pace:** one ChoiceChip per `ArrayEasing`.
  - **Tint colors:** the filters' `GradientEditor`, 2..8 stops; a 9th is ignored. With no tint it shows the colors kept in `plainTint` (below), else the default warm-to-cool stops. A screen reader reads each stop as `ArrayLabels18.tintColor` ("Tint color 2 of 3, #RRGGBB"), Selected when it is the selected stop (the editor's `stopDescription`).
  - **Tint strength:** 0..100 %.
  - **Reset look:** enabled when there is a look, the Pace is not Even or `plainTint` holds colors.
- **Two tint rules** stop a tint from doing nothing:
  - editing the colors while the strength is 0 sets it to 100 % in the same step;
  - a strength above 0 with no stops takes the default stops.
- **Pace with no look.** A Pace alone is no look (`ArrayLook.isIdentity` ignores the easing) and records no step.
  - `ArrayTool.plainPace` keeps the chip lit.
  - The tab edits through `ArrayTool.previewLook` / `commitLook` / `resetLook`. They start from `lookBase()`: the look, or else no look at `plainPace`.
  - They keep `plainPace` in step with the look's Pace, so a look edited back to nothing keeps its Pace for the next edit.
  - "Reset look" and another layer bring it back to Even.
- **Tint with no look.** A tint at strength 0 is no look either (`sanitized()` drops it).
  - `lookBase()` and the bar also start from `ArrayTool.plainTint`, the last stops chosen, so a strength raised again (dragged through 0 or typed) finds the same colors.
  - "Reset look" and another layer forget them.

**The strip (`ui/array/ArrayStrip.kt`, `arrayStrip`)** is a converted `StripSpec`: one row 1 of 236 dp, with no More row and no toggle at 360 and 392 dp, with or without the VECTOR cell.
- **`array.settings`, 120 dp.** A chip showing "Line × 3", spoken "Array settings", that opens the sheet. While a raster source is being edited it reads "Finish source edit" instead, one step.
- **`array.rendering`, 112 dp.** "Rendering array…" while `ArrayTool.rendering`. Its room is kept, so the chip never moves.
- **Without an array: `array.hint`, 236 dp.** The how-to hint, up to 2 lines. It is a row-1 item rather than `StripSpec.hint`, which would force the toggle.
- **Text sizing.** Each text auto-shrinks (down to 8 sp) to fit its declared width.
- **Overlays.** `StripSpec.overlays` hosts the Array sheet and the "Apply turns the text into pixels" dialog.
- ArrayToolOptions.kt is gone.

**Costs.**
- With no look, the copies cost what they did in v1.7: `ArrayLookPerfTest` holds drawPixels and drawWithArray within 5 % of the v1.7 draws at T606 sizes.
- A look costs its paints and nothing more.
- A draft frame costs about a plain frame.
- The host runs `ColorMatrixColorFilter` at about 1.4 µs/px. The absolute tinted budgets are therefore device checks: §6.3 Q5 and Q6, and 32 tinted text copies ≤ 320 ms.

**On disk.**
- The look is saved in the array spec JSON (`ArrayCodec`). An identity look is not written.
- `ArrayLookPersistenceTest` saves and reopens all four source kinds with a look: same spec, same caches, and still live after reopening.
- A v1.8 file opened in v1.7 shows plain copies, and a v1.7 save loses the look (Formats row "Array with a look"). More exactly: v1.7 first shows the cached copies with their look and draws plain copies only when it re-renders the array; its first save drops the look from the spec even if the array was never touched, while the container keeps the looked pixels until a re-render ("Formats and compatibility (v1.8)").

## Transforming data layers (v1.7, items 11 and 9)

The Transform tool (`tools/transform/TransformTool.kt`) asks an `ObjectLiftProvider` before its v1.6 pixel lift. What was lifted is `TransformTool.Lifted`: `PIXELS`, `PLACEMENT`, `VECTOR`, `TEXT`, `SHAPE`, `ARRAY` or `FOLDER`.

**Data layers** (`DataLift.kt`)
- `DataLiftProvider.kindOf(layer)` chooses the kind:
  - an arrayed layer is `ARRAY`;
  - a text is `TEXT` when `textCanMap(identity)`;
  - a shape is `SHAPE` when its map of the identity is not null;
  - anything else keeps the pixel lift.
- The maps come through the internal `DataMaps` seam. `RealDataMaps` calls D's `TextTransforms`, C's `ShapeTransforms` and E's `ArrayTransforms`. Tests inject fakes with `TransformTool.dataMaps`.
- The preview is the lifted pixels drawn by the box matrix, as in v1.6.
- On ✓, `LiftGeometry.matrix(state, left, top)` gives a row-major 3 x 3. The map turns it into new data, and `DataRender.apply` stores it through `controller.updateLayerData`. That is ONE `LayerDataAction`: it checks the effective lock and re-renders the layer from its data, so the pixels are always the data (I1).
- A text or shape map that declines falls back to the pixel resample: the layer becomes raster in the same step, as in v1.6. An array map that declines refuses with "Apply the array to transform it".
- Vector layers map their objects with `LayerDataTransforms.mapped` ("Transform objects").
- A text is proportional only (`uniformOnly`): no side handles and no flips. An arrayed text lifts as ARRAY, so `DataLift.uniformOnly` and `FolderLift.uniformOnly` treat an ARRAY with `textData` as a text too (`ArrayTransforms.mapped` refuses non-uniform maps for text). A shape skewed or scaled unevenly becomes custom points (area C).
- A raster array in "Edit source pixels" mode lifts as pixels: `DataLiftProvider.kindOf` returns null for it, and `TransformTool.objectProviderFor` routes to the data lift only when `kindOf` is ARRAY. The pixel commit keeps the array through `LayerData.rasterizedContent` (no bake). `FolderLiftProvider.lift` likewise refuses only a child whose kind is ARRAY, so such a child in a folder goes as pixels.
- Linked frames (lead decision):
  - A SCALE of one frame scales the whole story's type.
  - The re-flow that `TextThreads.onEdited` heals is folded into the Transform step with `amendLastStep`, so there is one undo.
  - A pure MOVE changes only that frame.
- `modeRefusal(mode)` gives the captions:
  - Distort: "Rasterize to deform" on text and shapes; the array caption on arrays; "Distort works on one layer" on folders.
  - Free deform: "Rasterize to free deform" on text, shapes and vectors; the array caption on arrays; "Free deform works on one layer" on folders.
- `canRasterizeFor` / `rasterizeAndDeform` back the "Rasterize and deform" button. The pending transform and the rasterize ("Rasterize text" / "Rasterize shape") are their own steps, then the pixels are lifted again in the new mode.
- No codec change: the stored text, shape, vector and array data are v1.6 formats, and save→reopen is exact.

**Folders** (`FolderLift.kt`)
- `FolderLiftProvider.lift(folder)` takes every non-folder, non-adjustment layer of the block. Each child gets a `ChildRule`:
  - `DATA` (by `kindOf`);
  - `VECTOR`;
  - `PIXELS`.
- Each child's content rect is cropped. The preview is a `MultiLayerRenderOverride` of all children.
- Lift, commit and delete check every child before anything changes:
  - `checkUsable(child, allowHidden = true)`, which covers its own lock or a folder inside, with the standard message (I11);
  - the transparency lock ("Transparency is locked on \"X\". Unlock it to transform.");
  - each arrayed child's map, computed up front.
- Any failure refuses the whole step with nothing changed.
- The commit is ONE `groupUndo("Transform")` CompositeAction holding one `LayerDataAction` or pixel edit per child. A text inside makes the whole folder proportional only.

## Free deform (v1.7, item 16)

Free deform is `TransformTool.Mode.MESH`, shown as the "Free deform" mode chip. It works on one pixel layer. Text, shapes and vectors are rasterized first with "Rasterize and deform"; arrays and folders are refused.

**The mesh** (`MeshDeform.kt`)
- An immutable grid of (cols + 1) x (rows + 1) vertices over the lift rect, with structural equality, up to `MAX_CELLS` 12 x 12.
- Surfaces are Catmull-Rom patches (C1 across cells) when "Smooth mesh" is on, bilinear cells otherwise.
- `resampled(cols, rows, smooth)` evaluates the current surface on a new grid, so changing "Mesh columns" / "Mesh rows" keeps the shape. It keeps a chain to the base, at most `MAX_DEPTH` 6.

**Settings** (`MeshSettings`)
- The next session's columns, rows and smoothing, stored under `transform.mesh`.
- Default 3 x 3, smooth on.

**Drawing** (`MeshRenderer.kt`)
- The source is drawn from a 1 px transparent-padded copy, for clean edges, with `Canvas.drawBitmapMesh`.
- The preview uses `PREVIEW_SUB` 8 parts per cell (at most 97 x 97 points) and draws the full lift; there is no proxy.
- The commit uses `COMMIT_SUB` 16.

**Editing**
- Vertices use the shared points UI (`tools/points`): tap, "Select several", select all, the gizmo and the pill.
- Each drag, gizmo edit or "Reset mesh" is one in-tool step, `MeshEdit(mesh, ref, selection)`, on `meshUndo` / `meshRedo`.
- `historyMark()` / `rollbackHistory(mark)` (§3.10) return to a mark.
- A cell change resamples every stacked step to the new cells.
- A finger on a gizmo handle scales or turns the selection, unless a vertex within reach is as close or closer. Ties go to the vertex (`<=` in `meshDown`, as in `CurveTool.groupDown` and the Shape tool), so a vertex lying exactly on a handle is tapped (toggled with "Select several") and dragged as a vertex. Guard: `FreeDeformRobolectricTest.aVertexOnAGizmoHandleWinsTheTapAndTheDrag`.
- Outside Free deform, `TransformTool.historyMark()` returns a `LiftMark(session, state)` for a plain lift: the state is taken from the canvas gesture's or pinch's start when a canvas finger is moving the box. `rollbackHistory` re-applies it only while the same lift is pending and no canvas gesture is active. So a pill "Scale X" drag on an untouched lift followed by a second finger snaps the scale back and undoes ONE document step (`Qa17ChromePillScaleTapUiTest`).

**Commit**
- `applyMesh` draws the padded lift through the mesh into the layer: ONE pixel edit labelled "Free deform", and undo restores the exact pixels.
- It runs synchronously on the main thread, like v1.6 Distort; §3.16 asked for a worker.
- Budgets (§6.3, device): preview ≤ 33 ms per frame at 12 x 12 on the full lift; commit ≤ 1.5 s for 4000 x 5000.

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
- **Pending saves (§3.14 (c), integration).** `saveSelection` and `updateSavedSelection` compress on `Dispatchers.Default`.
  - Meanwhile each one is in `controller.pendingSavedSelections`: Compose state, oldest first, with `id` (a save's future id, or the updated entry's id), `name` and `update`.
  - A pending save counts toward the 32-entry limit and the "Selection N" numbering.
  - Each lands ONCE (an `AtomicBoolean`) as ONE step, in request order: a fast save waits for an earlier slow one.
  - A worker failure other than OOM drops the save, with a log line, instead of leaving it pending.
- **`landSavedSelections()`** lands every pending save at once, waiting for its work with `runBlocking`. It is called by:
  - `undo` and `redo`;
  - `HistoryLabels.performUndo` / `performRedo`, before the feedback label is computed, so it reads "Undo: Save selection";
  - `undoMarker`, and so `uiMark`;
  - `rollbackSteps`, and so `restoreUiMark` and `rollbackTo`;
  - `CanvasOps.run` before its snapshot, so the save is its own step before the operation and is mapped with the others;
  - `EditorSession.close` before the project save;
  - the start of rename, delete, update and load.
  - It returns at once inside an edit scope, during undo or redo, and while edit listeners are told.
- **Why it is not a `DeferredStep`:** a save is not owned by a tool. A flush inside another action's pushes could fold the save into that action's step and break I2, for example a placement's `foldWithAdd` followed by `mergeLastUndo(2)`, or `ContentAwareFillJob`'s depth-0 mark.
- **Consequence:** a save that finishes after a quicker later action (for example a stroke within the ≤ 300 ms window) records its step after that action's step, in completion order.
- **Test seam:** `beforeSavedSelectionPack`, a per-controller suspend hook that runs on the worker before the compression.
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
  - **Pending saves** show at once, on top and newest first, tagged with the id they will have (`V17Tags.savedSelectionRow(id)`). Each row shows the name and a spinner in the thumbnail slot, and has no clickable and no menu until it lands.
  - **A pending update** replaces the entry's ⋮ with a spinner, and the row cannot be tapped until the update lands.

## Selection tools: Magnetic lasso, Color select and their strips (v1.8 items 3 and 4, area C: `tools/select/`, `ui/selection/`)

### Magnetic lasso (v1.8 item 3, area C)

The tool is `tools/select/magnetic/MagneticLassoTool.kt`, with `EdgeMap.kt`, `LiveWire.kt`, `MagneticPoints.kt` and `Loupe.kt`. Its strip is `ui/selection/MagneticLassoOptions.kt`.

Settings (`MagneticSettings` in `tools/select/ToolSettings.kt`) persist under `select.magnetic` in `AppSettings`:
- Edge reach: 8..64 dp, default 24.
- Edge contrast: 0..50 %, default 10.
- Snap to edge: on.
- Refer: Layer.
- Anti-alias: on.

Nothing new is stored in documents; the result is an ordinary `Selection`. The user taps points around an object, and each segment between two anchors follows the strongest edge between them.

**Edge map** (`EdgeMap`). It is built on a worker (`Dispatchers.Default.limitedParallelism(1)`, newest wins) from `PixelSnapshot.take` of the pixels Refer names: the active layer, or the flattened canvas (a folder counts as the canvas).
- The proxy's long side is at most `MAX_SIDE` = 2048 px, about 2 document px of error on a 4000 x 5000 canvas.
- For each proxy pixel: the Rec. 709 luminance (transparent pixels count over white paper), then a 3 x 3 Gaussian blur, then the Sobel magnitude.
- A 256-bin histogram normalizes the result so the 99th percentile maps to 255. When edges cover under 1 % of the picture (line art on white), the 99th percentile of the pixels above noise is used instead. The reference is never under `MIN_REFERENCE`, so noise never becomes a strong edge.
- `grad` holds one byte per proxy pixel: 4 MB at 2048², and the build's peak is twice that. `half` is a half-resolution copy (each pixel is the strongest of its 2 x 2), built once on first need.

**When the map is rebuilt.** Its key (`MapKey`) names the pixels:
- for Refer: Layer, the active layer, its bitmap and its `contentVersion`;
- for Refer: Canvas, the sum of every layer's `contentVersion`, plus `layersVersion` and the document size.

`ensureEdgeMap` compares the key on activation, on a new Refer and on every finger-down. So the tool sees a stroke painted with another tool, a pixel undo or redo while the tool is active, a layer switch, and a vector render landing. (A pixel undo changes only `contentVersion`, not `layersVersion`.)

A new key cancels the running build, forgets every traced segment (of the outline and of its history) and starts a new build. Until the build lands, segments are drawn straight and dashed, and taps do not snap.

A build that runs out of memory shows the toast `MagneticLabels18.OUT_OF_MEMORY` once and keeps segments straight. It is not retried on every touch (`failedKey`); the next activation, or other pixels, try again. `MagneticLassoTool.takeSnapshot` is an internal seam that lets a test force the failure (`MagneticOutOfMemoryTest`).

**Live wire** (`LiveWire`, design D10). It runs Dijkstra with Dial's bucket queue on the 8-connected proxy grid.
- A step to pixel q costs (10 straight | 14 diagonal) x (1 + ((255 - g(q)) shr 3)). A g below the contrast floor (Edge contrast x 255 / 100) counts as 0, so a flat region costs the same everywhere (the path runs straight), and an edge is up to 32 times cheaper to follow.
- The search window is the box of the two anchors plus `MARGIN` = 48 proxy px. A window over `MAX_NODES` = 1 M nodes searches the half-resolution map, and the path is scaled back up.
- There is one instance per worker. Its arrays are sized to the largest window and reused, so a drag allocates nothing.

Tracing runs one segment at a time on the worker, newest state first: the live segment (last anchor to the finger), then the active anchor's two segments, then the rest. A result is stored only if its anchors have not moved meanwhile.

**Outline and steps** (`MagneticPoints`). It holds:
- the anchors, in document px;
- one traced segment per pair of anchors (x, y pairs, both ends exact; null means not traced yet);
- the active anchor;
- the in-tool history (copy on write, at most 200 states).

Each of these is one step:
- adding an anchor;
- moving one (a drag, or an X / Y pill edit);
- deleting one ("Delete point" or the pill's trash; the neighbours are joined by a new segment);
- the strip's "Discard outline".

The top row's Undo and Redo walk these steps and bring traced segments back without tracing again. `hasPendingWork` holds while there are anchors, or steps to undo. When an outline is taken back to nothing, its steps end once the tool is left.

**Input.**
- A tap places an anchor. With "Snap to edge" on, the anchor moves onto the strongest edge within "Edge reach" (in screen dp, so the reach is the same at any zoom), unless no edge there passes the contrast floor. It lands on that edge's nearest point: the nearest edge-map pixel at least half as strong as the strongest one within reach, then up to 2 px on across the edge, from its flank to its centre line (`snapped`). So the anchor moves across the edge, never along it, where a photo's edge is stronger in one place than another (v1.8 final QA, `Qa18SelectMagneticOutlineUiTest`). An anchor that snaps onto the last one (within 3 dp) is not added twice. A dragged anchor snaps the same way where the finger lifts.
- Press and drag shows the live segment to the finger.
- A touch within 24 dp of an anchor drags it, with a 6 dp slop. Its two segments are traced again, and a cancelled touch puts the anchor back. A tap on an anchor makes it the active anchor. A cancelled touch (a second finger: a pinch, a pan, a two-finger tap) is no tap: the anchor active before it stays active (v1.8 final QA, `Qa18SelectMagneticFingersUiTest`).

Closing traces any missing segment (and the closing one) on the worker, then commits through `LassoCommit.apply`. This makes ONE selection step "Magnetic lasso", combined by the selection mode. On a vector layer, the outline selects objects.

There are three ways to close:
- a tap on the first anchor;
- the strip's "Close outline", or the pending bar's ✓;
- a double tap (300 ms, 32 dp). With fewer than three anchors, the second tap does nothing.

With fewer than three anchors, nothing closes and the outline is dropped quietly. Leaving the tool closes the outline, because `Tool.onDeactivate` commits pending work; with fewer than three anchors it is dropped.

**Loupe** (`Loupe`, D12). It shows from finger-down to lift for every touch that places or drags an anchor, and for Color select's taps too.
- It is a 104 dp circle showing the canvas at 3x the view's zoom, rendered through `Compositor.drawDocument` with white paper under it.
- It shows the outline, the live segment, the faint reach ring and a crosshair.
- It sits 96 dp above the finger, and flips below it when the finger is within 140 dp of the canvas's visible top. It also flips wherever the circle above would reach under the top bars: 96 + 52 dp is 148 dp, so a finger 140 to 148 dp under them flips too (v1.8 final QA: at 392 dp the fitted picture's top edge sits 143 dp under the bars, `Qa18SelectMagneticLoupeReachOomUiTest`).

The canvas view is full-bleed under the editor's status bar, top row and options strip, so the visible top is the bars' lower edge. `Loupe.chromeTop(controller)` reads it from `EyedropperTool.chromeTop`, which `CanvasView.setFitInsets` already feeds (the eyedropper's color preview keeps clear of the bars the same way). It is 0 until the editor has measured its bars.

For tests, the tool state is `MagneticLassoTool.loupe` / `ColorSelectTool.loupe` (`center`, `flipped`, `focus`).

**`LassoCommit`** (`tools/select/LassoCommit.kt`, §4.7). This is the commit of a closed outline, shared by the Lasso (freehand, polygon, curve) and the Magnetic lasso.
- It rasterizes at full resolution on a background thread (`SelectionJobs.applyAsync`), combined with the selection that is current when it finishes.
- The busy overlay "Selecting…" shows after 400 ms.
- It makes one selection step, named by the caller.

It was moved out of `LassoTool` byte for byte. `LassoCommitParityTest` checks the masks against hashes recorded from the v1.7 code.

**Budgets** (T606; `MagneticPerfTest`, `Timing`, desktop x 4 on CI):

| Scenario | Budget |
|---|---|
| Edge map, 2048 px proxy | ≤ 400 ms, worker |
| Segment, ≤ 300 proxy px | ≤ 30 ms |
| Anchor drag retrace, two segments of ≤ 150 proxy px | ≤ 16 ms per frame |
| Commit, 4000 x 5000 | ≤ 300 ms |
| Memory, 2048² proxy (map, half map, search) | ≤ 12 MB |

**Tests.**
- `EdgeMapTest`.
- `LiveWireTest`: a disc's rim is traced within 1.5 proxy px.
- `LiveWireCapTest`, `MagneticAnchorUndoTest`, `MagneticModesTest`, `MagneticLoupeUiTest`.
- `MagneticEdgeMapRefreshTest`: a stroke, its undo and its redo while the tool is active.
- `LoupeChromeFlipTest`: the flip under the bars, for both tools.
- `MagneticStripNarrowTest`, `LassoCommitParityTest`, `MagneticPerfTest`.
- `MagneticOutOfMemoryTest`: the toast once, straight segments, no new try or toast on the touches that follow; the next activation tries again.

### Color select (v1.8 item 4, area C)

The tool is `tools/select/colorrange/ColorSelectTool.kt`, with `ColorRange.kt` (pure math), `ColorPreview.kt` (`ColorProxy`, `ColorPreview`, `ColorMasks`) and `ColorSamples.kt`. Its strip is `ui/selection/ColorSelectOptions.kt`.

Settings (`ColorSelectSettings`) persist under `select.color` in `AppSettings`:
- fuzziness: 0..200, default 40;
- preset: default Sampled colors;
- Refer;
- anti-alias;
- invert.

Nothing new is stored in documents; the result is an ordinary `Selection`.

**Lab** (D14). sRGB (D65) is converted to Lab through a 256-entry linearization table, the 3 x 3 matrix, and a 4096-bin f(t) table with linear interpolation. The result is within 0.01 ΔE of the closed form (`LabTableTest`, over a 32³ grid). `ColorRange.labQ` rounds it to hundredths, packed in a Long; the preview's cache and the commit both use it.

**Membership** (`ColorRange.member`) is 0..1 per color. F is the fuzziness.
- Sampled colors: d is the nearest sample's ΔE76. Membership is 1 up to F/2, 0 from F, with a smoothstep between. F = 0 selects only exact matches (d < 0.5).
- Presets, with w = 0.05 + F/1000:
  - The six hues (Reds 0°, Yellows 60°, Greens 120°, Cyans 180°, Blues 240°, Magentas 300°): 1 within 15 + 0.15·F degrees of the centre, 0 beyond 45 + 0.15·F, smoothstep between. This is multiplied by smoothstep(0.05, 0.2, S), so grays never count.
  - Highlights: smoothstep(0.65 - w, 0.65 + w, L). Shadows: 1 - smoothstep(0.35 - w, 0.35 + w, L). Midtones: 1 minus both, clamped. L is Lab L / 100.
  - Vivid colors: smoothstep(0.45 - w, 0.45 + w, S·V). Muted colors: 1 - Vivid.
  - Skin tones: hue 5..50°, S 0.15..0.7, V > 0.25. The S and V edges are softened by w, the hue edges by w x 100°.
- Then the value is inverted when "Invert range" is on, and multiplied by the pixel's alpha, so a transparent pixel is never selected. With anti-alias off, it is cut at half (0 or 255).

Nothing counts when the preset is Sampled colors, there is no sample and invert is off (`ColorRange.Params.selectsNothing`). In that state:
- the preview is empty;
- `ColorSelectTool.canSelect` is false and "Select colors" is off;
- a confirm (the pending bar's ✓ after "Clear samples", or a layer operation landing the steps) only ends the samples' steps. The selection is never replaced or intersected with nothing.

**Samples** (`ColorSamples`). There are at most 8; a 9th replaces the oldest. Ways to change them:
- A tap on the canvas adds the Refer color under the finger, as an opaque color. A transparent pixel or a point outside the document adds nothing. The loupe shows while the finger is down, and a swatch dot marks the tap point.
- "Use the current color" adds the brush color.
- "Add from the color picker" calls `StripContext.closeMore()` (§3.9; a no-op since v1.9, R3) and opens `ColorPickerDialog`; its OK adds the color.
- "Clear samples" removes them all.

Each change is one in-tool step (Undo / Redo, at most 100). ✕ (discard) goes back to the samples of the last confirm.

There are three ways to confirm: "Select colors", the pending bar's ✓, or a layer operation, export or share that lands pending work (as for every tool with steps). A confirm makes ONE selection step "Color select", combined by the selection mode. The samples stay for the next refinement; their steps end only once the selection has started. Leaving the tool selects nothing and ends the steps.

When the samples or the preset match no pixel, the confirm shows the toast `ColorSelectLabels18.NO_MATCH`, the `emptyMessage` of its `SelectionJobs.applyAsync` run. It adds no selection step, and the current selection stays, in every mode (`ColorSelectNoMatchTest`).

**Preview pipeline.**
1. The Refer pixels are reduced to a proxy whose long side is ≤ `PROXY_MAX` = 1280: a scaled copy of the layer, or `Compositor.renderThumbnail(1280)` of the canvas.
2. The proxy's Lab is cached once per proxy (`ColorProxy`: three `ShortArray`s plus the pixels for alpha and HSV, ≤ 16 MB at 1280²). It is built on `Dispatchers.Default` in 64-row bands over every core.
3. A change (settings, a sample, an undo) recomputes only the membership (`ColorMasks.previewMask`) on a worker, and the latest change wins: one job runs, and a "wanted" flag is picked up when it ends.
4. The result is published as `ColorSelectTool.preview` (`version` + 1, the proxy `mask`, an ALPHA_8 bitmap).

`drawOverlay` tints the preview red (0x80FF0000) over the canvas, with the samples' dots. It rebuilds the proxy when the Refer pixels changed under the tool. The proxy's key holds the source, the layer, its bitmap, the `contentVersion`s, `layersVersion` and the size.

**Commit.**
1. A `PixelSnapshot` of the Refer pixels is taken on the main thread.
2. `ColorMasks.fullMask` runs at full resolution in 256-row bands over every core. Each worker has its own row buffer and a 4096-entry direct-mapped color cache, plus a last-color shortcut.
3. `SelectionMasks.fromWindow` builds the selection.

All of this runs through `SelectionJobs.applyAsync` under the busy overlay ("Selecting…"). On a vector layer, it selects pixels (as Magic wand does), not objects. The preview and the commit share `labQ` and `member`, so the preview shows what will be selected. `ColorPreviewMatchesCommitTest` checks that the full mask's 2 x 2 averages are within 2/255 of the preview.

**Budgets** (T606; `ColorSelectPerfTest`, `Timing`, desktop x 4 on CI, photo-like pixels):

| Scenario | Budget |
|---|---|
| Preview after a change (1024 x 1280 proxy, 8 samples at F 200, presets; and the tool's own preview end to end) | ≤ 120 ms |
| First Lab cache, 1024 x 1280 | ≤ 250 ms |
| Commit, 4000 x 5000 (mask + selection) | ≤ 1.5 s, under the busy overlay |
| Proxy memory at 1280² | ≤ 16 MB (Lab ≤ 10 MB) |

**Tests.** `LabTableTest`, `ColorRangeMembershipTest`, `ColorPreviewMatchesCommitTest`, `ColorSelectUndoTest`, `ColorSelectNothingToSelectTest`, `ColorSelectNoMatchTest`, `ColorSelectPresetChipsUiTest`, `ColorSelectStripNarrowTest`, `ColorSelectPerfTest`.

### The selection strips (area C)

Every selection tool's strip is a converted `StripSpec` in `ui/selection/`:
- `SelectionStrips.kt`: Bucket, Eyedropper, Magic wand, Lasso, Select shape, Object select;
- `MagneticLassoOptions.kt`;
- `ColorSelectOptions.kt`.

The compact controls are in `SelectionStripControls.kt`:
- `SelectionModeDropdown` ("Selection mode", 56 dp, key `select.mode`): shows the mode's icon, carries the mode as its state, and holds the four v1.7 mode descriptions inside (`modesMovedInto`).
- `StripValueButton`: a small label over the value and a ▾. A v1.7 control is found by its v1.7 handle ("Tolerance 32", "Refer: Layer"); a v1.8 control by its name, with the value as its state. `fixed` keeps the button exactly its width and ellipsizes the value. `valueContent`, when given, is drawn in the value's place and never read out (the state says the value): Color select's sample swatches.
- `StripSliderChip`: a popup with the v1.7 slider and number field. A v1.8 chip has no − / + buttons.
- `StripChoiceChip`, `StripToggle`, `StripIconAction`.
- `CountedCloseButton`: the ✓ with "N pt" under it, enabled from 3 points.

Rules for these controls:
- Every dropdown and popup is opaque (`IbisColors.Menu`), anchored in its item, and closed by a second finger, so it never closes the More row.
- An item that opens a sheet or a dialog (the Selection menu, the color picker) calls `StripContext.closeMore()` first (a no-op since v1.9, R3).
- The More row's "Deselect" and "Selection menu" (a sheet raised through the strip's overlays) show only while the selection bar is hidden.

Rows, with widths in dp. AUTO items leave row 1 first when it is full.

| Tool | Row 1 | More row |
|---|---|---|
| Bucket | Tolerance 72, Refer 80, Close gaps 72 | Expand 72, Anti-alias 88 |
| Eyedropper | Sample 80, Size 64, Back to brush 104 | - |
| Magic wand | mode 56, Tolerance 72, Contiguous 104 | Refer 80, Anti-alias 88, shortcuts |
| Lasso | mode 56, kind 56; polygon / curve: Close, Discard (40, room kept while empty); curve point: sharp (AUTO 2), Delete (AUTO 1) | Snap to objects 160 (polygon, curve), Anti-alias 88, shortcuts; hint "Tap corners" |
| Select shape | mode 56, shape 80, 1:1 56 | From center 96, Snap to objects 160, shortcuts |
| Object select | mode 56, Refine edges 104, Cancel 40 (while busy) | Refer 80, shortcuts; hint |
| Magnetic lasso | mode 56, Delete point 40, Discard 40, Close 40 (188; room kept while empty) | Edge reach 72, Edge contrast 88, Snap to edge 96, Refer 80, Anti-alias 88, shortcuts; hint while committing |
| Color select | mode 56; Color range 92 (fixed, preset as state, a popup of 13 FilterChips; for Sampled colors the value is the samples as a row of outlined swatches, oldest first, `ColorSelectStrip.swatchDp`: 12 dp up to four samples, 6 dp for eight, in 62 dp; with no sample it is "None yet", `ColorSelectLabels18.NO_SAMPLES`; Vivid colors and Muted colors are cut by a letter or two, their state says them whole; v1.8 final QA, `Qa18SelectRangeChipUiTest` and its 360 dp twin); Fuzziness 64 (AUTO 1: a 44 dp opaque, full-width one-line slider panel right under the strip's row); Add from the color picker 40; Select colors 40 (off while nothing counts) | Use the current color 40, Clear samples 40, Invert range 96, Refer 80, Anti-alias 88, shortcuts; hint while selecting |

Row 1 room (`StripInventory.content`) is 316 dp at 392 dp and 284 dp at 360 dp, and 44 dp less with the VECTOR chip.
- Color select's row 1 is 308 dp with its gaps, so "Fuzziness" heads the More row at 360 dp (with or without the chip), and at 392 dp with the chip.
- The curve lasso's point actions leave row 1 at 360 dp and with the chip.

**Tests.**
- `SelectionStripTest`: every state at 360 and 392 dp, with and without the chip. It covers the rows, the inventory rules, AUTO items leaving, handles on screen, the menu sheet outliving the row, and Refer's dropdown keeping the row.
- `SelectionModeDropdownTest`.
- `SelectionLabelsTest`: §4.8 rule 4 over the rows, the More rows and every opener.
- The narrow tests listed above.

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
- The vector-layer eraser is never repeated. "Symmetry doesn't apply to erasing vector objects" shows once per tool, when the stroke lifts: `BrushTool.start()` only sets `eraserNoteOnLift`, and `onUp` shows the note if no toast came meanwhile (`onCancel` and `onDeactivate` clear it). The first finger of a two-finger tap or a pinch starts an eraser stroke that the second finger cancels, so a gesture that erased nothing no longer uses the note up. A partial pass over any copy removes the whole stroke in one step.

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
- "Done" (the Symmetry strip, the Ruler strip and the legacy `RulerToolOptions`) selects `EditorController.doneTool`: the tool the user left for the Symmetry or the Ruler tool when the rulers work for it (`assistReturnTool`, set by `selectTool` from `SymmetryGuides.showsFor`; going between the two tools keeps it), else `lastPaintTool`. So Curve → Symmetry → Done is the Curve tool again, also through the top row's Ruler panel ("Place on canvas", "Edit on canvas"). `lastPaintTool` itself is unchanged (Brush, Smudge or Blur).
- `SymmetryRulerSection` in the Ruler panel: "Place on canvas" closes the sheet and selects the tool.

**Budgets** (§6.3, asserted by `SymmetryBudgetRobolectricTest` with `PerfBudget.ms`). These are JVM numbers for stroke work per frame; the T606 times are device checks.
- Mirror, 100 px brush: about 2.7 ms (budget 16).
- 32 copies of a 50 px brush: about 2.9 ms (budget 33).
- 64 copies of a 64 px brush: about 5 ms (budget 16).

### Mirrors for raster tools, the clone and the guides (v1.8 item 1a, area D)

#### Clone with symmetry

**What a copy samples (D1).** Under a symmetry ruler, a clone stroke draws one copy for each map M_k after the identity.
- The maps come from `ObjectCopies.mapsAt(c, x, y)`, which is `SymmetryMaps.transforms` at the stroke's first point: identity first, row-major 3x3.
- Copy k samples through the conjugated map G_k = M_k·T(−o)·M_k⁻¹, where o is the clone offset (destination − source).
- A destination pixel q of copy k therefore shows S(q − A_k·o), with A_k the linear part of M_k. Copy k clones from the source spot mirrored by its own map. A perspective map uses the same composition as a homography.
- The original keeps v1.7's exact whole-pixel shader, so a clone stroke without symmetry is bit-identical to v1.7.
- Copies sample bilinearly (`FILTER_BITMAP_FLAG`).

**One buffer, drawn per copy.** A stroke still has ONE ALPHA_8 coverage buffer. `BrushTool` composites it once for the original, then once per copy (k = n..1, then the original). Copy k's paint is `CoveragePainter.masked(mappedCoverage, sourceShader_k)`:
- It is `ComposeShader(BitmapShader(coverage) under M_k, source, SRC_IN)`.
- It is filled only over the 32 px cells (`COPY_CELL`) that copy k has landed on, as runs of cells along each row.
- `CoveragePainter.drawMapped` applies the stroke's mode, opacity, grain and the selection mask in document space, as for the original.

The seam is `CoverageCopies` in `brush/StrokeHooks.kt` (`copyCount`, `copyMap`, `prepareCopy`, `copyShader`, `prepareCommitCopies`). `CloneSource` implements it.

**Clip to the document's image (`brush/CopyDocumentClips.kt`).** The coverage buffer is the document's size and its shader CLAMPs. Under a map that turns or tilts the document, a copy's runs reach places whose preimage lies off the canvas, where the clamp would smear the edge's coverage into streaks. Copy k therefore shows only over the image of the document under M_k:
- `covers(k, map, run)` is the fast path. It is true when the run's four corners map back onto the document on one side of the horizon, so no clip is needed (almost always).
- Otherwise `clip(k, map)` gives a cached Path. It is the mapped document rect for an affine map. For a homography, it is the document clipped to w ≥ ε and to w ≤ −ε (ε = 1e-3 of the largest |w|), with each part mapped.
- `BrushTool.drawCopyRun` clips to the run first, then to that path. The anti-aliased clip mask is then only run-sized, never layer-sized.
- The same code runs live (display tiles) and at commit, so the commit equals the preview.

**Sources and the snapshot.**
- Copy k's source rect is M_k applied to the original's source rect (`CloneSource.sourceRectOf`), rounded out, plus a pixel for bilinear sampling. A homography whose horizon crosses the rect reads the whole document.
- `readsOnlyOutside` checks every copy.
- *This layer* samples the layer directly. With copies, `prepareCommitCopies` always makes one commit copy of the union of the copies' sources first, transparent outside the document. The commit never reads pixels it has already changed.
- *All layers* uses v1.5's lazily filled composite snapshot, now filled for every copy's source rect, within the same snapshot budget (fallback: This layer).

**The cap.** `CloneTool.MAX_MAPS = 32`. `CloneTool.copiesCounted(type, maps, x, y, w, h)` decides the count at the stroke's start:
- Mirror, Kaleidoscope and Rotation count every map. A stroke starting in a corner may go on to where all of them show. Kaleidoscope n has 2n maps; Rotation stops at 32 divisions.
- Array and Perspective array count the maps that take the start onto the canvas (`copiesOnCanvas`). Their margin ring of cells is free, so an Array of 4 x 8 cells is 32.
- More than 32 refuses the stroke: no paint, no undo step, and the toast `CloneTool.tooManyMessage(type)`: `MirrorLabels18.CLONE_TOO_MANY` ("Clone stamp mirrors up to 32 copies"), or under the Perspective array (whose default cell always gives more than 32: 75 on 400 x 300, 235 on 1080 x 2340) `MirrorLabels18.CLONE_TOO_MANY_PERSPECTIVE`, which says to drag the cell's corners outward in the Symmetry tool (the only tool that shows and drags them): a bigger cell fits fewer copies on the canvas, and the Symmetry tool's "Done" then returns to the clone stamp (`CloneMirrorReviewTest`, `Qa18PolishMirrorsCloneUiTest`, `Qa18PolishMirrorsVerifyCloneUiTest`).

**Undo and the source marker.** All copies commit in one `PixelEditAction` step. Aligned and the offset belong to the original stroke; the copies follow it. The source marker (⊕) is drawn once, for the original.

**Known gap.** The buffer is document-sized, so parts of the original that fall off the canvas have no coverage, and their copies are missing.

**Path strokes with maps.** `BrushTool.beginPath(input, seed, draftBudget, maps)` places every copy's dabs into the same single buffer, right after the dab they copy (n maps give n times the original's dabs), through `DabMapping`. A stroke with copies is always exact, per the frozen contract: `draftBudget` is ignored and `updatePath` returns false. The design's `draftBudget / n` is a lead decision. Cancelling or committing clears the coverage under the copies too.

**Guides.** `SymmetryGuides.showsFor(id)` is true for the nine replicating tools: Brush, Eraser, Smudge, Blur, Clone stamp, Curve, Polyline, Path and Shape. `EditorController.drawOverlays` calls `drawFor` for the active tool, which draws the ruler's guides faintly while symmetry is on. The Symmetry tool keeps its full guides and handles.

**Tests:**
- `CloneMirrorTest`, `CloneMirrorEdgeTest`, `CloneMirrorReviewTest` (edge streaks under Rotation and a perspective array, the cap per ruler kind, alpha lock, layer mask, save and reopen);
- `PathStrokeMapsTest`, `MirrorGuidesForToolsTest`;
- `SymmetryClonePerfTest` (`Timing`): Q2 Rotation 8, Q2 along the canvas edge, Q3 Array 4 x 8, Rotation 32 overlap, guides overlay.

## Storage (`storage/`)
Each project is a folder in app-private storage: `project.json` (document + layer properties),
one compressed raw pixel file per layer/mask, and `thumb.png`. Saves are incremental (only layers
whose `contentVersion` changed). The editor autosaves every 45 s and whenever the app goes to the
background or the user returns to the gallery. v1.5 adds a `.vec` file per vector layer and the
mask spec / adjustment of each layer inside `project.json`; data that can't be read on open is
dropped for its layer only and listed in `Document.loadWarnings` (shown once by the editor).

v1.7 (final QA):
- **Folders after a v1.6 rename.** v1.6 refuses a format-3 project, but its gallery rename rewrites `project.json` with only the keys it knows: folder entries keep `"file": ""` and lose their folder keys. When `formatVersion >= 3`, such entries are read as folders (`isStrippedFolder(formatVersion)` / `folderSpec(formatVersion)` in `ProjectFile` / `LayerEntries`), and "The folder structure was repaired" is shown: the folders come back empty at the top level and every layer is kept. A format-1/2 project with an empty file name is still refused, as in v1.6, so a damaged entry never turns a v1.6 artwork into format 3. The gallery's layer count uses the same rule. Guard: `Qa17CompatV16ReaderRobolectricTest`.
- **Leaving the app.** `MainActivity.onStop` calls `EditorSession.saveOnLeaving()`. Like `close()`, it lands running work first (`settleVectorWork`: array renders, vector renders and queued Object bar actions; `landSavedSelections`), then saves, so a "Save selection" still compressing or an array still "Rendering array…" survives the process being killed in the background. "Save now" and the periodic autosave still save only what has landed (`ArrayRendersRobolectricTest` requires that a save does not land a render). Guard: `Qa17CompatPendingWorkOnStopUiTest`.

## Formats and compatibility (v1.7)

| Codec | v1.6 | v1.7 | What changed | Older readers |
|---|---|---|---|---|
| `ProjectFormat.VERSION` | 2 | **3** | folders | v1.0–v1.6 refuse 3 |
| `writtenVersion` | 1 or 2 | 1, 2 or **3** | 3 iff any folder (I14) | |
| `TextCodec.VERSION` | 4 | **5** | `kerns`, `fontKerning` | ignore them; show pixels until edited |
| `ShapeCodec.VERSION` | 1 | **2** | `ShapePoint.radius` | ignore it |
| `VectorCodec.VERSION` | 1 | 1 | `VSplinePoint.sharp`, `ShapePoint.radius` inside `VShape`, `VStroke.copies` (all NEVER-encoded) | ignore them (`ignoreUnknownKeys`); no new object kind |
| `Payload.VERSION` | 1 | **2** | `PayloadLayer.parentId`, `folder`, `folderOpen`, `array: PayloadArray?`; `PayloadKind.FOLDER` | no version check; FOLDER coerces to RASTER |
| `ArrayCodec` | – | 1 | new (spec JSON plus the BWAR container) | |
| saved selection file | – | raw Deflate of ALPHA_8 rows | new | |

New file families: `array_<id>_r<rev>.bin` (BWAR container, arrayed layers of any source kind) and `sel_<id>_r<rev>.bin` (a saved selection's packed bytes). A folder writes no pixel or mask file (`"file": ""`). `deleteUnreferenced` and `ProjectRepository.duplicate` know both families.

What v1.6 does with a v1.7 project (the final compat QA checked these rows against the v1.6.0 build; the README states them for users):

| Construct | `formatVersion` | v1.6 opens it? | v1.6 shows | v1.6 edits or saves | Back in v1.7 |
|---|---|---|---|---|---|
| Folder | 3 | **No**: "This artwork was saved by a newer version of Brushwork" (its gallery rename strips the folder keys) | | | after a v1.6 rename: "The folder structure was repaired" |
| Array (any source) | 1 or 2 | yes | the copies (the cache), as a raster layer | raster editing; a save drops `array`/`arrayFile`, and the container becomes an orphan | a raster layer with the copies; the orphan is deleted on the next save |
| Saved selections | 1 or 2 | yes | nothing | a save drops them; the files become orphans | gone |
| Symmetry | 1 or 2 | yes | nothing | a save drops it | Off |
| Sharp path point | 1 or 2 | yes | the stored subpaths: corners correct | the I9 check fails, so the path reopens as a Bézier path in the Curve tool | unchanged if not edited, else a Bézier `VPath` |
| Per-point roundness | 1 or 2 | yes | the committed pixels | a re-render uses the single radius; a re-encode drops `radius` | as saved |
| Kerning | 1 or 2 | yes | the committed pixels | editing re-lays out without kerning; a re-encode drops it | as saved |
| Symmetry vector stroke (`VStroke.copies`) | 1 or 2 | yes | the committed pixels, copies included | a re-render of that layer draws only the original stroke; a re-encode drops `copies` | as saved |
| Fill/Stroke/Both, Pathfinder, transforms, Free deform, Shape to path | 1 or 2 | yes | identical (plain `VPath`/`VShape`/pixels) | identical | identical |

Folders alone bump the format: v1.6 would draw a folder's children flat, treat the folder entry as a raster layer without a pixel file, and its save would destroy the tree. Every other construct has a correct pixel cache that v1.6 already shows.

## Formats and compatibility (v1.8)

v1.8 changes no codec version and adds no file family. `ProjectFormat.VERSION` stays 3 and `writtenVersion` stays 1, 2 or 3 (I14); `TextCodec` stays 5, `ShapeCodec` 2, `VectorCodec` 1, `Payload` 2 and `ArrayCodec` 1. v1.7 therefore opens every v1.8 project. Every new field is `@EncodeDefault(NEVER)` (I13), and v1.7's decoders ignore it (`ignoreUnknownKeys`, `coerceInputValues`):

| Codec | New in v1.8 | v1.7 reads |
|---|---|---|
| `VectorCodec` (`VPath`) | `copies` (a `CopyMaps`, as 9-float lists), `generator` | ignores both |
| `ShapeCodec`, and a `VShape`'s `ShapeObject` | `copies`; `type` COG with `cogTeeth`, `cogDepth`, `cogToothWidth`, `cogTaper`, `cogHole`; the fallback `points` with `cogFallback` | ignores the new keys; COG coerces to RECTANGLE, which draws the fallback points |
| `ArrayCodec` spec | `look` (an `ArrayLook`; an identity look is not written) | ignores it |

The fringe colors of Chromatic aberration and Glitch are not persisted: neither filter can be an adjustment layer (no `pixelMapper`), and the filter recents keep only filter ids.

Two facts of v1.7's storage decide what v1.7 shows and keeps (`ProjectRepository`, `LayerEntries`, unchanged since `885793b4`, which is the v1.7.0 code):
- **Open re-renders nothing.** Each layer shows its saved pixel cache, as v1.8 drew it. v1.7 redraws a layer from its own model only when an edit of that layer re-renders it. On a vector layer that redraws only the changed tiles (`ContentDiff.changedTiles`), so v1.8's pixels can stay in the tiles v1.7 never redrew.
- **Saves are incremental.** A layer v1.7 never edited keeps its `.vec` file, its `shapeData` string and its array container as v1.8 wrote them, so its v1.8 keys survive. A layer v1.7 edited is re-encoded by v1.7's codecs, which drop them. The one exception is the array spec: `project.json` re-encodes it from memory on every save, so its `look` is lost at v1.7's first save even when the array was never touched.

What v1.7 does with a v1.8 project (the rows of design §8.1, checked against the code; the frozen v1.7 decoders in `compat17` check the decoding, `CompatV17DecodeTest`):

| What v1.8 wrote | What v1.7 shows | What a v1.7 save keeps | Back in v1.8 |
|---|---|---|---|
| Curve, Polyline or Path with mirror copies (vector layer) | the committed pixels, copies included; an edit of the layer redraws its changed tiles with the source only | the copies, while v1.7 never edited the layer; after an edit, the source only | as saved |
| Shape with mirror copies (vector or shape layer) | the same: the committed pixels, copies included; a re-render draws the source only | the same: the copies until v1.7 edits the layer, then the source only | as saved |
| Perspective-array curves and shapes (baked) | identical | identical | identical |
| Detached copies (`ObjectActions.detachCopies`) | identical (plain objects) | identical | identical |
| Raster commits with mirrors, clone results | identical | identical | identical |
| Selections from Magnetic lasso and Color select | identical (a selection or saved selection is pixels) | identical | identical |
| Array with a look (any source kind) | the cached copies, look included; a re-render of the array draws plain copies | the container as v1.8 wrote it (looked pixels) until v1.7 re-renders the array; the spec loses its look at the first save | an array without a look; its cache shows the look until the array is re-rendered |
| Generated curve | an ordinary path, identical geometry | the generator, while v1.7 never edited the layer; after an edit, the path without its settings | as saved (the reopen check of I16 drops a generator whose geometry changed) |
| Cog without a hole | the committed pixels; a re-render draws a Rectangle with the cog's outline (teeth) as custom points | the cog, while v1.7 never edited the layer; after an edit, a custom-points rectangle | as saved |
| Cog with a hole | the committed pixels, hole included; a re-render draws the same rectangle, without the hole | the same as without a hole: the cog until an edit, then a custom-points rectangle without the hole | as saved |
| A holed cog turned into a path | identical (one plain compound `VPath`) | identical | identical |
| Chromatic aberration and Glitch colors | not stored anywhere | - | - |

Corrections to design §8.1, found at integration: its "What v1.7 shows" column describes v1.7's re-render, not what it shows on open (the saved pixels), and its "What a v1.7 save keeps" column describes a layer v1.7 edited. The array row is the one where an untouched project changes: the spec loses its look at the first v1.7 save while the cache keeps the looked pixels, so back in v1.8 the array's cache and its spec differ (I1) until the array is re-rendered.

## Testing and CI (v1.8)

**The `Timing` category (F0).**
- `testing/Timing.kt` is a JUnit 4 category: an empty interface, `com.brushwork.paint.testing.Timing`. Every test class that times anything carries `@Category(Timing::class)` (`org.junit.experimental.categories.Category`). At integration 55 test classes carry it.
- `testing/TimingTaggedTest` keeps that true by scanning the test sources. A file "times" when its text has one of `CLOCK` (`PerfBudget.`, `System.nanoTime`, `measureNanoTime`, `measureTimeMillis`) or names a helper listed in `TIMING_HELPERS` (`qa16/LiveQa.kt`, `qa16/MasksLiveFlow.kt` and `tools/vector/VectorPerfHarness.kt`, each with the names its users call). Every top-level test class of such a file must be tagged.
- `NOT_TIMING` is its allowlist: about 20 files that read the clock for another reason, each with that reason. Examples are a UI wait's timeout scaled with `PerfBudget.ms`, a clock that names a fresh directory or document, times that are printed or noted but never asserted, and `PerfBudgetTest`'s scripted clock.
- An entry of `TIMING_HELPERS` or `NOT_TIMING` that no longer applies fails the test too, so both lists stay true. The test also pins the category's class name, which Gradle filters on.

**Timing guards (`testing/PerfBudget.kt`, frozen).**
- Budgets are tuned on a desktop. On CI (`CI` set) `PerfBudget.ms(desktopMs)` multiplies them by `CI_FACTOR` = 4. Callers scale with `ms` where they always did; a guard uses the budget it is given.
- `within(budgetMs, tries = TRIES, what) { block }` runs the block once untimed (a warm-up), then up to `TRIES` = 5 timed runs. It stops at the first run within the budget and returns its time. It fails only when every try is over, and its message lists every time and the best one.
- `ratioWithin(limit, tries, a, b, slackMs, what)` warms up both blocks, then times `a` (the thing under test) and right after it `b` (the reference) in each try. A try passes when a ≤ limit · b + slackMs.
- `timedWithin` is `within` without the warm-up, returning the block's result (for a cold first run). `measuredWithin`, `measuredRatioWithin` and `until` take the caller's own measurement: a median, an average, or a step set up again each try.
- A healthy guard pays one run. Retries absorb a noisy machine; a budget is never loosened to make a guard pass.

**Frozen test helpers (`$FrozenTests`).** `.wt/_tools/ownership-check-v18.ps1` fails an area branch that changes any of eight helpers, with "frozen test helper (lead)": `smoke/SmokeUi.kt`, `testing/StripTestKit.kt`, `testing/PerfBudget.kt`, `qa16/QaCurves.kt`, `qa16/Qa16Ui.kt`, `ui/editor/HistoryTapFingers.kt`, `ui/editor/chrome/UniqueLabelsV17.kt` and (from F4) `testing/PillTestKit.kt`. Only the lead changes them.

**The kits for converted strips and the pill.**
- `SmokeUi` (the More-row fallbacks): `find` and `click` look for a label; when it is not on screen and the options strip shows its More-row toggle closed, they open the row (`openMoreRow`, through the tag `V18Tags.STRIP_TOGGLE` on the strip's row 1) and try once more. `has` never opens the row, so a check for absence leaves the row as it was (v1.9, R3: an open row no longer takes a canvas touch, and the tool remembers it). v1.7 tests therefore still find a control an area moved to the More row.
- `testing/StripTestKit` does the same for tests that drive `RobolectricUi` directly: `tagged`, `toggle`, `moreRowShown`, `stripMoreRow`, `openMoreRow`, `closeMoreRow`, `reach { find }` (find, else open the row and find again), `inConvertedStrip(node)` (on row 1 or the More row) and `horizontalScroller(node)` (null on row 1 at 360 dp and wider, where row 1 never scrolls, I18; below 360 dp, row 1's scroller).
- **Two toggles, two More rows (v1.8 integration).** The vector object bar's More toggle and More row reuse `V18Tags.STRIP_TOGGLE` and `V18Tags.STRIP_MORE_ROW`, so no helper takes "the last node tagged ...".
  - `StripTestKit`'s `toggle`, `openMoreRow`, `closeMoreRow`, `moreRowShown`, `stripMoreRow`, `reach` and `inConvertedStrip` reach the options strip only. So do `SmokeUi.openMoreRow` (and with it the More-row fallback of `SmokeUi.find` and `click`) and `UniqueLabelsV17`'s strip walk.
  - The strip's toggle is the `STRIP_TOGGLE` inside `V18Tags.STRIP_ROW1`. The bar's More row is the `STRIP_MORE_ROW` under `ChromeTags.SELECTION_BAR`, or, for a bar composed on its own, the one whose parent holds no `STRIP_ROW1`; the strip's More row is any other. Kits can use the predicates `isStripToggle`, `isObjectToggle`, `isStripMoreRow` and `isObjectMoreRow`.
  - Tests reach the bar's row explicitly with `StripTestKit.openObjectRow` and `closeObjectRow` (also `objectToggle`, `objectMoreRow`, `objectRowShown` and `inObjectRow`), or by tapping `StripLabels18.MORE_OBJECT`.
  - Only one More row shows at a time, so opening either one closes the other, and the strip's closer never closes the bar's row. A test that needs Bring forward, Send backward, Array from objects or Detach mirror copies opens the bar's row first; `SmokeUi.find` and `click` never open it.
  - `StripTestKitObjectRowTest` pins this on Lasso and Object select at 392 dp.
- `testing/PillTestKit` reaches the X / Y pill's "Select several" cell (tag `V18Tags.PILL_SEVERAL`): `severalCell`, `severalState` ("On" / "Off") and `toggleSeveral` (its click action, then a settle).
- UI tests keep the v1.7 rules: one `@Test` per class after `SmokeUi.installTestRecomposer()`, its own `instrumentedPackages` sandbox, and `@Config(qualifiers = "w392dp-h873dp-xxhdpi")` (the phone) or `"w360dp-h640dp-xxhdpi"` (the narrow check).

**Cross-area tests (`qa18/V18CrossTest*`, design §6.2 item 2).** Integration tests across the areas, in `app/src/test/java/com/brushwork/paint/qa18/`.
- `V18CrossTest` (392 dp, the full editor, one `ChromeHarness` section per scenario):
  - a. A Kaleidoscope 6 spiral with an Array look is exported to SVG. The SVG has one path per copy (copies × instances paths), each path has one subpath (one "M"), and the fill colors group by the look's tints.
  - b. A holed cog with Mirror copies: Detach, then "Turn into path", gives one compound plain `VPath` per copy, and its pixels keep the hole.
  - c. A Magnetic lasso around a disc, intersected with Color select "Reds", then the Clone stamp under Rotation 4.
    - The art is a blue disc with red squares inside it and outside it, and a green disc at the canvas centre (`GREEN_R` 34 px).
    - The clone source is set 50 px left of the stroke start (`STROKE_X0 - CLONE_DX`, `CY`), which lands on the green centre. Each rotated copy reads its own rotated source spot (D1), and the green disc is the same under all four.
    - One stroke, 230 to 270 px at `CY`, crosses the right red square and runs onto the unselected blue. Before it, the test checks that the probes `rotated4(STROKE_X1, CY)` are unselected and blue, and that the four rotated source spots are green.
    - After it: the stroke is one undo step; no pixel outside the selection changed; each of the four inner red squares changed at least 40 px and is green at its centre; the four probes are still blue; the outer reds and the disc are unchanged.
    - A clone that ignored the selection would paint the probes green, so the test fails then (checked by mutation).
  - d. Chromatic aberration "Red/Cyan", then "Array from selection" with a "Sunset" look. The filter result is compared exactly, and the tinted copies carry the fringed pixels.
- `V18CrossTestStrips360` and `V18CrossTestStrips392` (scenario e; the shared check is `V18CrossTestStrips`, the kit `V18CrossTestKit`) walk the More row of every tool on the raster and the vector layer, plus the v1.8 states: a generated rose on Curve and Path; the curve tools open with mirror copies; a holed cog; a cog with copies in Points mode; an open text, on the raster and on the vector layer; a selected text frame; a brush part of a mask; the Transform box and Free deform; an Array layer. For each walked More row:
  - at most `StripLayout.MAX_MORE_LINES` lines;
  - nothing clipped or ellipsized, except a `fixed` `StripValueButton`'s value, which is its stateDescription ("The selection strips": a fixed value button ellipsizes its value, as Color select's chip does with "Vivid colors" and "Muted colors"), and a More-row hint ending in "…" (`TextFrameStripTest`);
  - no text inside another where one of the two is a v1.8 text (`LabelsV18Test` rule 4). The exemptions carried over: equal texts count only on two different controls (the same text twice on one control, such as a value button's label and description, or a label repeated beside its control, is one text); a text strictly inside another counts even on one control; `CurvesLabelsTest`'s ignored digits and its ("Curve", CURVES) pair; `ShapesLabelsTest`'s derived prefixes.

  Each class also has a self-check that the machinery catches planted faults, a floor of v1.8 texts that must be seen, and at least 40 More rows walked.
- `V18CrossTestOverlays` (360 dp, scenario f) runs `MoreRowOverlayTest`'s rule D25 on the real strips. Each control of each walked More row is clicked in turn. The state is set up anew for each click, and the document's steps are undone after it. A disabled control keeps its semantic click action, but a tap never reaches it, so it is skipped where it would be clicked. It stays in the row's index count, because enabled-ness can follow a tool setting an earlier click changed. What each click must do:
  - A sheet or dialog: the window still shows after two more settles. It lives in `StripSpec.overlays`, and its item calls `closeMore()` first. v1.9 (R3): `closeMore()` is a no-op in the editor and only hides the row in the rig, so whether the row stays open under the window is not checked; the window must outlive the row hiding under it.
  - An anchored popup: the More row stays open while it shows, and is still open after Back closes it.
  - A click that opens no window must leave the More row open, unless the click changed the tool, the strip has no More-row toggle, or it is one of `CLOSES_WITH_NOTHING_OPEN`: the Clone stamp's hint arming Set source, on raster and vector (the next canvas touch must reach the canvas), and TEXT@vector "Wrap around picture" with no text, which only toasts.

  This catches a sheet or dialog composed inside its own item, which dies with the row it closed and never shows. The self-check (`overlayMachineryCatches`) runs on fake specs: a good sheet, a good dialog and a good dropdown are found as their kinds, a dialog over an open row passes (v1.9, R3), and a popup behind a closed row and a dialog composed inside its item are each caught. A floor of 33 named openers (from each strip's own tests and the close rules in this file) must each be found as its kind. Among them is "TEXT@vector, an open text: Wrap around picture" (sheet). When the test was written, the walk found 126 openers.
- `CogTurnIntoPathStripTest` (360 dp) covers the Shape strip's "Turn into path" for a holed cog ("The Shape strip").

**Request coverage (`audit/RequestCoverageV18*UiTest`).** Each v1.8 request item (1–9) gets one end-to-end check. The checks drive the UI as a finger does: the canvas, the tool menu, the top row, the options strip and its More row, the X / Y pill, dropdowns, sheets, the filter panel and the color picker. They find controls by their `LabelsV18` labels and `V18Tags`, and they follow the area reports' "how to use" lines. Setup that is not part of the item (seed pixels, a vector layer, a test picture) is done in code. The item's own action always goes through the screen.
- One shared driver, `RequestCoverageV18.run(group, widthDp)`, has four groups:
  - **MIRRORS (item 1):** Curve, Polyline, Path and Shape mirror copies, reopened from a copy and then removed; the clone stamp's mirrored source; Select several in the X / Y pill, on a curve and in Free deform.
  - **SELECT (items 3 and 4):**
    - The magnetic lasso, tap to tap: Delete point, the top row's Undo, and a point dragged along the rim then put back by Undo. The disc's area must be within 4 %.
    - Color select: a sampled red, then an orange stripe about 49 ΔE away (computed with `ColorRange.lab`) that only Fuzziness 120 reaches, first in the red preview and then in the selection. Then a second sample from "Add from the color picker", then Color range › Blues.
  - **CHROME (items 8 and 9):**
    - The More menu's five sections.
    - The strip's More row: the line count comes from what each line shows, and no line scrolls sideways; a first one-finger canvas tap only closes it. The clone stamp's More row wraps to 3 lines at 360 dp and 2 at 392 dp.
    - Top-row long presses; the opaque menu card.
  - **LOOKS (items 2, 5, 6 and 7):**
    - An array's look: opacity; size, where half size gives a quarter of the pixels; a tint with Strength.
    - Fringe and static colors, each equal to the filter's own result.
    - A spiral changed in its sheet.
    - A cog placed in one step "Shape" on its own "Cog" layer, with 9 teeth.
- Seven classes run the driver. MIRRORS, SELECT and CHROME run at `w392dp-h873dp-xxhdpi` and at `w360dp-h760dp-xxhdpi`, as V17 does; LOOKS runs at 392 dp only. Each class has one @Test after `installTestRecomposer` and its own `instrumentedPackages` sandbox (`requestv18<group>sandbox`, and `requestv18<group>360sandbox` at 360 dp).
- Each item runs in a `ChromeHarness.section`, so a failing item does not hide the next one.
- Waits for renders and computed results use `PerfBudget.ms(10 s)`.
- Cost: the seven classes take about 83 s of JUnit time.

**The CI decision (CI2, design §8.5).** v1.7.0 ran two test JVMs on CI. After it, CI went back to one JVM (`885793b4`), because the timing-ratio QA tests (`VectorBudgetQaTest`'s local erase against a full render) spiked past their budgets while two JVMs shared the cores; the suite then took about 41 minutes. CI2 runs two JVMs again, but only for the tests that time nothing. `app/build.gradle.kts` sets the test JVMs:
- `forkEvery = 40` and a 1536 MB heap, as in v1.7.
- On CI (`CI` set) without `-PtimingOnly`: `maxParallelForks = 2`, and `excludeCategories` drops the `Timing` classes.
- With `-PtimingOnly`: one JVM, and `includeCategories` runs only the `Timing` classes.
- Locally (no `CI`): one JVM runs every test, timing tests included, as before v1.8.
- `bw.testForks` and `bw.testSet` (`timing`, `main` or `all`) are passed as system properties, so they are task inputs and the build cache never hands back a run made with other settings.

`.github/workflows/android.yml` runs "Unit tests" (`./gradlew --no-daemon testDebugUnitTest`: two JVMs, no timing tests), then "Timing tests" (`... testDebugUnitTest -PtimingOnly`: serial, so no second JVM competes with a budget for the cores), then the release APK. The job's timeout is 75 minutes. The merged suite has 4205 tests: 3883 in the first step and 322 in the second.

Measured on GitHub Actions (pushes to `main`, all green):

| Run | Commit | Unit tests | Timing tests | Whole job |
|---|---|---|---|---|
| 38049787695 | `33e1184f` (before CI2) | 41.8 min (one JVM, all tests) | - | about 49 min |
| 38111944913 | `4f923db2` (before CI2) | 40.2 min (one JVM, all tests) | - | about 46.6 min |
| 38114453644 | `089b946e` (CI2) | 22.0 min | 4.7 min | about 31 min |
| 38126171015 | `762b3cb7` | 25.7 min | 4.7 min | about 37 min |
| 38133168556 | `f4478bc8` | 18.2 min | 3.0 min | about 25.5 min |

The split cut the test time from about 41 minutes to 21 to 30 minutes, and the job from about 47 minutes to 25 to 37, well inside the 75-minute timeout.

## Testing and CI (v1.9)

Written by the lead at Gate 2 (design 5.5). The v1.8 rules above still hold (the `Timing`
category and `TimingTaggedTest`, `PerfBudget`'s guards, the frozen kits, one UI `@Test` per
Robolectric UI class); "CI shards and the test-count floor (F0)" in "v1.9 foundation" has the
flags in detail.

**CI** (`.github/workflows/android.yml`, "Build APK", on every push to `main` and on a `v*` tag):
- `unit`: a matrix of three shards, `testDebugUnitTest -PtestShard=<i> -PtestShards=3` (45 min
  each). A class runs in shard `ciShardOf(className, n)`, the hash of its top-level class name
  modulo n, so nested classes stay with their outer class and a new class lands in a shard
  without a list to keep. With `CI` set each shard uses two JVMs and leaves out the `Timing`
  category. CI ran two shards from F0 until Gate 2: after A7b's merge a two-shard test step took
  27.5 min and passed the 26-minute fallback of design 5.5 (item 6), so the lead set three
  (`9a213149`). The first three-shard run (39169533493) counted 1426, 1745 and 1593 tests (4764)
  and took 22 min 40 s for the whole workflow. `CiShardFilterTest` checks that every class falls
  in exactly one shard and the balance for two and three shards.
- `timing`: `testDebugUnitTest -PtimingOnly` (30 min): the `Timing` classes alone, serially in
  one JVM, so no second JVM competes with a budget for the cores.
- `test-count` (after `unit`, 10 min): sums the `tests` attributes of every shard's JUnit XML
  (artifacts `test-results-0`, `-1`, `-2`) and fails below the floor in
  `app/src/test/test-count.txt`, when a shard uploaded no XML or log, or when a log shows
  `testDebugUnitTest NO-SOURCE`. The XML counts whether the task ran or came from the build
  cache, so the tag build of a tested commit passes.
- The release APK (30 min) needs `unit`, `timing` and `test-count`, with A8's guards (the
  keystore, a `versionCode` above the last tag's) and `mapping.txt` uploaded; a `v*` tag
  publishes it.
- `.github/workflows/soak.yml` ("Soak tests", nightly at 02:30 UTC and on `workflow_dispatch`,
  60 min): `testDebugUnitTest -PsoakOnly` runs `SoakHarnessTest` alone in one JVM
  (`bw.testSet=soak`; it skips itself in every other run). A workflow of its own, so its schedule
  never cancels a push build of `main`.

**The test-count floor.** The non-Timing count of all shards: 3970 at v1.8, 4177 after F3, 4764
after the ten areas, 4787 after Gate 2. Only the lead changes the file (rule O): it raises the
floor at each merge into `main` to the merged branch's count, in a commit of its own
(`v19-lead: test-count <n> after <x>`), and lowers it only in the commit that deletes tests on
purpose. The file has no trailing newline.

**Local runs** (`.wt/_tools/gbuild.ps1 <tasks>`: JDK 17 and at most four Gradle builds at once
across the worktrees):
- `testDebugUnitTest` without `CI`: one JVM runs every test, the `Timing` ones included.
- With `CI=true`: CI's mode (two JVMs, no `Timing`); add `-PtestShard=<i> -PtestShards=3` for one
  shard. Then `testDebugUnitTest -PtimingOnly` without `CI` for the `Timing` classes.
- `-PsoakOnly`: the soak harness alone. `-Pbench`: the P0 proxy bench `qa19/P0ProxyBench` alone
  ("P0 proxy baselines" above); with `CI` set that class is excluded from every run.
- `.wt/_tools/test-times-v19.ps1 -Folder <JUnit XML folders>` reads one or several runs (the
  local results folder, or CI's `test-results-<i>` and `test-results-timing` artifacts) and
  prints the slowest classes and the seconds per area (a `v19/<x>/` package is area x, a
  `qa19` class goes by its prefix, the lead's classes are "lead", the rest "v1.8 and older"). An
  area's added seconds stay within 1.5 x its share of design 5.5's budget (item 7).

**UI classes (design 5.5 caps).** One `@Test` per Robolectric UI class (a second one gets no
recomposition); more scenarios become sections. New UI classes in v1.9 (test classes added since
`600582d9` that compose through `installTestRecomposer`, `RobolectricUi` or `Qa19Ui.uiTest`, the
kits left out), against each part's cap:
the lead's F0 to F3 6 of 8 (`Qa19StickyRowsUiTest`, `Qa19StickyRowsNarrowUiTest`,
`Qa19FrameTimesUiTest`, `Qa19UiSelfTest`, `BevelPopupRobolectricTest`, `StripKeysV19Test`), A7a 1
of 1, A8 5 of 5, A5 3 of 3, A1 4 of 4, A9 3 of 3, A2 2 of 3, A4 3 of 3, A3 6 of 7, A6 2 of 2,
A7b 0 of 1, Gate 2 2 of 3 (`CrossObjectsBarsUiTest`, `StripFontScaleTest`): 37 of 43.

**Known CI flakes.**
- `filters/art/FringePerfTest` "Glitch, custom" (and "Glitch, no middle color") on a slow runner:
  the Glitch rows assert `GLITCH_CI_LIMIT` 1.6x on CI, and an EPYC 9V74 runner measured 1.95x
  (the class comment's runner table; EPYC 7763 and Xeon 8370C stay under). A failure is rerun;
  a second failure is recorded with the runner's CPU line, and the budget is never loosened.
- Under a loaded machine (several builds at once) a few v1.8 UI classes can time out locally:
  `qa18/Qa18PolishChromeSelectionBarTest`, `qa17/Qa17CompatPendingWorkOnStopUiTest`,
  `qa18/Qa18PolishChromeVerifyTest` and `qa18/Qa18PolishChromeLongPressTest`; each passes run
  alone. A failure elsewhere is a real one.

## UI (`ui/`)
`MainActivity` → gallery or editor. `ui/common/Components.kt` holds shared controls (sheets,
nudge pad, chips, swatches) and `ui/common/NumberControls.kt` (v1.6) the number controls (typeable
sliders, numeric/length fields with units + automatic sliders / drag-to-scrub, with increment
parameters) — use them everywhere. For a screen reader (v1.8 polish), a `LabeledSlider`'s slider
is named "<label> slider" and says its value text as its state (its progress range stays); the
label's text keeps the only exact match. `GradientEditor` (ui/filters) puts a semantics-only node
over each stop's marker: named by its `stopDescription` (number from the left, count, hex),
Selected for the selected stop, and its click selects the stop and opens its picker as a tap
does; finger touches still go to the bar's own gesture.

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

Frame times (v1.9, design 2.6 step 8; F3). Every `CanvasView.onDraw` is timed as
`PerfTrace.FRAME` (one volatile read while recording is off). While `AppSettings.showFrameTimes`
is on (cached in the view and refreshed by its preferences listener), the view records
(`PerfTrace.enabled`) and draws the frame-times overlay last, at the free area's top left: the
last `PerfTrace.SAMPLES` (120) frames' p50, p90 and longest, and the last brush lift
(`PerfTrace.lastMs(PerfTrace.LIFT)`), white on a dark box. It reports the text to
`onFrameTimes` at most every 500 ms when it changed (the first frame after the switch at once),
and null when switched off; `EditorScreen` puts a semantics-only node (tag `V19Tags.FRAME_TIMES`,
the two lines as its text) over it, 8 dp into the fit area as the overlay is drawn.
`DiagnosticsSection` (`ui/diagnostics/`) gets its first content: its header and the
`StabilityLabels19.SHOW_FRAME_TIMES` switch, which clears and enables `PerfTrace` when turned
on. `BrushworkApp` enables `PerfTrace` at start while the switch is on. `Qa19FrameTimesUiTest`
checks the section at font scales 1, 1.3 and 2, and in the editor the overlay (drawn into a
bitmap), its node and its last-lift line, switched on and off through Settings.

## History taps over the UI (v1.7, item 10; `ui/editor/HistoryTapHub`, `ui/editor/EditorHistoryTaps`, `ui/common/HistoryTaps`)

The canvas still recognizes its own two- and three-finger taps (`TouchGestureClassifier`). `HistoryTapHub` recognizes the ones that touch the UI too. It is pure and allocation-free: fixed arrays, one call per pointer change, pointers told apart by window slot and id.

Each window feeds it exactly once:
- the editor root, through `Modifier.historyTaps(sink, HistoryTapSlots.EDITOR)` at the Initial and Final passes. Hosted sheets (`SheetHost`, inside the root `BoxWithConstraints`) are in this window, so the root feed covers them;
- a `ModalBwSheet` composed in a window of its own, through its column and `LocalHistoryTaps`.

`CanvasView` forwards no `MotionEvent`s. Instead, the canvas `AndroidView` carries `Modifier.historyTapCanvas`, whose Initial pass marks its pointers as canvas pointers. `BwDialog` / `ValueInputDialog`, `DropdownMenu` and `Popup` content have no feed, so a tap there belongs to that window.

**Open dropdowns (`Modifier.closeOnSecondFinger` on every `DropdownMenu`'s content, `ui/common/HistoryTaps.kt`).** A history tap over an open menu only closes it: no item fires, and no undo or redo runs, because the menu window has no feed.
- **Both fingers on the menu, or the first on an item and the second beside it:** the Initial-pass node sees the second press. A finger beside the menu hits no node; its `ACTION_POINTER_DOWN` is carried by the item's finger, so the node also closes on a Press in which none of its own pointers went down. From then on it consumes every change until all fingers lift and dismisses once.
- **The first finger beside the menu:** the touch-modal popup window gets it outside its content. No child takes it, so `PopupLayout.onTouchEvent` dismisses on that DOWN and the popup root handles the gesture itself. Because no touch target was added, `ViewGroup` intercepts every later event of the gesture, and a second finger on an item never reaches it, even while the menu fades out. No code is needed for this case.
- Guard: `Qa17ChromeHistoryTapsUiTest` (More options and the "Stroke kind" strip dropdown, at a usual and a quick pace).
- Two fingers on a dialog's button act as a plain tap on that button: dialogs are outside history taps by design (§7).

A gesture counts as a history tap under the canvas' rules: every finger goes down and up within 300 ms of the first down, none moves the 12 dp tap slop, there are at most 3 fingers, and all are fingers (a stylus or mouse ends it).
- **Canvas alone:** the gesture stays the canvas': its two- and three-finger taps undo or redo there, also through an open More row. v1.9 (R3): a finger that joins a canvas gesture on a More-row control counts as a canvas finger (`passCanvasGesture` calls `onCanvasDown`), so the gesture stays the canvas' ("State, memory and canvas touches").
- **A finger on the UI:** the hub CLAIMS the gesture in the Initial pass, before the new finger reaches anything, and only when the tap with that many fingers is on: at the second finger when two-finger undo is on; at the third finger when only three-finger redo is on (two UI fingers then stay the controls'). With both settings off, or while the editor is busy (`busyMessage`; the busy scrim takes the touches), nothing is claimed. From the claim every event is consumed: the control under the first finger cancels instead of firing (Compose's clickable / drag detectors, and the pill cells, which check `isConsumed`), and the canvas drops what its fingers started (`CanvasView.yieldToHistoryTap`).
- **After the last up**, in the Final pass so a control's own up has run: the hub puts back what the fingers did and runs ONE `HistoryLabels.performUndo` / `performRedo` with the hotbar's feedback. With a filter open, undo cancels it and redo says "Finish the filter first".

**The mark has two parts.** This differs from design §3.10, which takes `uiMark()` at the first down. `uiMark()` → `undoMarker()` records a pending live edit first. Taken at every first down, it split the Adjust sheet's live edit into one step per tap, and it `runBlocking`-waited for a running vector render on every touch, the canvas' included.
- **At the first down,** `EditorHistoryTaps.openMark` takes a light mark that records no step and waits for nothing: the active tool's `Tool.historyMark()`, the `AppSettings` journal (opened), the live brush presets, the colour, and an open filter's `FilterSession.values` (a filter panel's − / +, curve point or gradient stop changes them on touch-down). The light mark stays open while a tap with the current finger count is still allowed.
- **At the claim,** `claimMark` takes the full `uiMark()`. Recording the pending live edit there is right: it belongs before the mark, and the one undo takes it back.
- **The tap** calls `restoreUiMark(full)`, then puts back the light mark: `rollbackHistory`, `rollbackJournal`, the presets (persisted), the colour and the filter values.
- **Every gesture end and cancel** releases both (`releaseUiMark`, journal closed).
- **Canvas finger first:** its light mark is released when that finger's down is settled as the canvas' own, and opened again at the UI finger's down. What the canvas finger started is dropped by `yieldToHistoryTap`, not by the mark.

A document step that a control pushes on a finger's DOWN, before the claim, is not put back. The touch-down audit found none in the chrome.

## The coordinate pill (v1.7, items 9 and 13; `ui/tools/CoordinatePill`)

- **Row 1** is `[✥][X][Y][# 10][🗑]`. The "#" cell shows the Length step in 11 sp, with no unit. It is still "Increments", and a long-press opens the Length Step popup.
- **The trash cell** deletes what the tool's `DeletingTool.objectDeletion` names (`deleteLabel`: "Delete selected points", "Delete curve"…). One tap, no confirmation, and undo restores it. The folded pill is `[✥][🗑]`.
- **Row 2** sits 4 dp under row 1 (`IbisDims.PillRowGap`) while `ScaledTool.objectScale` has a scale: `[⛓][Scale X][Scale Y][# step]`.
  - "Keep scale proportions" is remembered as `pill.keepProportions`. For a uniform-only object (`ObjectScale.uniformOnly`) it is shown on and disabled and Scale Y is hidden; the remembered setting is left alone.
  - The scale is in % of the box the selection was taken with. It is typed (expressions too) or dragged at 1 % per dp. One typed value or one drag is one `beginScaleEdit` … `endScaleEdit`.
  - "Scale increments" shows the Scale step.
  - A Scale cell is read as "Scale X" / "Scale Y" with its value as state; the drawn letter and value are cleared from the semantics, so row 2 never repeats row 1's "X" / "Y". The `Clickables` audit skips what a `clearAndSetSemantics` node hides, as a screen reader does.
- **Cells and history taps:** a cell's tap, long-press and drag give way when a history tap claims the touch (the change is consumed); a running drag ends there.
- **Routing:** the pill reads the interfaces through `coordinateSourceOf` and never names a concrete tool, so any tool that implements `ObjectScale` / `ObjectDeletion` gets the row and the cell.
- **Layout:** the pill's width comes from its cells; at 360 dp the X / Y cells give way first (`weight(1f, fill = false)`). `ChromeLayout.selectionBarTop(statusDp, pillHeightDp)` follows the measured pill height: the selection bar is at 173 dp with row 1 alone and at 209 dp with row 2. `IbisDims.PillWidth` (280), `PillMaxWidth` (372), `PillScaleRowTop` (171) and `SelectionBarTopBelowScaleRow` (209) are reference values; no code reads them.

## The options strip (v1.6 §3.7.2; `ui/editor/chrome/OptionsStrip`)

The panel lays out 0 × 0 and does not place the bar for a tool that shows no options. The bar reports its content width while it is measured, and the "empty" flag is remembered per active tool and vector mode: Compose does not measure an unplaced node again on its own, so a flag kept across tools left the strip hidden for every later tool. On a tool or vector-mode change the flag starts as "not empty", the panel measures the new bar in the same pass, and the bar's report flips it before the panel lays out, so a tool without options never shows an empty panel.

## Converted strips and top menus (v1.8 items 8 and 9, areas G and H)

### The converted strips of area G (v1.8 item 8, §3.9; `ui/placement/`, `ui/textframes/`, `ui/mask/`, `ui/remove/`, `ui/pathfinder/`)

Seven builders return real `StripSpec`s: `transformStrip`, `textStrip` and `frameDividerStrip` (`ui/placement/PlacementStrips.kt`), `textFrameStrip`, `maskStrip`, `removeStrip` and `pathfinderStrip`.
- Each strip's `…StripItems` object holds its keys, its declared widths (measured, rounded up to 4 dp) and its handles (the v1.7 labels).
- Row 1 / More row placement is the host's `StripLayout.split` (I18).

- **Shared parts (`ui/placement/StripParts.kt`).**
  - `StripMenu`: an anchored dropdown, opaque `IbisColors.Menu`.
  - `StripMenuButton`: a label and ▾. It leaves the semantics while its menu is open, so the menu entry is the one control with its label.
  - `StripTextButton`: icon and text at 10 to 11 sp, `stacked` for icon over text, with `checked`/`on`/`dimmed` states.
  - `StripStatusText`: 11 sp, 2 lines.
  - `snapItem`: "Snap to objects", 96 dp.
  - `ClosesMoreRow()`: used in toggle-less states. A no-op since v1.9 (R3): the host hides a toggle-less spec's row itself, remembered (`StripState.drop`, `undrop`), and the call sites stay.
- **Close rules (D25; v1.9, R3: a row closes only by its toggle).**
  - Anchored dropdowns and popups keep the More row open: the Transform mode, Mesh size, interpolation, the Text frames wrap, the Masks target, the Remove sample and size, the Pathfinder ops.
  - An item that opens a sheet or dialog, or that waits for the next canvas touch, calls `LocalStripContext.current.closeMore()` first (a no-op since v1.9: a sheet covers the row, and a canvas touch reaches the canvas through it). Examples: "Edit text", "Letters", "Wrap", "Numbers", "Edit story", "Link…", the Masks add buttons, the Frame divider sheets.
  - Every sheet and dialog is composed from `StripSpec.overlays`.
- **Transform (`TransformStripItems.kt`).**
  - Idle: v1.7's status text alone (284 dp, no toggle).
  - Box modes: row 1 has the mode dropdown (56 dp, the current mode's label and icon), "Keep aspect ratio", "From center" and the flips. The flips keep their width while a text can't be mirrored. The More row has "Delete" (104 dp), the quarter turns, "Fit to canvas", "Reset transform", "Snap to objects", "Choose interpolation" (80 dp: the caption `TransformLabels18.INTERPOLATION_CAPTION` at 10 sp over the current interpolation and its ▾, v1.8 polish) and "Numbers" (64 dp, the Numbers sheet is an overlay), under the hint `TransformLabels18.BOX_HINT`. It tells how the box works while a Free or Distort transform is active, and replaces the idle status text there (v1.8 integration; `PlacementStripTest`).
  - The dropdown lists the three modes. A mode the lifted content refuses is dimmed, with its caption as stateDescription; a tap shows the caption, plus "Rasterize and deform" when available.
  - `movedInto` maps the other two modes to the mode item (`modesInside`).
  - Free deform: row 1 has the mode, `TransformLabels18.MESH_SIZE` ("3 × 3", 72 dp; its popup holds v1.7's "Mesh columns" / "Mesh rows" steppers, `movedInto`) and "Reset mesh". The More row has "Smooth mesh", "Select all points" / "Deselect all points", "Delete" and "Choose interpolation". The hint is `PointLabels.SEVERAL_HINT` while the pill's "Select several" is on (`selectSeveralAvailable = isMeshShown`).
- **Text (`TextStripItems.kt`).**
  - Row 1: "Edit text" (92 dp, dimmed with nothing to edit), the direction, "Letters" and "Wrap" (AUTO 1).
  - More row: "Snap to objects", then "Numbers" (80 dp, its word on one line) while a text is open. Without an open text the item is left out, not kept as an empty slot; Snap stays first in both states (v1.8 polish; `Qa18PolishChromeTextTest`).
  - The editor dialog and the Numbers, Wrap and Letter scaling sheets are overlays.
- **Frame divider.**
  - Without a frame layer: "New frame layer" and the status (no toggle). The status is v1.7's text (never reworded); `StripStatusText(balanced = true)` breaks it into two even lines, not a full line and a lone "it" (v1.8 polish).
  - Ready: "Rows × columns", "Remove panels mode" and "Frame settings / new frame layer" on row 1; "Snap to objects" on the More row under the divide or remove hint.
  - Edited by hand: "Redraw", "New frame" and the status (184 dp).
  - The settings sheet and the grid dialog are overlays.
- **Text frames (`ui/textframes/TextFrameStrip.kt`).**
  - Nothing selected: the draw hint on row 1.
  - A frame selected: "Edit story" (64), "Link…" (60), "Unlink here" (AUTO 1), "Delete frame" and the red "+ N characters" (64 dp, its width reserved while the story fits).
  - Linking: "Cancel link" and the link hint.
  - The More row always has "Threads" and "Snap to objects", plus "Wrap around picture" (a dropdown) with a frame selected. The story editor is the overlay.
- **Masks (`ui/mask/MaskStrip.kt`).**
  - Row 1: "+ Linear", "+ Radial", "+ Brush" (40 dp each), "Invert", "Adjust…" (72 dp, AUTO 1) and "Done" while a brush part is selected.
  - More row: the target dropdown (152 dp), Brush / Erase, Add / Subtract / Intersect, the overlay toggle and "Components (N)" (100 dp).
  - The Adjust and components sheets, the filter browser and the replace question are overlays (`MaskSheets`).
- **Remove (`ui/remove/RemoveStrip.kt`).**
  - Row 1 only, no toggle: "Remove brush size" (64 dp readout; its popup holds v1.7's slider, with a spinner while removing), the "Sample" dropdown (96 dp) and "Color adaptation" (104 dp).
- **Pathfinder (`ui/pathfinder/PathfinderStrip.kt`, `PathfinderOptions.kt`).**
  - Row 1 only, no toggle: the ops dropdown (92 dp, tag `v18g.pathfinder.ops`; the button shows the last op used, kept with `rememberSaveable`; the ten ops keep their descriptions; enabled with at least 2 picks while idle), "Select all objects" (40 dp) and the status (140 dp, AUTO 1: it moves only below 360 dp).
  - `movedInto` maps every op description to the dropdown.
- **Tests.**
  - UI (one @Test per class, 360 and 392 dp × VECTOR cell, inventory rules 1 to 5, splits, clipped texts): `PlacementStripTest`, `TextFrameStripTest`, `MaskStripTest`, `RemoveStripTest`, `PathfinderStripTest`, `FreeDeformPillTest`.
  - JVM: `StripsLabelsTest` (labels are LabelsV18 or v1.7 constants, rule L).
  - Shared kit: `PlacementStripKit`.

### Top menus (v1.8 item 9; area H: `ui/editor/chrome/TopRow.kt`, `ui/editor/EditorScreen.kt`, `ui/editor/chrome/ChromeGlyphs.kt`, `ui/theme/`)

- **The More circle.** The top row's last circle draws `ChromeGlyphs.MoreDots`. These are three solid dots of radius 2.1 at y 12 on the 24-unit grid, centred at x 5.5, 12 and 18.5. Its label stays "More options".
- **Sections (D28).** `MenuEntry.section` (a `MenuLabels18` name) replaces v1.7's `dividerBefore`. `MoreMenu` heads each run of entries of one section with a `SectionHeader`:
  - 24 dp tall (`IbisDims.MoreMenuSection`), with the name at 12 sp (`IbisDims.MoreMenuSectionText`) in `OnChromeDim`;
  - a heading for screen readers, not clickable, tagged `V18Tags.MENU_SECTION_PREFIX + name`.

  The five sections (`moreMenuEntries`):

  | Section | Entries |
  |---|---|
  | `CLIPBOARD` | Copy, Paste |
  | `BRING_IN` | Import picture, Import SVG or PDF… |
  | `SEND_OUT` | the four exports, Share |
  | `VIEW` | Flip view, Fit to screen, 100% (actual pixels), Reset rotation |
  | `THIS_ARTWORK` | Canvas…, Increments…, Save now, Settings |

  "Canvas…" moved from before "Flip view" to the head of the last section. Every other entry keeps its label and relative order. The top-row circles folded into the menu on a narrow screen have no section. They come first, before the first header.

  The headers make the menu about 116 dp longer than v1.7's: five 24 dp headers replace four 1 dp dividers. On the 392 × 873 dp phone the entries from "Canvas…" on sit below the fold, one short scroll away. v1.7's menu already scrolled there. A test that reads those entries' bounds must scroll the menu first; `SmokeUi.click` does not need to.
- **Opacity (D27).**
  - Menus are opaque `IbisColors.Menu` (#1E1E1E). The More menu passes it as `containerColor`.
  - `BrushworkTheme` sets `surfaceContainer = IbisColors.Menu`, so Material dropdowns that keep the default container color are opaque too. A dropdown that sets its own color keeps it.
  - At integration (`v18I`) the five dropdowns that still set `BrushworkColors.ChromeHigh` (#2A2C30) moved to `IbisColors.Menu`: the brush readouts' editor (`ui/brush/BrushPanel.kt`), the clone stamp's Sample menu (`ui/clone/CloneToolOptions.kt`), the layer window's special-layer, ⋮ and blend menus (`ui/layers/LayerControls.kt`) and a saved selection's menu (`ui/layers/SavedSelectionRows.kt`). `ChromeHigh` stays on what is not a menu: dialogs, the filter chip and the snackbar.
  - The options strip's More row and the object bar's More row are `IbisColors.Menu` with a 1 dp `BrushworkColors.ChromeBorder` edge.
  - Sheets, the selection bar and the object bar's own row stay `SheetBackground` (translucent).
- **Long-press labels.** `Modifier.longPressLabel(label)` shows the label in a bubble and never acts. It sits on:
  - every top-row circle (`TopCircle`);
  - every selection-bar button (`BarItem`, its click label);
  - every object-bar button (`ObjectBarItem`) except Recolor, whose own long press recolors the lines only;
  - both More toggles, and the strip's VECTOR cell (F3);
  - the pill's trash, Keep proportions and fold cells (`CoordinatePill`). The several-points cell already had one, and the step cell keeps its own long press;
  - every icon-only control of a tool's strip, row 1 and the More row (v1.8 polish): `ToolIconButton`, the selection strips' `IconDropdown`, `CountedCloseButton` and `StripIconAction`, Transform's mode button (`StripMenuButton(longPress = true)`), the vector eraser's modes, Symmetry's type and Color select's current color. The shape and curve cells already had theirs.
    - They use `Modifier.iconLongPressLabel` (`ui/common/IconLongPress.kt`). It acts only inside an `IconLongPressScope`, which each `StripHost` cell provides, and only in that scope's own window. A popup or a dialog opened from a cell (Free deform's mesh size, the dropdowns) shows no bubble. A sheet raised from a cell shows none either: `SheetHost` resets the scope.
    - A number readout (an icon and its value, `StripReadout`) is not icon-only: it keeps v1.7's long press, which opens its editor like a tap.
- **Selection bar scroll cue (v1.8 polish).** The selection bar's ten items are wider than a phone, so the bar scrolls sideways. At each edge with items past it, a 12 dp cue shows a 24 dp chevron over a fade to `IbisColors.Menu` (`ScrollCue` in `ui/editor/SelectionBar.kt`, tags `ChromeTags.SELECTION_BAR_CUE_START` / `_END`).
  - The cue lies in the bar's edge padding and an item's side margin. It may cover an item cut by the edge, never a whole item's label. It takes no touches, so a finger on it reaches the item under it.
  - It is an overlay, not a slot: the eight v1.6 items still fit 392 dp. The paste bar (Paste, More, Hide) fits and shows none.
  - Only the selection bar uses it. The object bar's own row does not scroll. The strip's row 1 below 360 dp and a More row's too-wide last line scroll in `StripHost`, another component, and have no cue.
- **Tests.**
  - In `qa18`:
    - `MoreMenuSectionsTest`, `TopRowLongPressTest`, `MoreRowOffsetsTest`;
    - `MoreRowCloseTest` (the D25 rules, plus the object bar's row beside the strip's; v1.9, R3: only the toggle closes a row, and a canvas touch through it acts and leaves it open);
    - `ObjectBarMoreTest`;
    - `OpaqueMenusTest` (Skia pixels of the menu card and both More rows);
    - `ChromeLabelsTest`;
    - `ChromeV18NarrowUiTest` (360 dp: every strip, the row-1 budgets, the object bar, the top row);
    - from the v1.8 polish, `Qa18PolishChromeLongPressTest` (392 dp: a long press on every icon-only control of every tool's strip and of the bars, raster and vector, and in the states that show more icons, shows its name and changes nothing; taps still act; no bubble in the mesh size popup) and `Qa18PolishChromeLongPressShotsTest` (the strips QA named, at 392 and 360 dp, rendered with the bubble); `Qa18PolishChromeSelectionBarTest` (392 and 360 dp, dark and white pictures: the cue at the edge with items past it before and after a slide, clear of whole items' labels, legible, a tap through it); `Qa18PolishChromeFrameHintTest` (the Frame divider's status on two even lines, raster and vector, 392 and 360 dp) and `Qa18PolishChromeInterpolationTest` (the caption on "Choose interpolation", box modes and Free deform, 392 and 360 dp); `Qa18PolishChromeVerifyTest` (the verifier's flows: the cue slid part way, Deselect while slid, Undo and Redo by the top row, a long press through each cue; every tool's bubbles at 360 dp, the Magnetic lasso with points and Color select with a sample; the Text More row through apply, Undo and Redo);
    - from the integration, `MoreRowHistoryTapsTest` (history taps through an open More row) and `StripRow1NarrowFingerTest` (320 dp, real fingers on row 1), both under "The options strip and its More row".
  - Helpers:
    - `Fingers18`: real touch, with taps (`tap`, `twoFingerTap`, `threeFingerTap`), `quickTap` (no settle, for touches inside a fade), strokes, pinches, pans, `longPress` / `longPressAt` and the canvas state;
    - `FakeStrip18`: `FakeStrips.spec(more, moreWidth, closer, row1Width)`; `FakeStrips.items(row1, more)`, which takes row-1 and More items of any width and counts their taps; and `FakeStripEditor` through `LocalStripOverride`.
  - `ui/vector/ObjectBarLayoutTest` covers the pure object-bar split.

## Typed expressions: grammar and parse sites (v1.7, item 15; `core/Expressions.kt`)

- **Grammar** (a trailing unit word, `°` or `%` is stripped first; spaces between tokens are allowed):
  ```
  input   := [relop] expr
  relop   := * / × ÷ x X        (only a LEADING one is relative: the value applies to the current one)
  expr    := term {(+ | - | −) term}
  term    := factor {(* | / | × | ÷ | x | X) factor}
  factor  := [+ | - | −] primary   (ONE sign: "--5" is an error)
  primary := number | '(' expr ')'
  number  := (digits [(. | ,) [digits]] | (. | ,) digits) [(e | E) [+ | -] digits]
  ```
- A leading `+` or `-` is a sign, never relative. NaN or infinite results, a division by 0 ("Can't divide by 0") and text longer than `MAX_LENGTH` (64) are errors.
- **Fast path:** `isPlainNumber` text (a sign, digits with '.' or ',', an exponent, a unit) takes each parse site's v1.6 code unchanged.
- **The three parse sites** (`Units.parse`, `NumberSliderMath.parseTyped`, `SliderMath.parseValue`) otherwise `evaluate` the text; when that is an error they run their v1.6 code after all (the lenient fallback: "1 000" reads 1000), so no text that gave a number in v1.6 stops giving one (I13).
- **Relative text:** fields resolve it against their current value before `parse` (`resolveRelative`: "/2" at 120 becomes "120/2"; "*2+1" becomes "120*(2+1)").
## Typed expressions: keys and readout (v1.7, item 15's UI; `ui/common/OperatorKeys`)

- **The keys:** six operator keys (+ − × ÷ ( )) sit under the field in `ValueInputDialog`, the `LabeledSlider` value editor (`NumberControls`) and the Step popup (`IncrementControls`). Keys are 52 dp wide, shrink to 40 dp in a narrow place, then the row scrolls. They never take focus, so the keyboard stays up. `OperatorText.insert` appends to a fully selected value: with "120" selected, "+" gives "120+".
- **The readout:** `ExpressionReadout.of` gives the live readout.
  - Empty text and a plain number show nothing, as in v1.6 (a plain out-of-range number is refused only at OK, with "Type a number (…)").
  - Otherwise it shows "= 150 px", the value the field's own `parse` reads.
  - Relative text is checked with `Expressions.evaluate` first, so "/0" is an error ("Can't divide by 0"), not 0.
  - Text that is not a valid expression shows "Check the expression".
  - A valid expression whose value `parse` refuses (out of range, e.g. "0.05*1" under the 0.1 % scale minimum) shows "Type a number (range)" in `ValueInputDialog`.
- **Applying:** OK (`BwDialog.confirmEnabled`) and Done do nothing while the readout shows an error. Garbage text therefore reads "Check the expression" where v1.6 said "Type a number (…)". Empty text still says "Type a number (…)".
- **Closing the keys (final QA):**
  - A `NumberField` / `LengthField` shows its keys while it has the focus. When Done commits, it also clears the focus (`LocalFocusManager.clearFocus()` after `commit()`), so the row closes at once, as the `LabeledSlider` value editor closes on Done. Before, Done only hid the keyboard and the row stayed open until a scrub's touch-down took the focus; that collapsed the row and moved the handle of a field below 42 dp under the finger.
  - A refused Done (the readout is an error) keeps the field focused and its keys open, as the Step popup stays open on a refused Done; the keyboard is hidden.
  - The focus-loss commit that follows Done sends nothing again: `commit()` returns at once when the text is the one already committed (set by a commit, or by `finish()` after a button, slider or scrub change; cleared by any typed or set change). Without this, the Path point's "Weight" field (`CurveToolOptions`) began a numeric edit that nothing ended, and `CurveTool.pushHistory` merged later same-key edits into one undo step whatever the pause between them.
  - Typing in one field and then scrubbing ANOTHER field below it without Done still closes the row at the scrub's touch-down (the touch-down must take the focus so the typed value commits first).
  - Guards: `Qa17KeysRowDoneUiTest` (the Array sheet's "Constant X", then a scrub of "Constant Y" whose handle must not move; a slider's value editor) and `NumberFieldDoneRobolectricTest` (ONE edit begun and finished after Done, nothing sent when refocused and left).

## v1.7 integration: audits and cross-area flows

**I10 on the v1.7 screens (`ui/editor/chrome/UniqueLabelsV17`).** UniqueLabelsTest (392 dp) and UniqueLabelsNarrowTest (360 dp) also run the v1.7 sections. The screens are:
- Path with two points picked through "Select several";
- a shape in Points mode with one point picked, then two;
- the Array sheet in each mode;
- the Symmetry tool with each ruler, and each ruler's value chip open;
- the Ruler panel over the Symmetry tool, and over the Brush;
- Pathfinder with its operands;
- Transform in Free deform;
- the text editor with the Kerning row;
- the layer window with an open folder and two saved selections, plus their menus.

Each screen is reached as a finger reaches it, by labels and `V17Tags`, never by coordinates.
- The options strip and a sheet are checked at every scroll position and again as a whole. A vertical walk also walks each row inside the container that scrolls sideways (a ChoiceChips row): start to end, then back at its start, because a lazy row recomposes its first items. The lazy layer list is checked per position only, because its node ids change.
- Each walk asserts that it started at the start and reached the end. A ScrollBy action animates, so the walk waits until the position stops changing. At the end, every placed clickable in the container must have been seen. A control only reachable by an unwalked scroll fails the test instead of escaping it.
- `ChromeHarness` does not read what a `clearAndSetSemantics` node hides (its children, which the unmerged tree still lists).

The v1.7 sections use `UniqueLabels.duplicates`, which has three rules the v1.6 `assertUnique` keeps out:
- unit suffixes (`LengthUnit.short`, "%", "°") are values, not names;
- a "Type a value for …" cell (`NumberSlider`) is known by its name, not by the value it shows, as `ownLabelsInside` treats a layer row's "100%";
- labels matching the VALUE pattern ("12 px", "45°", "8.0") are values.

Finger sizes (`StateAudit.assertFingerSized`) are checked where the v1.6 audits check them: the Path, Shape and Free-deform pills with the chrome, the layer window, and its menus.

Decided at integration:
- With two or more shape points picked, the strip's delete chip reads "Delete point", because the pill's trash cell is "Delete selected points" (§3.13).
- While the Symmetry tool is active, the Ruler panel leaves out its "Symmetry rulers" section, because the tool's options strip already shows those rulers and "Reset symmetry" (I10: one label, one control on screen). This matches the More menu over the layer window leaving out "Import picture". With any other tool active, the panel lists the symmetry rulers (§3.18).

**Request coverage (`audit/RequestCoverageV17*UiTest`).** Each v1.7 request item (1–20) has one end-to-end check. The checks drive the UI as a finger does: labels come from `LabelsV17` or existing labels, and tags from `V17Tags`. Each runs at both QA sizes (`w392dp-h873dp-xxhdpi` and `w360dp-h760dp-xxhdpi`). Documents are at most 512 × 512.
- One shared driver, `RequestCoverageV17.run(group, widthDp)`, holds three groups: POINTS (items 1, 2, 4, 5, 6, 7, 19), OBJECTS (items 9, 11, 12, 13, 15, 16, 17) and LAYERS (items 3, 8, 10, 14, 18, 20).
- Six classes run it, one group and one size each. Each class has one @Test after `installTestRecomposer` and its own `instrumentedPackages` sandbox: `RequestCoverageV17UiTest` (Points 392), `...Points360UiTest`, `...ObjectsUiTest`, `...Objects360UiTest`, `...LayersUiTest`, `...Layers360UiTest`.
- Each item runs in a `ChromeHarness.section`. A failing item does not hide the next, and the failure message names the item.
- Waits for renders and computed results (Apply, array copies, Pathfinder) use `PerfBudget.ms(10 s)`. The Pathfinder check sets `PathfinderTool.computeDispatcher = Dispatchers.Unconfined`.
- Layer inserts and removals go through `controller.structure` (the app's paths). The test adds no layers of its own outside them.
- Cost: the six classes take about 55 s of JUnit time.

**Flows with objects (`qa17/Flow*RobolectricTest`).**
- `FlowShapePathPathfinder`: rounded corner, Turn into path, Sharp corner, Pathfinder Unite.
- `FlowKernedTextArraySvg`: kerned text, Transform-mode array, SVG outlines.
- `FlowSavedSelectionRotateArray`: saved selection, canvas rotated 90°, Load (Replace), Array from the selection bar.
- `FlowSymmetryVectorSvg`: Rotation × 6 vector stroke, save, reopen, SVG with six envelopes in one path.
- `FlowLinkedFrameRasterize`: middle frame, Rasterize and deform (Distort). The pending transform and the rasterize are separate steps, and the chain heals inside the rasterize step.
- The folder flows and audits are listed under "Layer tree and folders".

**History-tap UI tests.**
- `HistoryTapsArrayAndPathUiTest` (392 dp, real multi-touch) covers two cases.
  - The open Array sheet is hosted, so both fingers must land inside it. The second finger goes on the title "Array". The first finger drags the Count slider, then holds "Increase Count" (it steps on touch-down), then rests on "Apply array". Each time the test checks the live preview while the first finger is still down. The tap then puts it back and undoes one "Edit array". The sheet stays open and Apply does not fire.
  - The Path tool on a reopened, untouched path: one finger drags a point's width slider (a live in-tool step), then a second finger lands. The width rolls back, the untouched path is released, and exactly one document step is undone.
- `HistoryTapsV17ToolsUiTest` covers five controls: the Symmetry ruler chips, the Pathfinder strip, a saved-selection row in the layer window, the Free deform "Smooth mesh" chip and the "More mesh columns" stepper.
  - One two-finger tap over each gives one undo, and the control under the first finger does not fire. Three fingers redo one step.
  - An undo lets an untouched Transform lift go (`EditorController.undo`), so the test lifts again before the next control.
- The Kerning row sits in the text editor sheet, where `HistoryLabels.historyBlocked` (v1.3) holds. The first finger's touch-down kern is checked, then the tap puts the kern back and says "Finish the text first: OK or Cancel". After OK, a tap over the options strip undoes one step.
- Finger discipline (`HistoryTapFingers`): find controls by label or tag, never by fixed dp.
  - Measure, then touch at once: no idle between the measure and the first down.
  - `reach` / `reachInStrip` / `reachSlider` settle after scrolling. A semantics scroll animates: a control still gliding moves away from where it was measured, and a finger on a container still scrolling stops the scroll instead of pressing the control.
  - For controls that act on touch-down or on a drag, `holdThenSecondFinger` / `dragThenSecondFinger` check that change before the second finger lands, so "put back" is proven and not vacuous.
- QA canvas points: `qa3/Qa3FreeCanvas.farFrom` measures the free canvas on screen. Its top is the lowest of the top row, the options strip, the pill and the selection bar. Its bottom is the highest of the ✓ / ✕, the slider rows and the bottom bar. The qa3 pinch tests use it instead of fixed dp, because the pill's Scale row moves the selection bar down to 209–249 dp.
