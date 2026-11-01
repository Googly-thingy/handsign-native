# Hand Sign Detector — NATIVE Android app (not a WebAPK)

This is a **real native APK project** (Kotlin + CameraX + MediaPipe Tasks Vision).
Open the `src/android` folder in Android Studio and compile it. It is **not** a
PWA / Trusted Web Activity / Bubblewrap wrapper, so it is not bound by browser
limits: the front camera keeps running in a **foreground service** while the app is
backgrounded, pinch scrolls the **whole system** via an AccessibilityService, and
palm-then-fist takes **real screenshots** via MediaProjection.

## What it does

- Front camera hand tracking, on-device (MediaPipe `HandLandmarker`, GPU + CPU fallback)
- 🤏 **Pinch to scroll**: pinch thumb+index, move hand up/down.
  In-app it scrolls the demo box; with *Allow scrolling* enabled (Accessibility) it
  swipes whatever app is open, system-wide.
- 🖐️➡️✊ **Palm then fist to screenshot**: hold Open Palm ~0.5s, then Fist within 3s
  (3.5s anti-double-fire cooldown). With *Allow screenshots* granted it captures the
  **screen** (MediaProjection); otherwise it saves the camera frame.
- **True background**: `TrackingService` (foreground service, `camera|mediaProjection`
  type) + partial WakeLock + persistent notification. Survives app-backgrounding;
  restart after reboot by reopening the app.

## Project layout

```
src/android/
  settings.gradle  build.gradle  gradle.properties  gradle/wrapper/…
  app/build.gradle               (deps: CameraX 1.3.4, tasks-vision 0.10.14, lifecycle, activity)
  app/src/main/AndroidManifest.xml
  app/src/main/assets/hand_landmarker.task   (bundled model, ~8MB)
  app/src/main/java/com/handsign/detector/
    MainActivity.kt              (foreground UI + in-app pipeline)
    TrackingService.kt           (background foreground-service pipeline)
    GestureAccessibilityService.kt (system-wide swipe scroll)
    ScreenshotCapture.kt         (MediaProjection shot + MediaStoreSaver)
    HandLandmarkerHelper.kt      (MediaPipe wrapper, GPU→CPU fallback)
    GestureClassifier.kt        (pose heuristics, same as web prototype)
    ActionEngine.kt              (pinch-scroll + palm→fist state machine, unit-tested)
    YuvToRgb.kt                  (camera frame conversion)
  app/src/main/res/…             (layout, strings, theme, accessibility config)
  app/src/test/…/ActionEngineTest.kt
```

## Build it

1. Install **Android Studio Ladybug+** with **JDK 17** and **Android SDK 34**.
2. Open the `src/android` directory (not the repo root).
3. Let Gradle sync. The `downloadModel` task fetches `hand_landmarker.task` into
   `app/src/main/assets/` if it isn't already there (it is bundled, so this is a no-op).
4. Run `./gradlew :app:testDebugUnitTest` — the ActionEngine tests should pass.
5. `Run 'app'` on a physical device (front camera + gestures need real hardware;
   the emulator has no hand to track).
6. Or build an installable APK: `./gradlew :app:assembleDebug` →
   `app/build/outputs/apk/debug/app-debug.apk`, then `adb install` it.

Release: `assembleRelease` (unsigned until you add a signing config) or use
Android Studio's *Generate Signed Bundle / APK*.

## First-run permissions (each needs one tap)

1. **Camera** — system prompt on Start.
2. **Notifications** (Android 13+) — so the background service notification shows.
3. **Allow scrolling** — opens Accessibility Settings; enable *Hand Sign Detector*.
4. **Allow screenshots** — one-time MediaProjection prompt.

## Why native instead of the web version

| Need | Web / PWA / WebAPK | This native app |
|---|---|---|
| Camera while tab hidden / app backgrounded | ❌ browser pauses it | ✅ foreground service |
| Scroll other apps with a gesture | ❌ page only | ✅ AccessibilityService swipes |
| Real device screenshots from a gesture | ❌ captures canvas at best | ✅ MediaProjection |
| Keep CPU awake hours-long | ❌ throttled | ✅ WakeLock + notification |

The browser prototype (`index.html` + `main.pjs` at repo root) is untouched and still
works for quick on-page demos.
