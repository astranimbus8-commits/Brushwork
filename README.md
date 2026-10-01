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
- Autosave: when you leave the app, when you go back to the gallery, and every 45 s while drawing (adjustable).
- New canvas with a custom size in pixels, inches, centimeters, millimeters or points plus DPI, and presets: 500×500, 1080×2408, 2048×2048, 1536×2048, 1080×1920, 1920×1080 and more; print sizes A3–A6, B4/B5, US Letter, Legal, Tabloid, postcard and manga manuscript at 72–600 DPI.
- Start from a picture.

**Painting**
- ibisPaint-style hotbar: tool, brush/eraser switch, brush size, color, layers, undo, redo; side sliders for size and opacity.
- 16+ brushes (pens, G-pen, pencils, airbrush, marker, calligraphy, watercolor, chalk, spray, pixel pen…), erasers, smudge and blur tools, pressure and taper, full brush settings.
- Undo/redo, **two-finger tap = undo, three-finger tap = redo**, pinch to zoom/rotate, flip view.
- Stabilizer: smoothing, or a Blender-style **rope (lazy mouse)** where painting happens at the end of a string you drag.
- Rulers: straight, circular, elliptical and radial, with exact numeric control (position, angle, radius…) in px/in/cm/mm/pt and nudge buttons (e.g. move the circular ruler 3 px left).
- Grids: square, rule of thirds, isometric, diagonal.

**Color**
- HSB color wheel, RGB and HSB sliders, hex input, eyedropper, saved palettes and recent colors.

**Layers**
- Add, delete, duplicate, reorder by dragging, merge down, flip horizontal/vertical, opacity, 17 blend modes, clipping masks, layer masks, lock and alpha lock, import picture.

**Tools**
- Magic wand, lasso (freehand / polygon / curve), rectangle/ellipse selection, Object select (tap an object), copy / cut / paste, bucket fill (tolerance, gap closing), manga frame divider.
- Transform: move/scale/rotate/distort with two-finger pinch, exact numbers with a reference point (scale from the center), delete, and Illustrator-style smart guides that snap to other layers and the canvas.
- Content-aware fill for selections and a Remove brush that fills what you paint over from its surroundings.
- Text: editable text layers, text boxes with wrapping / background / border, upright vertical text, text on a line / circle / square / curve (bending or rotating letters), placeholder text, imported fonts (dafont .zip / .ttf / .otf) with favorites.
- Shapes: line, rectangle, ellipse/circle, polygon with any number of sides, star, arrow; sharp, round, bevel or inverted corners like Illustrator; exact numeric editing. Shapes stay editable on their own layer (tap one to change it again), and "Points" mode lets you add, move, delete and smooth its vertices.
- Bezier curve tool (tap to add points, long-press a point to make it a sharp corner) and polyline tool.
- Snap to objects in every tool (one switch): points, shapes, vertices, selections, text, rulers and frame cuts line up with the canvas, other layers, other points and straight lines in your drawing, such as the lines of the Table filters.
- Smart selection: subject, background, sky, nature, buildings, people, water (on-device).
- Canvas: resize image, canvas size with anchor, trim, crop, rotate/flip canvas, resolution, color mode (RGB / grayscale / 1-bit monochrome).

**Filters (85)**
Brightness & Contrast, Tone Curve, Color Balance, Hue/Saturation/Brightness, Level Adjustment, Replace Color, Gradation Map, Posterize, Invert, Grayscale, Black & White, Monocolor, Change Drawing Color, Extract Line Drawing, Find Edges · Gaussian, Zooming, Spin and Motion Blur, Mosaic, Unsharp Mask, Frosted Glass (normal/zooming/moving) · Stroke (both/outer/inner), Stained Glass, Wet Edge, Glow (inner/outer), Bevel (outer), Relief, Relief HQ, Waterdrop, Satin, Drop Shadow, Extrude Parallel, God Rays · Parallel/Concentric/Radial Line Gradation, Radial Line, Speed Line, Clouds, QR Code, Watercolor, Anime Background, Manga Background, Background Removal · Chromatic Aberration (moving/zooming), Glitch, Noise, Retro Game, Oil Paint, Chrome, Bloom, Cross Filter, Sheer (cross/line/square/hex/circle) · Crystallize, Hexagonal/Square/Triangular Pixelate, Pointillize, Dots (hexagonal/square) · Expansion, Fish Lens, Sphere Lens, Wave, Ripple, Twirl, Polar Coordinates, Tile (count/size), Table (count/size), Blur Frame, Rain. Every filter has a live preview, respects the selection and can be undone.

## Build from source

Requires JDK 17 and the Android SDK (platform 37, build-tools 36).

```
./gradlew testDebugUnitTest assembleDebug
```

Every push to `main` runs the tests and builds a signed APK in GitHub Actions (download it from the run's artifacts). Pushing a `v*` tag publishes a GitHub Release with the APK attached. Release signing uses repository secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`); without them the APK is signed with a debug key.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the code is organized.

## Testing status

The app is covered by 1500+ JVM/Robolectric tests, including real Skia rendering of the compositor, brushes, tools and filters, and whole-editor UI smoke tests. It has not yet been tried on a wide range of physical devices; please open an issue if something misbehaves on yours.

## Credits

- Scene segmentation model: Autoseg-EdgeTPU-S from the TensorFlow Model Garden (Apache 2.0), see `app/src/main/assets/models/NOTICE.txt`.
- Subject segmentation: Google ML Kit (downloaded on demand by Google Play services).
- QR codes: ZXing (Apache 2.0).
