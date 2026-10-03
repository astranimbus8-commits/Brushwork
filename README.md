# Brushwork

A painting and illustration app for Android in the spirit of ibisPaint and Clip Studio Paint.

## Install on your phone

1. On your phone, open the [Releases](../../releases) page and download the newest `Brushwork-vX.Y.Z.apk` from **Assets**.
2. Open the downloaded file. The first time, Android asks to allow installs from your browser: tap **Settings**, enable **Allow from this source**, then go back.
3. Tap **Install**. If Play Protect warns about an unknown app, choose **More details → Install anyway** (this happens for every app installed outside the Play Store).

Requires Android 8.0 or newer. Every release is signed with the same key, so a newer APK installs over the old one and keeps your artwork.

## Features

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
- Magic wand, lasso (freehand / polygon / curve), rectangle/ellipse selection, Object select (tap an object), copy / cut / paste, bucket fill (tolerance, gap closing), manga frame divider.
- Transform: move/scale/rotate/distort with two-finger pinch, exact numbers with a reference point (scale from the center), delete, and Illustrator-style smart guides that snap to other layers and the canvas.
- Content-aware fill for selections and a Remove brush that fills what you paint over from its surroundings.
- Clone stamp like Photoshop: long-press to set the source; with Aligned on the source travels with your strokes; samples this layer or all layers.
- Text: editable text layers, text boxes with wrapping / background / border, text that wraps around a picture's outline (and re-flows when the picture changes), upright vertical text, text on a line / circle / square / curve (bending or rotating letters), placeholder text, imported fonts (dafont .zip / .ttf / .otf) with favorites.
- Letter scaling: letters grow or shrink one by one from the first to the last (or the reverse) down to a "Smallest letter" size, aligned on their centers, their baseline or their tops.
- Text frames tool: linked text boxes like InDesign. Text that does not fit in one frame flows into the next (a red + shows overflow; tap it and draw or tap the next frame); moving, resizing, unlinking or deleting a frame re-flows the story.
- X / Y pill under the options strip: beveled X and Y cells whose numbers are the sliders (drag a number to move whatever is being placed: Transform, shapes, text, curve and path points, mask parts, the clone source; slide your finger away for fine control, tap to type). Two fingers scale an object only when at least one finger is on it; otherwise they zoom the view.
- Increments everywhere: turn on "#" and every move, size, scale, angle and percentage snaps to a step you choose (10 px, 1 px, 10 %, 15°, 5 % by default; long-press any value to give it its own step). Typed numbers are kept exactly.
- Shapes: line, rectangle, ellipse/circle, polygon with any number of sides, star, arrow; sharp, round, bevel or inverted corners like Illustrator; exact numeric editing. Shapes stay editable on their own layer (tap one to change it again), and "Points" mode lets you add, move, delete and smooth its vertices.
- Bezier curve tool (tap to add points, long-press a point to make it a sharp corner) and polyline tool; plain lines follow the brush size, and each point has its own thickness (0–300 % slider). Handles can be scaled longer or shorter (both, in or out, one point or all) with arrows, a slider or a pinch, and the handle size on screen is adjustable.
- Path tool like Blender's NURBS paths: control points with weights, order 2–6, endpoint and cyclic options, Circle and Capsule quick starts, and "To Bézier" to keep editing it as a curve.
- Snap to objects in every tool (one switch): points, shapes, vertices, selections, text, rulers and frame cuts line up with the canvas, other layers, other points and straight lines in your drawing, such as the lines of the Table filters.
- Smart selection: subject, background, sky, nature, buildings, people, water (on-device).
- Canvas: resize image, canvas size with anchor, trim, crop, rotate/flip canvas, resolution, color mode (RGB / grayscale / 1-bit monochrome).

**Filters (86, in the tool menu → Filters)**
Tone (Exposure, Contrast, Highlights, Shadows, Whites, Blacks), Brightness & Contrast, Tone Curve, Color Balance, Hue/Saturation/Brightness, Level Adjustment, Replace Color, Gradation Map, Posterize, Invert, Grayscale, Black & White, Monocolor, Change Drawing Color, Extract Line Drawing, Find Edges · Gaussian, Zooming, Spin and Motion Blur, Mosaic, Unsharp Mask, Frosted Glass (normal/zooming/moving) · Stroke (both/outer/inner), Stained Glass, Wet Edge, Glow (inner/outer), Bevel (outer), Relief, Relief HQ, Waterdrop, Satin, Drop Shadow, Extrude Parallel, God Rays · Parallel/Concentric/Radial Line Gradation, Radial Line, Speed Line, Clouds, QR Code, Watercolor, Anime Background, Manga Background, Background Removal · Chromatic Aberration (moving/zooming), Glitch, Noise, Retro Game, Oil Paint, Chrome, Bloom, Cross Filter, Sheer (cross/line/square/hex/circle) · Crystallize, Hexagonal/Square/Triangular Pixelate, Pointillize, Dots (hexagonal/square) · Expansion, Fish Lens, Sphere Lens, Wave, Ripple, Twirl, Polar Coordinates, Tile (count/size), Table (count/size), Blur Frame, Rain. Every filter has a live preview, respects the selection and can be undone.

## Build from source

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```
./gradlew testDebugUnitTest assembleDebug
```

Every push to `main` runs the tests and builds a signed APK in GitHub Actions (download it from the run's artifacts). Pushing a `v*` tag publishes a GitHub Release with the APK attached. Release signing uses repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); without them the APK is signed with a debug key.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organized.

## Testing status

The app is covered by 2900+ JVM/Robolectric tests, including real Skia rendering of the compositor, brushes, tools and filters, and whole-editor UI smoke tests. It has not yet been tried on a wide range of physical devices; please open an issue if something misbehaves on yours.

## Credits

- Scene segmentation model: Autoseg-EdgeTPU-S from the TensorFlow Model Garden (Apache 2.0), see `app/src/main/assets/models/NOTICE.txt`.
- Subject segmentation: Google ML Kit (downloaded on demand by Google Play services).
- QR codes: ZXing (Apache 2.0).
