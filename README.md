# BendCam

Circuit-bent camera simulator for Android. Bend photos and videos from your gallery, or point the live camera at the world and watch it break in real time, then record it.

## Features

- **Gallery bend**: pick any photo or video (system photo picker, no storage permission), stack effects, tune each one, hold the image to compare with the original, save to `Pictures/BendCam` or share.
- **Video bend**: scrub through a clip to preview the effect chain, then export the whole video (up to 720p, 30 fps, original audio kept) to `Movies/BendCam`.
- **Live camera**: real-time glitch viewfinder (CameraX), front/back switch, freeze the glitch pattern, full-resolution photo capture, and **video recording** of the bent feed with microphone audio.
- **Share-to-BendCam**: share an image or video from any app to open it directly in the editor.
- **26 effects**, all stackable with per-effect intensity. Temporal effects (Ghost Trails, Slit Scan, Hue Cycle) remember previous frames in live view and video:

| Effect | What it simulates |
| --- | --- |
| RGB Split | misaligned color channels |
| Scan Tear | horizontal sync loss bands |
| Pixel Sort | brightness-sorted pixel runs |
| Datamosh | broken macroblock motion vectors |
| JPEG Rot | real byte corruption of the JPEG stream |
| Row Smear | stuck readout rows / columns |
| Wave Bend | sinusoidal line displacement |
| Interlace | offset, dimmed odd fields |
| VHS | chroma bleed, noise, tracking line |
| Dead Sensor | hot pixels, dead lines, color noise |
| Bit Crush | reduced bit depth with XOR artifacts |
| Channel Swap | rewired color channels |
| Solarize | partial tone inversion |
| Thermal | false-color heat map |
| Mirror | horizontal / vertical / quad mirror |
| Pixelate | blocky low-res readout |
| 1-Bit Dither | ordered Bayer dither down to a few levels |
| Edge Neon | Sobel edges glowing in their own color |
| Swirl | twisted center vortex |
| Fisheye | barrel lens bulge |
| Prism | radial chromatic aberration |
| Hue Cycle | hue rotation, animated over time |
| Kaleido | polar-folded kaleidoscope |
| Block Invert | macroblocks with inverted or dropped channels |
| Ghost Trails | phosphor-like feedback trails (temporal) |
| Slit Scan | each row from an older frame (temporal) |

The dice button builds a random chain; the refresh button re-rolls the random glitch pattern.

## Download

Grab the latest APK from [Releases](../../releases).

## Build

```bash
./gradlew assembleRelease
```

Requires JDK 17+ and the Android SDK (compileSdk 36). Min Android 7.0 (API 24).

## Releases (CI)

`.github/workflows/release.yml` builds on every push and PR (APK uploaded as a workflow artifact).
Pushing a tag `vX.Y.Z` publishes a GitHub Release with `BendCam-X.Y.Z.apk` attached:

```bash
git tag v1.0.1 && git push origin v1.0.1
```

Release signing uses these repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them the APK is debug-signed. Locally, put the same values in a git-ignored `keystore.properties`
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`).
