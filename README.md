# BendCam

Circuit-bent camera simulator for Android. Bend photos from your gallery, or point the live camera at the world and watch it break in real time.

## Features

- **Gallery bend**: pick any photo (system photo picker, no storage permission), stack effects, tune each one, hold the image to compare with the original, save to `Pictures/BendCam` or share.
- **Live camera**: real-time glitch viewfinder (CameraX), front/back switch, freeze the glitch pattern, full-resolution capture with the same effect chain.
- **Share-to-BendCam**: share an image from any app to open it directly in the editor.
- **16 effects**, all stackable with per-effect intensity:

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
