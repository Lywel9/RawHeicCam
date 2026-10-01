# RawHeicCam

A Camera-Assistant-style companion app for Samsung phones: it keeps a warm
camera session (foreground service, auto-restarts on boot), captures **RAW
(DNG) + JPEG simultaneously**, and publishes a compact **HEIC with the HDR
gain map (ISO 21496-1) attached** — RAW-style highlight latitude at a
fraction of the DNG's file size.

## Pipeline

1. `CameraAttachService` (foreground, `camera` type) starts on boot via
   `BootReceiver` and keeps a bound `ImageCapture` session warm.
2. `CameraController` uses CameraX 1.5.1's `OUTPUT_FORMAT_RAW_JPEG` to
   capture DNG + JPEG in one shot (falls back to RAW-only, then JPEG-only
   based on what the HAL reports via `ImageCaptureCapabilities`).
3. `HeicTranscoder` parses the uncompressed Bayer DNG (minimal TIFF reader
   with SubIFD + CFA + black/white level support), recovers highlight
   headroom into an HDR rendition, computes a log2 gain map, and encodes
   HEIC via the platform encoder (`Bitmap.compress` + `Bitmap.gainmap`),
   which writes the gain map per ISO 21496-1 — the same container Samsung
   uses.
4. `PhotoSaver` publishes the file to `Pictures/RawHeicCam` via MediaStore.

Degrades gracefully: HDR-HEIC → SDR-HEIC → JPEG. The shot is never lost.
Optional "keep DNG" toggle stores the original RAW alongside.

## Build

```bash
# requires JDK 17 + Android SDK (compileSdk 35)
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Grant camera + notification permissions on first launch, tap **Attach**,
then **Capture RAW → HEIC now**. On boot, the service restarts itself when
attach-on-boot is enabled.

## Honest caveats

- No Android app can silently inject frames into Samsung's own camera app.
  "Attach" here means: this app holds its own always-on CameraX session in a
  foreground service — the closest thing Android allows to a Camera
  Assistant-style companion.
- Some Samsung devices (and all JPEG-compressed DNG HALs) report
  `OUTPUT_FORMAT_RAW_*` differently or not at all; the app probes and shows
  what your camera actually supports.
- The gain map is computed from a single-exposure DNG, so it recovers
  clipped highlights only — it is not multi-frame HDR.
- Sustained always-on camera sessions may be throttled by aggressive OEM
  battery managers (disable battery optimization for reliable attach).
