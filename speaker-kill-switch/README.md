# Speaker Kill Switch

Android app that keeps the built-in speaker silent while Bluetooth earbuds keep working. Built for a Galaxy Tab S9 FE (Android 16) with Shizuku.

## What it does

When the switch is ON:

1. **Routing block.** For every audio product strategy it calls the hidden `IAudioService.setDeviceAsNonDefaultForStrategy` with the built-in speaker (and the safe speaker, if present), so the policy engine will not pick the speaker while another output exists.
2. **Volume pin.** The audio policy falls back to the speaker when no other output exists (for example when the Buds drop). So the speaker's `STREAM_MUSIC` index is also set to 0 and kept there. The previous index is saved and restored on OFF.
   - Speaker not the active route: `IAudioService.setDeviceVolume` through Shizuku.
   - Speaker is the active route: `AudioManager.setStreamVolume`. This path also works without Shizuku.
3. **Pause on disconnect (optional).** On `ACTION_AUDIO_BECOMING_NOISY`, or when a Bluetooth output is removed, a media-pause key event is dispatched.

A foreground service re-applies this on volume changes, audio device changes and Shizuku binder events. A Quick Settings tile toggles the switch. If the switch was ON, the service restarts at boot.

## How it works

Hidden APIs are called by reflection on an `IAudioService` wrapped in `ShizukuBinderWrapper`, so calls run as the shell uid. `hiddenapibypass` lifts the reflection restrictions. Every call is wrapped, and failures show up in the Diagnostics panel (Copy diagnostics button).

## Limits

- Only `STREAM_MUSIC` is volume-pinned. Alarm, ring, call and notification streams can still use the speaker.
- Shizuku (wireless debugging mode) must be started again after every reboot. Until then only the volume-only fallback runs.
- This is a software block, not a hardware cut. A Buds disconnect may leak a brief moment of audio before the volume is zeroed.
- Turning OFF without Shizuku running cannot remove the routing block right away; it is removed the next time the app or tile is opened with Shizuku ready.

## Build

```
./gradlew assembleRelease
```

Needs JDK 17+ and the Android SDK (platform 36). The APK is signed with `keystore/debug.keystore` (standard android debug credentials) for both build types, so new builds install over old ones. A prebuilt copy is in `dist/SpeakerKillSwitch.apk`.
