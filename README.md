# Crate

A music player for Android that puts your albums in a 3D rack of CD jewel cases. Scrub through the rack and each album starts its preview instantly.

## Features

- **3D jewel-case rack** drawn on a plain `Canvas`. Each case is a box projected with a weak-perspective camera, and the selected album slides out toward you.
- **Instant previews.** When the rack settles on an album, its 30-second preview from the iTunes catalogue starts playing. Songs cross-fade, and the albums next to the current one are fetched ahead of time.
- **Waveform scrubber** with a fixed needle. Drag it to flip through the rack; it ticks with a light vibration at each album.
- **Pick for me** spins the rack and lands on a random album.
- **Blurred backdrop** taken from the current cover, cross-fading as you move.
- **Search.** Finds albums already in the rack, or looks them up on iTunes and adds them.
- **Saved albums** and **sorting** (newest, oldest, artist, saved first).

No third-party libraries are used, only the Android framework.

## Build

Requirements: JDK 17 or newer, and the Android SDK with platform 36.

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The app needs internet access. Covers and previews are downloaded on first use and cached.

## Changing the albums

The starting collection lives in `app/src/main/assets/catalog.json`. To change it, edit the `ALBUMS` list in `tools/resolve_catalog.py`, then run:

```bash
python tools/resolve_catalog.py
```

## Project layout

| File | What it does |
| --- | --- |
| `CrateView.kt` | The 3D rack: projection, drawing, drag and fling, tap to select |
| `WaveformView.kt` | The scrubber under the rack |
| `MainActivity.kt` | Screen layout, backdrop, Pick for me, search, sorting |
| `PreviewPlayer.kt` | Preview playback with fades |
| `Catalog.kt` | Album data, cover downloads, the jewel-case insert artwork, iTunes search |

## Note

This is a personal, non-commercial project. Album art and audio previews belong to their owners. They are streamed from Apple's public iTunes Search API and are not included in this repository.
