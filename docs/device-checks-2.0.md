# Brushwork 2.0 device checks

These checks try Brushwork 2.0 on a real phone. Every number below was estimated on a computer, so
only a phone can confirm it. Nothing here can harm your artworks. A check that fails does not stop
the release: it is fixed in a 2.0.x update. Please send back what the last section asks for.

All steps use fingers only. Each check says what to do ("Steps") and what should happen ("Pass").
Answer each check with "pass" or "fail", and add a sentence when it fails.

## Before you start

1. Install 2.0 over 1.9. Do not uninstall 1.9 first.
2. Keep at least 3 GB of free storage. Full layer storage keeps layers in the app's private space,
   and below 2 GB free Brushwork uses less of it. Each import also needs about twice the file's
   size free while it runs.
3. Open the gallery once and wait 10 seconds before the first check (the automatic check runs
   then; see Q1).

## The check artworks

Four Brushwork PDF files are attached to the 2.0 release on GitHub (under **Assets**). Every
painted layer holds random strokes that cover about 35 % of the canvas.

| File | What it holds | Size |
|---|---|---|
| `check-200.pdf` | 200 painted layers at 1080 × 2408 | about 55 MB |
| `check-500.pdf` | 500 painted layers at 1080 × 2408 | about 140 MB |
| `check-100-blend.pdf` | 100 painted layers at 1080 × 2408; layers 51 to 100 alternate Multiply and Screen | about 28 MB |
| `check-4000x5000-50.pdf` | 50 painted layers at 4000 × 5000 | about 38 MB |

**Import one.**
1. Download the file on the phone. It goes to Downloads.
2. Check Q1 first: Settings > Layer storage must read "Automatic: Full", or choose "Full:
   unlimited layers". At Classic, Brushwork cannot hold that many layers, as in 1.9.
3. In the gallery, tap the file button next to "Import picture" ("New from SVG or PDF"; in an empty
   gallery it is a button with that name). Pick the file.
4. Brushwork makes a new artwork exactly the file's size and brings back every layer, with its
   name ("Layer 1" to "Layer 200" and so on) and blend mode. A big file takes a while.
5. When the import ends, go back to the gallery (this saves the artwork). The gallery names the
   artwork after the file.

Import each file once. The checks then open the artwork from the gallery.

## How to read the numbers

Brushwork keeps the times of its last 50 layer switches, opens, saves, stroke frames and so on in
memory. To read them, open an artwork, tap the ⋯ button ("More options") in the top bar, then
**Settings** at the end of the menu, then **Layer storage**:
- **Copy details** copies everything as text. Paste it into your message with the check's number.
- **Details** shows the same text on screen.

The timing lines read "name: p50 / p95 (count)". The p95 is the slow end: 95 of 100 values are at
most that. The names are:
- `SWITCH`: tapping another layer
- `OPEN`: opening an artwork
- `SAVE_LAYER`: saving one changed layer
- `COMPOSITE`: drawing the canvas for one stroke frame
- `INPUT_TO_COMPOSITE`: from your finger to the canvas
- `FRAME_STROKE`, `FRAME_PANEL`, `FRAME_OTHER`: screen frames while painting, while scrolling the
  layers panel, and otherwise (2.0 may count the scrolling frames as `FRAME_OTHER`; see Q2)
- `LEDGER_PEAK`: the most memory the layers took

The numbers are lost when the app closes, and a new start begins with none. So before each
check, swipe Brushwork away from the recent apps and start it again. Copy the details right after
the check, before you start the next one.

## Engine checks

| # | Check | Steps | Pass |
|---|---|---|---|
| Q1 | The automatic check: result and time | Install 2.0 over 1.9. Open the gallery and wait 10 s. Open an artwork, then Settings > Layer storage. Copy details. Then swipe the app away within 1 s of the next gallery start, and start it again. | The subtitle reads "Automatic: Full" ("Automatic: Full, without storage swap" is accepted when free storage is under 2 GB). The "Ran:" line shows at most **3 s** (3000 ms). The "Running now:" line shows R between **800 and 1,400 MiB**. No notice shows in the gallery. The swiped check runs again by itself at the next start, and the subtitle stays "Automatic: Full". |
| Q2 | 500 layers at phone size | Open `check-500`. Scroll the layers panel from top to bottom and back. Copy details at once, before you add a layer. Add one layer. Paint one stroke. Save (go back to the gallery). Open the artwork again and copy details. | `OPEN` at most **15 s**; while it opens, the line "Opening layer n of m" under the spinner counts up. In the first copy, the scrolling frames have a p95 of at most **17 ms**: read `FRAME_PANEL`, or `FRAME_OTHER` when there is no `FRAME_PANEL` line (a missing `FRAME_PANEL` line is not a fail). Every thumbnail placeholder is filled within 2 s after the scrolling stops. Layer 501 is added with no refusal. In the second copy, `SAVE_LAYER` at most **300 ms**. |
| Q3 | 200 layers at phone size | Open `check-200`. Paint one stroke on each of 10 layers spread through the stack. Add 10 layers. | No refusal. The layers panel count reads "210". "Preparing the layer" never shows. |
| Q4 | Open and save | Close `check-200` and open it again. Paint one stroke and save. Then paint for 2 minutes, through at least one autosave. | The reopen takes at most **6 s** (`OPEN`), with "Opening layer n of m" under the spinner. `SAVE_LAYER` at most **300 ms**. `FRAME_STROKE` p95 at most **20 ms** while the autosave runs. |
| Q5 | Layer switch | In `check-200`, tap 20 different layers. Copy details. Swipe the app away and start it again. In `check-4000x5000-50`, tap 10 layers you have not touched since the open. Copy details. | At phone size `SWITCH` p95 at most **60 ms**, and "Preparing the layer" never shows. At 4000 × 5000 `SWITCH` p95 at most **600 ms**; the small ring with "Preparing the layer" may show after a quarter of a second. |
| Q6 | 4000 × 5000 with 50 layers | Open `check-4000x5000-50`. Paint on 5 layers, merge two of them, undo 20 steps, redo 5, save, close, and open it again. | `OPEN` at most **15 s**. `SAVE_LAYER` at most **1.5 s**. Every stroke is there after the reopen. Brushwork does not close. `LEDGER_PEAK` at most the R of the "Running now:" line. |
| Q7 | Stroke frame with 200 layers | In `check-200`, paint for 10 s on layer 100. | `COMPOSITE` p95 at most **14 ms**. `FRAME_STROKE` p95 at most **17 ms**. |
| Q8 | Stroke latency with 100 layers | In `check-100-blend`, paint for 10 s on layer 50. Copy details. Swipe the app away and start it again. Paint for 10 s on a new canvas with one layer. Copy details. | `COMPOSITE` p95 at most **15 ms**. `INPUT_TO_COMPOSITE` p95 at most **32 ms**, and at most **8 ms** more than on the one-layer canvas. This check may miss: the computer's estimate for this composite is about 16 to 24 ms on the phone, against the 15 ms target. Please send the numbers either way. |
| Q9 | One side of a mirror | On a vector layer, draw a curve with an 8-way mirror. In Transform, tap it, then drag one side for 5 s. Then pick all sides and move them. | The screen frames (`FRAME_STROKE` and `FRAME_OTHER`) have a p95 of at most **33 ms**. The other 7 sides stay put while you drag. Moving all sides moves all 8. |
| Q10 | Color preview | Open a shape drawn with a brush outline and 8 mirror copies. Tap the pill's color button and drag the hue for 5 s. Do the same in "Change Drawing Color" on that layer. | The screen frames (`FRAME_STROKE` and `FRAME_OTHER`) have a p95 of at most **33 ms** in both. The canvas follows your finger. Cancel puts the colors back. |
| Q11 | Classic | In Settings > Layer storage choose "Classic: as in version 1.9". Open `check-200`. Then make a new 1080 × 2408 canvas and add painted layers until Brushwork refuses. Then choose "Automatic (recommended)" again. | `check-200` is refused with "This artwork has more layers than Classic layer storage can open." and offers "Open with Full storage", which opens it. The refusal reads "Layer limit reached (N) for this canvas size" with N from **70 to 95** (estimate 84). |
| Q12 | Largest canvas | New canvas: read "Largest on this phone: about W × H px" under the size fields. Make a canvas of that size and add 50 painted layers. Then try a canvas 10 % larger on each side. | The largest size is between **5,500 × 5,500 and 6,800 × 6,800** (estimate "about 6000 × 6000"). The 50 layers are added with no refusal at Full. The larger canvas is refused with "This canvas is too big for this phone's memory. The largest is about W × H." |
| Q13 | Memory safety | In `check-500`, paint for 30 min. Switch 5 times to the camera and to a browser in between. | No "Brushwork was closed by the phone because memory ran out" notice, no "Brushwork closed unexpectedly" card, no lost stroke. If one appears, send "Copy details" and the crash report. |

## Feel checks

There is no number to read here. Answer yes or no, with a sentence when no.

| # | Steps | Pass |
|---|---|---|
| FC1 | Mirror a shape 8 ways. In Transform tap one side, wait 3 s, tap the same spot again, then once more. Tap one side, drag it, and tap it again. Turn one side with two fingers. Try to resize the picked side, then tap again for all sides and resize them. Then make a Perspective array and tap one copy. | One side, then all sides (there is no time limit), then the object underneath. After the drag, the second tap picks all sides, and one Undo takes back the side's move. The two-finger turn never resizes the side, and the side still follows later edits of the object. The picked side shows no scale handles and no Scale row ("Resize all sides together: tap again for all sides"); with all sides picked, resizing works as in 1.9. The side's box appears at once. The Perspective copy moves on its own. |
| FC2 | Open a shape and tap the pill's color button. Change the fill and the stroke, tap ✓, then Undo. Do the same in the text sheet on a text. Then open the color button again, swipe the sheet down, and tap ✓ on the pill. | The color button is the first button of the pill's second row, under the move button. The canvas follows your finger. After ✓, one Undo takes back both colors, and your drawing color has not changed. After the swipe the sheet shrinks and the preview stays; ✓ keeps the previewed color as one Undo step and closes the sheet. |
| FC3 | Select objects, tap "Behind" in the selection bar, drag the array's handles, and let go. Set the look to "Recolored" and move "Recolor strength". Then Undo. | The copies show in front while you drag and behind after; the hint "Copies show in front while you adjust and go behind when you let go." shows the first time. Recolored copies take the color. One Undo removes the new layer and its copies. |
| FC4 | On a vector layer with about 200 objects, open "Change Drawing Color", move the color, and tap Apply. Then tap "Edit" on one object. | The preview follows within about a quarter of a second, and Apply ends within about 1.5 s. The object opens in its tool: it was not turned into pixels. |
| FC5 | Select about 500 points of a path and drag them near a guide. Then select a few sharp and a few smooth points and tap the corner button on the pill. Select a Polyline point and long-press the same place. | The group snaps to the guide, shows the guide line and does not lag. The mixed points all become sharp, and one Undo turns them back. On the Polyline the corner button is greyed in the same place, and its name reads "Polyline points are always sharp". |
| FC6 | Select a shape, tap "Edit", change it, and tap ✓. Select a path with a gradient fill, tap the More button of the object bar, then "Turn into shape". Then Undo. | Edit opens the Shape tool, and you stay in it after ✓. Turn into shape opens the Shape tool with a message that names the gradient change ("Gradient fill became its first color"). The message shows for about 10 seconds, or until you close it, and its "Undo" brings the path back, as one Undo does. |
| FC7 | Set the phone's largest font size. Open a shape with points selected, then open the object bar. | The pill's color and corner buttons can be seen, and nothing overlaps. The object bar's labels can be read. |
| FC8 | Optional, with a second phone that has 1.9. On the 2.0 phone, make a new artwork with 3 layers and keep the imported `check-200`. In an artwork's Settings, under Backup, tap "Choose backup folder", pick a folder such as Documents/Brushwork, then tap "Back up now". Copy that folder to the 1.9 phone (with a computer, for example). On the 1.9 phone, in an artwork's Settings under Backup, tap "Restore artworks…", pick the copied folder and restore both artworks (if 1.9 already uses a backup folder, tap "Change folder", pick the copied folder, then "Restore them"). Open both in 1.9. | The 3-layer artwork opens. `check-200` shows "This artwork was saved by a newer version of Brushwork", and 1.9 does not close. |

## What to send back

- For each check: its number, "pass" or "fail", and a sentence when it fails.
- For each engine check: the text of Settings > Layer storage > **Copy details**, copied right
  after the check.
- Any crash report. After an unexpected close, the gallery shows "Brushwork closed unexpectedly"
  with "Copy" and "Share". Older reports are under "Diagnostics" at the end of an artwork's
  Settings ("Crash reports (n)", then "Share report …" or "Share all").
- If Brushwork ever shows "Brushwork was closed by the phone because memory ran out", say which
  check you were doing, and send Copy details.
