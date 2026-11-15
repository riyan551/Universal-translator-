# Universal Translator

Android screen translator prototype using Kotlin, Jetpack Compose, MediaProjection, ML Kit OCR, ML Kit Language Identification, and ML Kit on-device Translation.

## Features
- User-approved screen capture through Android MediaProjection.
- OCR from visible screen frames using Latin, Japanese, Korean, Chinese, and Devanagari ML Kit recognizers.
- Multiple script recognizers run on each sampled frame; this improves script coverage but increases CPU use and APK size.
- Language identification.
- Offline translation using downloadable ML Kit language models.
- Online translation using a configurable LibreTranslate-compatible endpoint.
- Hybrid mode: online first, offline fallback.
- Position-aware translation labels placed at the detected text-block coordinates.
- Persistent SQLite translation cache keyed by normalized text, source language, target language, and engine mode.
- Comic-oriented local background patch: samples border pixels around each OCR text box and places a rounded color patch with translated text at that position.
- Game mode with a shorter OCR polling interval (about 900 ms; actual latency depends on OCR, network, and device load).
- User controls for overlay text size and background opacity.

## Requirements
- JDK 17
- Android SDK 36
- Gradle / Android Gradle Plugin 8.13.2
- Android 8.0+ (minSdk 26); intended for Android 11–16.

## Build
```bash
gradle --no-daemon assembleRelease
```
APK output:
`app/build/outputs/apk/release/app-release-unsigned.apk`

For signed distribution, configure signing in Codemagic or Android Studio. The provided Codemagic workflow builds an unsigned release APK; configure Android code signing in Codemagic before publishing.

## Use
1. Install the APK.
2. Open Universal Translator.
3. Grant "Display over other apps".
4. Choose target language and engine.
5. Set a LibreTranslate-compatible endpoint for online/hybrid mode.
6. Tap Start screen translator and approve Android screen capture.
7. Switch to a game or comic app. The translation panel appears when OCR detects new text.
8. Stop using the persistent Android notification or app Stop button.

## Important limitations
- Android requires explicit screen-capture consent. Secure/DRM-protected windows may be blank or unavailable to OCR.
- The current OCR dependency recognizes Latin script. Japanese, Chinese, Korean and other writing systems still need their corresponding ML Kit script recognizer dependencies and selection logic.
- This is a functional starter project, not yet a polished production release. Translated labels are placed near the bounding boxes detected by OCR. The original pixels are not erased or inpainted, so the source text may remain visible underneath or beside the translation. This build has not yet been device-tested for overlay/capture feedback on every Android vendor.
- LibreTranslate availability, rate limits, endpoint policy, and API keys depend on the server you configure. Do not send private screen content to an untrusted server.
- ML Kit translation models are downloaded on demand; first use of a language pair requires internet. Once downloaded, translation can run offline.
- For long sessions, review battery use and device vendor background restrictions. Game mode samples more frequently and may increase battery and heat.


## Comic cleanup / inpainting scope
The current comic cleanup is a lightweight local patch-fill, not neural inpainting. It estimates the text-box background from surrounding border pixels and places a rounded patch behind the translation. It can work well for plain white speech balloons and flat-color backgrounds, but may look imperfect on gradients, textured art, patterned backgrounds, or text overlapping balloon outlines. It does not reconstruct complex artwork or guarantee perfect preservation of every balloon contour. Full-quality manga inpainting would require a dedicated inpainting model or OpenCV-based processing and significantly more compute/memory.

## OCR scripts
Bundled ML Kit recognizers are included for Latin, Japanese, Korean, Chinese, and Devanagari scripts. All are invoked for sampled frames to avoid assuming the language in advance. This increases APK size, startup work, CPU use, and battery consumption. Game mode therefore samples at approximately 900 ms rather than attempting an unrealistic 420 ms cadence with five recognizers.


## v1.3 comic cleanup improvements
- Adds a screen-space cleanup patch for each OCR rectangle. The patch uses edge interpolation from pixels around the detected text bounds and is composited underneath the translated text.
- This is a lightweight classical approximation for simple/flat speech balloons. It is not neural inpainting and cannot reliably reconstruct textured artwork, halftones, complex gradients, or lines crossing the text.
- Overlay patch opacity is configurable in the app settings.
- A frame signature check avoids clearing and retranslating unchanged text/coordinates on every OCR sample. The SQLite cache remains persistent across app sessions.
- OCR engines cover Latin, Japanese, Korean, Chinese, and Devanagari scripts. Multiple recognizers are invoked per sampled frame, so performance varies with device load.

## Neural inpainting and balloon detection roadmap
A true neural inpainting model is not bundled in this source package. It requires a compatible quantized TensorFlow Lite model file, model license review, memory/performance profiling, and an inference pipeline. The current cleanup is a safe fallback for plain speech balloons rather than a claim of neural-quality reconstruction. Accurate balloon segmentation (including tails and irregular outlines) also needs a dedicated segmentation model or a more advanced image-processing pipeline.


## v1.4.0 development notes

- OCR engines now run sequentially and stop after the first engine returns usable text, rather than running all five engines simultaneously. This should reduce peak CPU use on common Latin-language screens; CJK/Devanagari fallback can take longer when earlier engines return no text.
- Translation cache is capped at the newest 5,000 entries.
- Online-only mode now reports when an endpoint is missing instead of silently switching to offline translation.
- Overlay geometry uses the captured screen coordinates directly and clamps the translation label to the visible screen width.

The OCR fallback order is Latin, Japanese, Korean, Chinese, then Devanagari. This is a practical first-pass strategy, not perfect script classification: OCR may occasionally return false-positive text before a later script engine is tried. The cleanup patch remains a simple edge-interpolation approximation and can look poor on textured or detailed backgrounds.
