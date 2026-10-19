# Brushwork

A painting and illustration app for Android in the spirit of ibisPaint and Clip Studio Paint.

## Install on your phone

1. On your phone, open the [Releases](../../releases) page and download the newest `Brushwork-vX.Y.Z.apk` from **Assets**.
2. Open the downloaded file. The first time, Android asks to allow installs from your browser: tap **Settings**, enable **Allow from this source**, then go back.
3. Tap **Install**. If Play Protect warns about an unknown app, choose **More details → Install anyway** (this happens for every app installed outside the Play Store).

Requires Android 8.0 or newer. Every release is signed with the same key, so a newer APK installs over the old one and keeps your artwork.

**Install and backup.** Install 1.9 over 1.8; do not uninstall 1.8 first. Then choose a backup folder ("Choose backup folder" on the notice in the gallery, "Backup folder…" in the gallery's ⋮ menu, or "Choose backup folder" under "Backup" in an artwork's "Settings"). After any later reinstall, choose the same folder and tap "Restore them" (or "Restore artworks…" in the empty gallery or in Settings > Backup) to get your artworks back. On Android 10 and later, if Android asks whether to keep app data when you uninstall, choose Keep.

**Opening 1.9 artworks in 1.8.** Artworks with several texts in one text layer open correctly in 1.8, but editing that layer in 1.8 keeps only its first text, and moving, rotating or scaling it in 1.8 leaves the other texts as pixels. Paper shows as transparent in 1.8, and lists, justify, kerning styles and text-path points are lost if you edit that text in 1.8.

**Opening 1.8 artworks in 1.7.** Brushwork 1.7 opens every 1.8 artwork and shows each layer as 1.8 drew it. Saving it in 1.7 without editing keeps everything except the arrays' looks. A layer edited in 1.7 loses its 1.8 data: mirror copies and curve settings are dropped, a cog becomes a plain shape without its hole, and pixels that 1.7 does not redraw can remain on that layer. Back in 1.8, an array whose look 1.7 dropped keeps showing the look until you change the array; that first change can leave fragments of the old copies beside the new ones, and Undo gives back the picture 1.7 left.

**Opening 1.7 artworks in 1.6.** Projects with layer folders need Brushwork 1.7. Everything else still opens in 1.6, which shows arrays as pixels; a symmetric stroke on a vector layer keeps only its original copy if 1.6 redraws that layer. Saving such an artwork in 1.6 turns its arrays into plain pixels for good and drops its saved selections and symmetry setting.

## Features

**New in 1.9**
- Several points' thickness at once in Curve, Polyline and Path: with two or more points selected, the More row's "Point thickness" is the same blue slider as for one point. It reads "Mixed" when they differ ("×1.25" and so on while you drag, which keeps a taper's proportions); "Thinner point" / "Thicker point" step every point, and the typing box ("Type the point thickness") sets them all to a number or changes each with +10, -10, *2 or /2. Thickness rises again from 0 %, and at 0 % a drag that starts just beside the slider's start still moves its thumb. In Path, "Point weight" does the same for the selected points' weights.
- A tool's More row and the object bar's extra row stay open while you draw, pan, zoom or undo, until you close them with "Show more tool options" or "Show more object actions"; each tool remembers whether its row was open, also after the app restarts.
- Justify text: in the text sheet's "Alignment" section, "Justify lines" spreads each line to the full width and "Justify the last line too" stretches the last line as well; while only "Justify lines" is on, the align buttons set the last line ("Last line:"). Vertical text has "Justify columns" and "Justify the last column too" in its "Column alignment" section ("Last column:").
- Kerning styles: under the "Font kerning" switch in the text sheet's "Spacing" section, "Pair kerning" offers "Off", "Auto", "Optical" and "Metrics" ("Off" turns "Font kerning" off, the others turn it on).
- A "3 words" placeholder: the text sheet's "Placeholder text" amounts now start with "3 words" (for example "Lorem ipsum dolor").
- Bullets and numbering: the text sheet's "Lists" section turns the lines into "Bullets" (• ◦ ▪ – ★ ✓, or your own with "Other bullet…") or "Numbering" (styles such as 1., a., i. and ①, from a "First number"), and "List settings" sets the "Marker size", "Marker thickness", "Marker gap" and "Marker font". Lists are for horizontal text.
- Undo and redo inside filters and effects: while a filter's panel is open, every finished change is its own step, and Undo and Redo (the buttons or two- and three-finger taps) walk back through them ("Undo: Radius"); when Undo has taken back every change, it says "Back at the start: Undo again closes the effect". A control that differs from its default shows a reset icon ("Reset <control>"), and "Reset to defaults" is now one step. In an adjustment layer's "Adjust: <layer>" sheet every finished change is its own undo step too ("Change <control>"), and each control, "Amount" included, has its reset icon.
- Crash reports: after Brushwork closes unexpectedly, the gallery shows "Brushwork closed unexpectedly" with "Copy", "Share" and "Dismiss". The newest 20 reports stay on the phone under "Diagnostics" at the end of an artwork's "Settings": "Crash reports (n)" with "Copy report …" and "Share report …" for each one, "Share all" and "Delete all reports".
- Quicker stroke ends and adjustment masks: lifting the finger after a brush, eraser, smudge or blur stroke now redraws only the part of the canvas the stroke touched. On an adjustment layer, with "Fast adjustment preview" on (the default), dragging a linear or radial mask handle or painting a brush mask previews at a lower resolution while your finger moves and sharpens when you hold still or let go; the switch's description now reads "Shows adjustments, masks and effects at a lower resolution while you drag".
- "Chromatic Aberration (Moving)" and "Chromatic Aberration (Zooming)" end with "Blend mode" (the 17 layer blend modes) and "Opacity"; at Normal and 100 % they look as before.
- Erase with Curve, Polyline, Path and Shape: tap the eraser switch in the bottom bar ("Erase with curve", "Erase with polyline", "Erase with path", "Erase with shape") or choose "Erase instead of paint" at the end of the stroke menu, and the line, fill or shape erases instead of painting, with a dashed outline while you draw and one undo step. On vector and shape layers a mode button (it shows the current mode's icon; TalkBack names it "Erase mode: Object" and so on) offers "Object", "Partial" and "To intersection", or "Turn into pixels". The Eraser now erases a shape layer's shapes as objects, as on a vector layer; its "Partial" also cuts shapes and fills, and the pieces stay editable as paths; "Turn into pixels" at the end of its More row asks "Turn the layer into pixels?" first ("Erase pixels" or "Cancel"; Undo turns it back). On vector layers the Eraser takes the objects away as you move (1.8 dimmed them).
- Text on a path: the "Shape / path" section's "Path points" has "Add points", "Edit as path" and "Edit as polyline". They open a point bar ("Add point", "Delete point", "Sharp corner", "Points: N" and ✓ "Done editing points"), the text follows the points as you drag them, and with no point selected a tap on the path between two points adds one there (up to 200 points).
- Several texts in one text layer and several shapes in one layer: a new text joins the selected text layer (up to 64 texts), and a new shape joins the selected shape layer, which becomes a vector layer of editable shapes. "New layer each time" (in the Text tool's More row, and in "Shape settings") gives each new one its own layer as before. Touching a polyline while the Curve tool is active (or a curve in Polyline) switches to the right tool and opens the line ("Switched to Polyline to edit this line"). "Duplicate" is on the selection bar after "Save": on a pixel layer the copy of the selected pixels floats in Transform right where they are; drag, scale or rotate it, then tap the check mark to place it as one undo step, and the selection moves with the copy (the cross or Undo while it floats leaves the layer as it was). On a vector or text layer the objects or texts the selection touches are copied slightly offset ("Made N copies in this layer"). The Text tool's More row has "Duplicate" for the open text, and the Shape tool's for an open shape on a vector layer or one that joins the selected shape layer.
- New canvas starts with one layer, "Layer 1", whatever you pick under "Background": "White", "Transparent", "Color…" or "Paper" (an empty layer over white paper, so erasing shows the paper). The dialog remembers your last choice and your last "Color…" color. "Import picture" in the gallery starts with the picture as the only layer. For an artwork on paper, "Export PNG", "Export JPG", "Share" and the gallery thumbnail show the paper; "Export SVG…" and "Export PDF…" have "Include paper" (on by default).
- A backup folder: choose one (such as Documents/Brushwork) with "Choose backup folder" on the gallery's notice or "Backup folder…" in the gallery's ⋮ menu, and every save, new artwork, rename and duplicate is copied there; a deleted artwork is kept there for up to 30 days. After a reinstall, choosing the old folder offers "Restore them", and "Restore artworks…" (in the empty gallery, the ⋮ menu or an artwork's Settings > Backup) lists what it holds, with the artworks not already in the gallery ticked, except those deleted in the last 30 days, which are listed apart: tap "Restore N artworks". Nothing in the gallery is overwritten, and "Also restore settings, brushes and palettes" brings those back with them, at once, also in an open artwork.
- Shape undo goes back one edit: while a shape is open, Undo (or a two-finger tap) takes back only its last move, resize, rotation, typed value or point edit, and the check mark still places the whole shape as one step. "Bevel points" in Polyline and in the Shape tool's "Points" mode cuts the selected sharp corners, with "Amount", "Fewer segments" / "More segments" and "Done", as one undo step. "Merge down" of two text layers, or of two vector or shape layers, keeps them editable, and "Merge into pixels?" asks first when it cannot ("Merge as pixels" or "Cancel"; for a see-through layer with overlapping objects also "Keep editable"); "Merge folder" keeps a folder of only text layers, or only vector and shape layers, editable when it can. Texts that share a layer move with "Bring forward" and "Send backward" in the Text tool's More row (shapes too, in the Shape tool's), and the object bar's "Forward" and "Backward" now move one step even when nothing overlaps.

**Eraser change.** On shape layers the Eraser no longer turns the layer into pixels by itself: as on vector layers, choose "Object", "Partial" or "To intersection", or tap "Turn into pixels" in the More row. "Partial" now also cuts shapes and fills into pieces that stay editable as paths.

**Limits.** How many layers an artwork can hold depends on its size and on the memory Android gives the app. On a phone with a 512 MB app heap that is up to 25 layers at 1080×2408, 14 at 2048×2048, 5 at A4 300 DPI and 3 at 3000×4000; phones with a larger heap allow more. Since 1.9 each layer mask counts as a layer. The New canvas dialog shows the number for your phone ("Layers on this device").

**Fixed in 1.9**
- Fewer crashes: when a background task of the editor or of a screen fails, Brushwork shows "Something went wrong (…). Your artwork is safe.", resets the tool, keeps a report in "Diagnostics" and goes on; before, the app closed.
- Magnetic lasso and Color select prepare the picture in the background at a reduced size, so large canvases no longer freeze or close the app; when memory runs short they say so instead (for example "Not enough memory for Color select").
- Lower memory use: several filters and saving need less memory on large artworks, and so do the gallery's thumbnails. When memory runs critically low, the oldest undo steps are cleared ("Memory is low: older undo steps were cleared") instead of the app crashing, and a filter, merge, export or share that would not fit is refused with "Not enough memory for …", leaving the artwork unchanged.
- No more long freezes: work still running 1.5 seconds after you tap Undo, draw or switch layers (a vector layer still being drawn, array copies, a saved selection, or a "Partial" erase on a vector or shape layer) is cancelled with "… took too long and was cancelled", and the layer stays as it was; that Undo undoes nothing else. Pressing Home or switching apps while work is finishing no longer freezes the screen: the work finishes and is saved in the background.
- Large system font sizes: the tool strips, the object bar, the X / Y pill, number fields and the gallery's artwork cards shrink their labels to fit instead of cutting them off, never below their size at the normal font size; at the normal size nothing changes, and a long artwork name still ends in "…".

**New in 1.8**
- Mirrors for curves, polylines, paths, shapes and the clone stamp: in the Symmetry tool pick the Mirror, Kaleidoscope, Rotation, Array or Perspective array ruler and tap "Done" to go back to the tool you came from. On a vector layer the copies stay live, even after the ruler is turned off: tap any copy to reopen the object, and every copy follows your edits. "Detach mirror copies" (object bar, "Show more object actions") makes them separate objects; "Remove mirror copies" (the strip's More row) drops them. Transform moves an object with its copies; the eraser's "Object" erases it with all of them, while "Partial" and "To intersection" cut only the copy you cross.
- Perspective array copies of curves and shapes become separate plain paths on a vector layer (only the copies that touch the canvas), or pixels on a paint layer.
- Mirrored clone stamp: each copy clones through its own mirror, up to 32 copies per stroke. Under the Perspective array, drag the cell's corners outward in the Symmetry tool first.
- "Select several" is now an icon at the end of the X / Y pill's first row (Curve, Polyline, Path, Shape "Points", Free deform), and it stays when the pill is folded.
- Array look: the Array sheet's "Look" tab changes opacity, size and turn from the first copy to the last ("Start opacity" / "End opacity" and so on), with a "Pace" (Even, Speeds up, Slows down, S-curve), and tints the copies along a color gradient ("Tint colors" with presets such as Sunset, and "Tint strength"). "Reset look" gives plain copies again.
- Magnetic lasso: tap points along an edge and each one snaps onto it, with a loupe above your finger. Drag a point to move it; "Delete point"; close with a tap on the first point, "Close outline" or a double tap. The More row has "Edge reach", "Edge contrast" and "Snap to edge".
- Color select: tap up to 8 colors on the canvas (or "Add from the color picker", "Use the current color"), or choose a "Color range" preset (Reds to Magentas, Highlights, Midtones, Shadows, Skin tones, Vivid colors, Muted colors). Drag "Fuzziness" while a red preview follows, then "Select colors". "Invert range" and "Refer: Layer" / "Refer: Canvas" are on the More row.
- In the Magnetic lasso and Color select, a two-finger tap takes back the last point or sample and a three-finger tap brings it back.
- Fringe colors for Chromatic Aberration (Moving and Zooming) and Glitch: RGB, Red/Cyan, Magenta/Green, Blue/Yellow, or your own colors from the swatches ("No color" for an empty fringe). Glitch also has "Color of the static", whose opacity sets how strong the static is. These colors are not saved with the artwork: each time you open the filter it starts from RGB.
- Generated curves in the Curve and Path tools ("Curves", Spirals and waves): Archimedean, Golden, Logarithmic and Fermat spirals, Sine wave and Rose. "Change the generated curve" edits turns, inner size, growth per turn, direction, waves, petals and points per turn live (up to 400 points, and the sheet says when you reach it). Tap the curve later to edit it again, until you move a point by hand. Moving, turning, evenly scaling or flipping it with Transform keeps its settings.
- Cog shape: Shape type → "Cog", with 3 to 64 teeth, tooth depth, width and taper, and a hole; round, bevel or inverted corners. "Turn into path" makes a cog with a hole one compound path.
- Clearer top menus: tool options that do not fit move to a More row under the strip (the "Show more tool options" chevron at its right end), in up to 3 lines. Hold any icon in the strip or a top-row circle to see its name. The top row's "More options" menu is grouped into Clipboard, Bring in, Send out, View and This artwork. The selection bar shows ‹ and › when there is more to slide to, and "Choose interpolation" in Transform is captioned "Interpolation".
- Screen readers: gradient color stops ("Color stop 2 of 4, #FF8800"), every slider ("Opacity slider, 50 %") and the seed buttons ("New random seed") have names.

**New in 1.7**
- Several points at once in Shape (Points), Curve, Polyline and Path: "Select several", then tap points or drag a box on empty canvas. Drag one of them to move them all, drag the dashed box's corners and edges or pinch inside it to scale, turn its knob to rotate, and type a thickness or weight for all of them (the field reads "Mixed" when they differ).
- "Point roundness" for each selected shape corner, and "Reset point roundness".
- Live Array like Blender's modifier: Line, Circle, Curve (along a path you draw or pick) or Transform, with count, spacing, sweep, turn and scale per copy. Make one with "Array from selection" or the layer menu's "Array…" on a text, shape or vector layer, and change it any time with "Edit array"; "Edit source pixels", "Apply array" and "Remove array" in the layer menu.
- "Sharp corner" on any middle point of a Path.
- "Turn into path" in the Shape tool's Points mode turns the picked corners into an editable Path (weights, thickness, sharp corners) in place.
- "Stroke only", "Fill only" and "Stroke and fill" for Paths, Curves and Polylines.
- Layer folders like ibisPaint: "New folder", "Put in new folder", drag or swipe layers in and out, pass through or a blend mode and opacity, clipping to a folder, "Merge folder", "Layer from folder", open / close with the thumbnail.
- The X / Y pill shows its step next to "#" ("# 10"; hold "#" to change it) and has a Scale X / Scale Y row with "Keep scale proportions".
- Two-finger tap = undo and three-finger tap = redo also over the toolbars, options strip, layer window, sheets and the pill; the control under your fingers does not fire.
- Transform keeps text, shapes, vector curves and arrays editable: move, scale and rotate them, then keep editing them with their own tool.
- Curve, Polyline and Path move and scale the whole curve: with no point selected the pill reads "Center" (X, Y and Scale), or "Select all points" and use the box.
- A trash cell on the pill in Shape, Curve, Polyline, Path and Text: "Delete shape", "Delete path", "Delete curve", "Delete polyline", "Delete text", or "Delete selected points".
- Saved selections: "Save" on the selection bar or + on the Selection Layer row; each row in the layer window can load, add to, subtract from, intersect with, update, rename or delete.
- Equations in number fields: "(3+4)*2", "100/2", or "*1.5" / "/2" to change the current value, with a live "= …" result and + − × ÷ ( ) keys.
- Free deform: Transform → "Free deform", a mesh up to 12 × 12 ("More mesh columns" / "More mesh rows"), several vertices at once, "Smooth mesh".
- Kerning per letter pair: put the cursor between two letters (or select some) and set "Kerning" in the text editor; "Font kerning" switches the font's own kerning off. Kerns follow their letters across linked frames.
- Symmetry tool like ibisPaint: Mirror, Kaleidoscope, Rotation, Array and Perspective array rulers for the brush, eraser and smudge, also in the Ruler sheet; on a vector layer a symmetric stroke stays one editable object.
- Pathfinder like Illustrator for shapes and vector paths: Unite, Minus front, Minus back, Intersect, Exclude, Divide, Trim, Merge, Crop, Outline; one undo reverts it.

**Fixed in 1.7**
- Path thickness per point: a Path with 3 points and 0 % ends now shows its middle point's thickness at once (before, nothing showed until it had 5 points). Paths saved by v1.6 keep their old thickness until they are edited.
- Path: with the first point selected, new points go before it, so a path grows from its start.

**Gallery and files**
- Gallery of your artworks with thumbnails; rename, duplicate, delete, export PNG/JPG to the phone's gallery, share.
- Export SVG and PDF (layers as groups / PDF layers, vector objects as real paths, text as text or outlines); import SVG as editable vector layers and PDF pages as pictures, into an artwork or as a new artwork ("New from SVG or PDF"). Brushwork's own SVG/PDF files re-open with every layer intact.
- Autosave: when you leave the app, when you go back to the gallery, and every 45 s while drawing (adjustable).
- New canvas with a custom size in pixels, inches, centimeters, millimeters or points plus DPI, and presets: 500×500, 1080×2408, 2048×2048, 1536×2048, 1080×1920, 1920×1080 and more; print sizes A3–A6, B4/B5, US Letter, Legal, Tabloid, postcard and manga manuscript at 72–600 DPI. It starts with one layer: white, transparent, a color of your choice, or empty over white paper.
- Start from a picture ("Import picture"): the picture is the artwork's only layer.

**Painting**
- ibisPaint-style screen: a row of round buttons at the top (undo, redo, vector, selection, stabilizer, grid, ruler, more), the tool's options in a floating strip, size and opacity sliders above a bottom bar (eraser switch, tool menu, brush size, color, hide interface, layers, back), and a two-column tool menu.
- 16+ brushes (pens, G-pen, pencils, airbrush, marker, calligraphy, watercolor, chalk, spray, pixel pen…), erasers, smudge and blur tools, pressure and taper, full brush settings.
- Undo/redo, **two-finger tap = undo, three-finger tap = redo**, pinch to zoom/rotate, flip view.
- Stabilizer: smoothing, or a Blender-style **rope (lazy mouse)** where painting happens at the end of a string you drag.
- Rulers: straight, circular, elliptical and radial, with exact numeric control (position, angle, radius…) in px/in/cm/mm/pt and nudge buttons (e.g. move the circular ruler 3 px left).
- Grids: square, rule of thirds, isometric, diagonal.

**Color**
- HSB color wheel, RGB and HSB sliders, hex input, eyedropper, saved palettes and recent colors.

**Layers**
- Add, delete, duplicate, reorder by dragging, merge down, flip horizontal/vertical, opacity, 17 blend modes, clipping masks, layer masks, lock and alpha lock, import picture.
- ibisPaint-style layer window: a canvas preview with quick buttons on the left, large rows (thumbnail, eye, opacity and blend mode, locks, drag handle) with a Selection Layer row, an icon strip on the right, and blend mode and opacity rows at the bottom.
- **Vector mode** (the Vector button in the top bar): draw on vector layers with the tools you already use. Brush strokes, shapes and curves stay editable objects; the eraser removes whole objects, cuts strokes, shapes and fills into pieces that stay editable (a cut shape's pieces are paths), or erases up to where lines cross; the bucket recolors objects or fills enclosed areas; Lasso / Select shape pick objects and Transform moves, scales, rotates and distorts them without losing quality; an Object bar duplicates, recolors and reorders them.
- **Lightroom-style masks** (Masks tool): linear, radial and brush mask parts (add / subtract / intersect, invert, density) that stay editable, on adjustment layers whose effect (Tone, Brightness & Contrast, Levels, Hue/Saturation, Color Balance…) is applied live to the layers below and can be changed any time; any filter can also be applied through a mask. Dragging a slider, a mask handle or the layer opacity shows a fast preview first and sharpens within a moment ("Fast adjustment preview" in Settings).

**Tools**
- Magic wand, lasso (freehand / polygon / curve), magnetic lasso, color select (sampled colors or a color range), rectangle/ellipse selection, Object select (tap an object), copy / cut / paste, bucket fill (tolerance, gap closing), manga frame divider.
- Transform: move/scale/rotate/distort with two-finger pinch, exact numbers with a reference point (scale from the center), delete, and Illustrator-style smart guides that snap to other layers and the canvas.
- Content-aware fill for selections and a Remove brush that fills what you paint over from its surroundings.
- Clone stamp like Photoshop: long-press to set the source; with Aligned on the source travels with your strokes; samples this layer or all layers.
- Text: editable text layers, text boxes with wrapping / background / border, text that wraps around a picture's outline (and re-flows when the picture changes), upright vertical text, text on a line / circle / square / curve (bending or rotating letters), placeholder text, imported fonts (dafont .zip / .ttf / .otf) with favorites.
- Letter scaling: letters grow or shrink one by one from the first to the last (or the reverse) down to a "Smallest letter" size, aligned on their centers, their baseline or their tops.
- Text frames tool: linked text boxes like InDesign. Text that does not fit in one frame flows into the next (a red + shows overflow; tap it and draw or tap the next frame); moving, resizing, unlinking or deleting a frame re-flows the story.
- X / Y pill under the options strip: beveled X and Y cells whose numbers are the sliders (drag a number to move whatever is being placed: Transform, shapes, text, curve and path points, mask parts, the clone source; slide your finger away for fine control, tap to type). Two fingers scale an object only when at least one finger is on it; otherwise they zoom the view.
- Increments everywhere: turn on "#" and every move, size, scale, angle and percentage snaps to a step you choose (10 px, 1 px, 10 %, 15°, 5 % by default; long-press any value to give it its own step). Typed numbers are kept exactly.
- Shapes: line, rectangle, ellipse/circle, polygon with any number of sides, star, arrow, cog; sharp, round, bevel or inverted corners like Illustrator; exact numeric editing. Shapes stay editable, on their own layer or together in the selected shape layer (tap one to change it again), and "Points" mode lets you add, move, delete and smooth its vertices.
- Bezier curve tool (tap to add points, long-press a point to make it a sharp corner) and polyline tool; plain lines follow the brush size, and each point has its own thickness (0–300 % slider). Handles can be scaled longer or shorter (both, in or out, one point or all) with arrows, a slider or a pinch, and the handle size on screen is adjustable.
- Path tool like Blender's NURBS paths: control points with weights, order 2–6, endpoint and cyclic options, Circle and Capsule quick starts, and "To Bézier" to keep editing it as a curve.
- Snap to objects in every tool (one switch): points, shapes, vertices, selections, text, rulers and frame cuts line up with the canvas, other layers, other points and straight lines in your drawing, such as the lines of the Table filters.
- Smart selection: subject, background, sky, nature, buildings, people, water (on-device).
- Canvas: resize image, canvas size with anchor, trim, crop, rotate/flip canvas, resolution, color mode (RGB / grayscale / 1-bit monochrome).

**Filters (86, in the tool menu → Filters)**
Tone (Exposure, Contrast, Highlights, Shadows, Whites, Blacks), Brightness & Contrast, Tone Curve, Color Balance, Hue/Saturation/Brightness, Level Adjustment, Replace Color, Gradation Map, Posterize, Invert, Grayscale, Black & White, Monocolor, Change Drawing Color, Extract Line Drawing, Find Edges · Gaussian, Zooming, Spin and Motion Blur, Mosaic, Unsharp Mask, Frosted Glass (normal/zooming/moving) · Stroke (both/outer/inner), Stained Glass, Wet Edge, Glow (inner/outer), Bevel (outer), Relief, Relief HQ, Waterdrop, Satin, Drop Shadow, Extrude Parallel, God Rays · Parallel/Concentric/Radial Line Gradation, Radial Line, Speed Line, Clouds, QR Code, Watercolor, Anime Background, Manga Background, Background Removal · Chromatic Aberration (moving/zooming, with choosable fringe colors, blend mode and opacity), Glitch (choosable fringe and static colors), Noise, Retro Game, Oil Paint, Chrome, Bloom, Cross Filter, Sheer (cross/line/square/hex/circle) · Crystallize, Hexagonal/Square/Triangular Pixelate, Pointillize, Dots (hexagonal/square) · Expansion, Fish Lens, Sphere Lens, Wave, Ripple, Twirl, Polar Coordinates, Tile (count/size), Table (count/size), Blur Frame, Rain. Every filter has a live preview, respects the selection and can be undone.

## Build from source

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```
./gradlew testDebugUnitTest assembleDebug
```

Every push to `main` runs the tests and builds a signed APK in GitHub Actions (download it from the run's artifacts). Pushing a `v*` tag publishes a GitHub Release with the APK attached. Release signing uses repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); without them the APK is signed with a debug key.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organized.

## Testing status

The app is covered by 4700+ JVM/Robolectric tests, including real Skia rendering of the compositor, brushes, tools and filters, and whole-editor UI smoke tests. It has not yet been tried on a wide range of physical devices; please open an issue if something misbehaves on yours.

## Credits

- Scene segmentation model: Autoseg-EdgeTPU-S from the TensorFlow Model Garden (Apache 2.0), see `app/src/main/assets/models/NOTICE.txt`.
- Subject segmentation: Google ML Kit (downloaded on demand by Google Play services).
- QR codes: ZXing (Apache 2.0).
