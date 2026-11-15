# Utils

One Android app (`dev.ilgax.utils`, Galaxy Tab S9 FE class tablets, Android 14+) that bundles small system tools as independent modules, with a Material 3 Compose UI that adapts from phone to 12-inch tablet. Everything privileged goes through [Shizuku](https://shizuku.rikka.app/).

It installs next to the older apps, but **turn the old Speaker Kill Switch OFF and uninstall it first**: two apps pinning the speaker would fight. Likewise uninstall the standalone **Tab Remote Receiver** and re-pair the watch (keys are per app).

## Modules

| Module | What it does | Limits |
|---|---|---|
| `:feature-speaker` Speaker kill switch | Blocks the built-in speaker for every audio product strategy (`setDeviceAsNonDefaultForStrategy`), pins the speaker `STREAM_MUSIC` volume to 0 (saved index restored on OFF), optional pause on Bluetooth disconnect. Foreground service re-applies on volume/device/Shizuku events; restarts at boot; Quick Settings tile; shortcut. | Only `STREAM_MUSIC` is pinned (alarm/ring/call can still use the speaker). Software block: a Buds disconnect may leak a moment of audio. Shizuku must be restarted after every reboot; until then only the volume-only fallback runs. Turning OFF without Shizuku cannot remove the routing block until Shizuku is ready again. |
| `:feature-hotspot` Wi-Fi hotspot | Starts/stops Wi-Fi tethering via the hidden `TetheringManager` in a Shizuku UserService (shell uid); shows name, security, band, devices, shared Wi-Fi, masked password, open-network warning; Quick Settings tile; shortcut. | Needs Shizuku. Only works on channels the regulatory config allows (confirmed 2.4 GHz on a Tab S9 FE+). Shizuku must be restarted after every reboot. |
| `:feature-math` Equation solver | Circle a handwritten equation with the S Pen: "Solve" (launcher alias, so it appears in Air Command; also a QS tile and a shortcut) screenshots the screen after a 300 ms delay (the shared "Utils" accessibility service `takeScreenshot` when enabled, else a Shizuku `screencap` daemon UserService with raw output), shows it frozen only while you circle, then shows the result as a floating, draggable, minimisable card over the live app (accessibility overlay when "Utils" is on, otherwise "Display over other apps", granted silently through Shizuku when possible), runs an on-device ONNX image-to-LaTeX model (bundled Pix2Text-MFR 1.5 int8; swappable through a `model.json` manifest and zip import), normalises the LaTeX and solves it with a pure-Kotlin exact solver (linear, quadratic with surds, rational equations, chained equalities, 2x2 systems, simplification) with steps and a KaTeX preview. Edits re-solve live; corrections can be logged locally for fine-tuning and exported as a zip. No INTERNET permission. | `\times` is read as the variable x; digit.digit is multiply or decimal (setting). Handwriting recognition is only as good as the model (4 of 10 user samples exact). Needs Shizuku or the accessibility service enabled. |
| `:feature-remote` Watch remote | Tablet receiver for the Wear OS "Tab Remote" watch app: BLE GATT server, signed commands (lock, media, volume, ring, close/open/switch app). Setup checklist, pairing sheet, four app slots, local test buttons. **Macros** (0.3.0): multi-step operations edited on the tablet (Watch remote, Macros: name, stop-on-failure, reorderable steps with per-step parameters, requirement chips, Run, duplicate, delete, last 20 runs) and started from the watch (RUN_MACRO; answers "started" at once, one macro at a time, `MACRO_BUSY` otherwise; a failed watch run posts a notification naming the step). Steps come from per-module action providers registered in the module init (`:core` action registry): watch remote (lock, play/pause, volume, ring, close app, open/switch slot), flow (wait 0.5-30 s, 60 s per macro), media and sound (next/previous, volume %, mute, ringer, Do Not Disturb), system via accessibility (home, back, recents, notifications, quick settings, screenshot), screen (brightness %, auto-brightness, rotation lock, timeout), Wi-Fi on/off (Shizuku), hotspot and speaker kill switch (their modules), open app, open link, force-stop app (Shizuku). Skipped: Bluetooth on/off (would cut the remote link), flashlight (the Tab S9 FE+ has no flash). Brightness % follows the stock slider curve over the documented 1..255 setting range (assumption, see unverified list). Bundles the watch APK (built from `../watch-remote/wear` by `:feature-remote:bundleWatchApk`, signed with `watch-remote/keystore/debug.keystore`) and updates the watch over Wi-Fi: "Watch app" section, one-shot HTTP server (the only INTERNET use, in `:feature-remote`), authenticated offer over BLE. One-time on the watch: `adb install -r watch-remote/dist/tab-remote-watch-0.3.0-debug.apk` and `adb shell appops set dev.ilgax.tabremote.wear REQUEST_INSTALL_PACKAGES allow`. | Needs the shared "Utils" accessibility service (lock / open / close). Closing apps force-stops them via Shizuku when it is ready; otherwise it presses Home and `killBackgroundProcesses` (process may survive, app stays in Recents). |

## Install and first run

1. Install `dist/utils-0.3.0-debug.apk` (arm64 only) (signed with the shared debug key, so later builds update in place).
2. Start Shizuku (wireless debugging) and open Utils; use the banner to grant Shizuku permission. Allow notifications when asked.
3. Quick Settings: edit tiles and add **Speaker kill**, **Hotspot** and **Solve**. Long-press the launcher icon for the **Speaker switch** and **Hotspot** shortcuts.
4. Watch remote: open the module and work through Setup (Bluetooth permissions, notifications, Bluetooth on, accessibility, battery exemption). For a sideloaded app, allow restricted settings first: App info, three-dot menu. Then "Pair a watch", compare the 6-digit code with the watch, confirm on the tablet, then on the watch.

5. Equation solver: open the module and finish Setup (enable the accessibility service "Utils", or Shizuku plus "Display over other apps"). **Upgrading from 0.2.0:** the two old accessibility services ("Watch remote" and "Utils – screen capture for Solve") are replaced by one shared service named "Utils"; turn it on once in Settings → Accessibility (sideloaded: App info → three-dot menu → "Allow restricted settings" first, Android 13+). Then add it to the S Pen menu: Air command → Settings → Shortcuts → add **Solve**. Try the solver with no model in the "Try it" field.

## Adding a module

1. New Android library `:feature-foo` depending on `:core` only; implement `UtilModule` (card state, `toggle()`, `Detail`).
2. Declare its tile/service/activities in its own manifest.
3. `include(":feature-foo")` in `settings.gradle.kts`, `implementation(project(":feature-foo"))` in `:app`.
4. Add one line to `ModuleRegistry.of(...)` in `UtilsApplication`. Home card, detail pane and dynamic shortcut follow automatically.

## Build

```
./gradlew --no-daemon :protocol:test testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew --no-daemon :app:recordRoborazziDebug     # rewrites screenshots/*.png
```
JDK 17+, Android SDK (platform 36), `local.properties` with `sdk.dir`. `:protocol` is the pure-JVM wire protocol from `../watch-remote/protocol`, included in place and not copied (its build output goes to `build/protocol`). `scripts/render-katex-fixtures.sh` regenerates the KaTeX PNGs used by the math screenshots (headless Chrome; run after `:feature-math:testDebugUnitTest`). Robolectric downloads its Android jar at first test run; `-Probolectric.repo=<maven mirror>` overrides the source on restricted networks.

## Verification status

Device-confirmed logic carried over (by the owner, on other builds):
- Speaker: the whole controller (hidden `IAudioService` calls through `ShizukuBinderWrapper`, ordering, fallbacks, diagnostics) on Tab S9 FE, Android 16.
- Hotspot: context bootstrap, `TetheringManager` construction, `TetheringRequest`, `startTethering` callback, status reads, on Tab S9 FE+ (SDK 36), run through `app_process`.

Not confirmed on any device (equation solver): the Shizuku `screencap` daemon UserService and raw output parse, the accessibility `takeScreenshot` path (hardware bitmap), the floating card window (both overlay types, drag, keyboard focus switching, minimise, Re-circle starting the activity from the overlay), the silent `appops` grant, the shared accessibility service dispatch (including the remote's foreground tracking), whether the "Solve" alias shows in Air Command, the 300 ms capture delay, on-device latency (desktop: ~0.2 s encoder, 0.05-0.1 s decoder for short equations), the WebView KaTeX card, the lasso with a real S Pen, and the transparent no-flicker launch. The solver, normaliser, tokenizer, preprocessing and the real model on three samples are covered by JVM tests.

Not confirmed on any device:
- Hotspot `stop`, the shell-bound `WifiManager` upstream read, the client count (same as the CLI).
- The Shizuku UserService plumbing (bind, AIDL parcelable, destroy transaction, running the engine on its own looper thread, process lifetime), tiles, shortcuts, notification/permission flow.
- The whole Compose UI on a device (screenshots are Robolectric renders with a fixed palette; dynamic color, haptics, predictive back, split-screen and rotation are untested).
- Watch remote: the BLE/GATT/pairing/command code is the original and has never run on hardware; the Shizuku force-stop (`IActivityManager.forceStopPackage`) and the dependency-injected command path are new and untested.

- Macros (0.3.0), not confirmed on any device: every step's real effect (global actions, `settings put` / `svc wifi` / `cmd notification set_dnd` through the new Shizuku shell UserService, ringer and Do Not Disturb with policy access, brightness curve on One UI, activity starts from the accessibility service), the macro GATT paging and ping over a real link, the failure notification, and the editor on a tablet. Macro storage, runner, protocol paging, registry and requirement checks are covered by JVM tests.
