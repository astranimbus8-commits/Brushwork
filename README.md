# Brushwork

A painting and illustration app for Android in the spirit of ibisPaint and Clip Studio Paint.

## Install on your phone

1. On your phone, open the [Releases](../../releases) page and download the newest `Brushwork-vX.Y.Z.apk` from **Assets**.
2. Open the downloaded file. The first time, Android asks to allow installs from your browser: tap **Settings**, enable **Allow from this source**, then go back.
3. Tap **Install**. If Play Protect warns about an unknown app, choose **More details → Install anyway** (this happens for every app installed outside the Play Store).

Requires Android 8.0 or newer. Every release is signed with the same key, so a newer APK installs over the old one and keeps your artwork.

**Opening 1.8 artworks in 1.7.** Brushwork 1.7 opens every 1.8 artwork and shows each layer as 1.8 drew it. Saving it in 1.7 without editing keeps everything except the arrays' looks. A layer edited in 1.7 loses its 1.8 data: mirror copies and curve settings are dropped, a cog becomes a plain shape without its hole, and pixels that 1.7 does not redraw can remain on that layer. Back in 1.8, an array whose look 1.7 dropped keeps showing the look until you change the array; that first change can leave fragments of the old copies beside the new ones, and Undo gives back the picture 1.7 left.

**Opening 1.7 artworks in 1.6.** Projects with layer folders need Brushwork 1.7. Everything else still opens in 1.6, which shows arrays as pixels; a symmetric stroke on a vector layer keeps only its original copy if 1.6 redraws that layer. Saving such an artwork in 1.6 turns its arrays into plain pixels for good and drops its saved selections and symmetry setting.

## Features

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
- New canvas with a custom size in pixels, inches, centimeters, millimeters or points plus DPI, and presets: 500×500, 1080×2408, 2048×2048, 1536×2048, 1080×1920, 1920×1080 and more; print sizes A3–A6, B4/B5, US Letter, Legal, Tabloid, postcard and manga manuscript at 72–600 DPI.
- Start from a picture.

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
- **Vector mode** (the Vector button in the top bar): draw on vector layers with the tools you already use. Brush strokes, shapes and curves stay editable objects; the eraser removes whole objects, cuts strokes or erases up to where lines cross; the bucket recolors objects or fills enclosed areas; Lasso / Select shape pick objects and Transform moves, scales, rotates and distorts them without losing quality; an Object bar duplicates, recolors and reorders them.
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
- Shapes: line, rectangle, ellipse/circle, polygon with any number of sides, star, arrow, cog; sharp, round, bevel or inverted corners like Illustrator; exact numeric editing. Shapes stay editable on their own layer (tap one to change it again), and "Points" mode lets you add, move, delete and smooth its vertices.
- Bezier curve tool (tap to add points, long-press a point to make it a sharp corner) and polyline tool; plain lines follow the brush size, and each point has its own thickness (0–300 % slider). Handles can be scaled longer or shorter (both, in or out, one point or all) with arrows, a slider or a pinch, and the handle size on screen is adjustable.
- Path tool like Blender's NURBS paths: control points with weights, order 2–6, endpoint and cyclic options, Circle and Capsule quick starts, and "To Bézier" to keep editing it as a curve.
- Snap to objects in every tool (one switch): points, shapes, vertices, selections, text, rulers and frame cuts line up with the canvas, other layers, other points and straight lines in your drawing, such as the lines of the Table filters.
- Smart selection: subject, background, sky, nature, buildings, people, water (on-device).
- Canvas: resize image, canvas size with anchor, trim, crop, rotate/flip canvas, resolution, color mode (RGB / grayscale / 1-bit monochrome).

**Filters (86, in the tool menu → Filters)**
Tone (Exposure, Contrast, Highlights, Shadows, Whites, Blacks), Brightness & Contrast, Tone Curve, Color Balance, Hue/Saturation/Brightness, Level Adjustment, Replace Color, Gradation Map, Posterize, Invert, Grayscale, Black & White, Monocolor, Change Drawing Color, Extract Line Drawing, Find Edges · Gaussian, Zooming, Spin and Motion Blur, Mosaic, Unsharp Mask, Frosted Glass (normal/zooming/moving) · Stroke (both/outer/inner), Stained Glass, Wet Edge, Glow (inner/outer), Bevel (outer), Relief, Relief HQ, Waterdrop, Satin, Drop Shadow, Extrude Parallel, God Rays · Parallel/Concentric/Radial Line Gradation, Radial Line, Speed Line, Clouds, QR Code, Watercolor, Anime Background, Manga Background, Background Removal · Chromatic Aberration (moving/zooming, with choosable fringe colors), Glitch (choosable fringe and static colors), Noise, Retro Game, Oil Paint, Chrome, Bloom, Cross Filter, Sheer (cross/line/square/hex/circle) · Crystallize, Hexagonal/Square/Triangular Pixelate, Pointillize, Dots (hexagonal/square) · Expansion, Fish Lens, Sphere Lens, Wave, Ripple, Twirl, Polar Coordinates, Tile (count/size), Table (count/size), Blur Frame, Rain. Every filter has a live preview, respects the selection and can be undone.

## Build from source

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```
./gradlew testDebugUnitTest assembleDebug
```

Every push to `main` runs the tests and builds a signed APK in GitHub Actions (download it from the run's artifacts). Pushing a `v*` tag publishes a GitHub Release with the APK attached. Release signing uses repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); without them the APK is signed with a debug key.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organized.

## Testing status

The app is covered by 4200+ JVM/Robolectric tests, including real Skia rendering of the compositor, brushes, tools and filters, and whole-editor UI smoke tests. It has not yet been tried on a wide range of physical devices; please open an issue if something misbehaves on yours.

## Credits

- Scene segmentation model: Autoseg-EdgeTPU-S from the TensorFlow Model Garden (Apache 2.0), see `app/src/main/assets/models/NOTICE.txt`.
- Subject segmentation: Google ML Kit (downloaded on demand by Google Play services).
- QR codes: ZXing (Apache 2.0).
