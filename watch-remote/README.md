# Tab Remote

A Bluetooth LE remote: a Wear OS watch app sends signed commands to a tablet app, which executes them.

> **Status: untested on hardware.** Everything here was written and built without a BLE radio, an emulator or real devices. It compiles, lints and the protocol module is unit tested on the JVM, but the BLE, accessibility and Keystore behaviour has never run. Expect to debug on first use.

Modules

- `protocol/` pure Kotlin/JVM: constants, command frames, pairing crypto, advertisement and app-slot encoding (JUnit tests).
- `tablet/` "Tab Remote Receiver": foreground service, BLE peripheral (advertiser + GATT server), accessibility service.
- `wear/` "Tab Remote": BLE central, app, tile, tile. One launcher entry.

## Build

```
./gradlew --no-daemon :protocol:test :tablet:assembleDebug :wear:assembleDebug :tablet:lintDebug :wear:lintDebug
```

APKs are copied to `dist/tab-remote-receiver-0.1.0-debug.apk` (tablet) and `dist/tab-remote-watch-0.3.0-debug.apk` (watch). `local.properties` must contain `sdk.dir=/opt/android-sdk` (or your SDK path).

## Install

- Tablet: copy the receiver APK to the tablet and open it (allow installs from that source).
- Watch (wireless debugging, Settings > Developer options > Wireless debugging):
  ```
  adb pair <ip>:<pairing-port>        # enter the pairing code shown on the watch
  adb connect <ip>:<port>
  adb install -r dist/tab-remote-watch-0.3.0-debug.apk
  ```

## First-time setup on the tablet

Open Tab Remote Receiver and work through the checklist; each unmet item has a button.

1. Bluetooth permissions and notification permission: Grant.
2. Bluetooth on.
3. Accessibility service: needed for Lock, Open app and Close app. Turn on "Tab Remote" in Accessibility settings. On Android 13+ a sideloaded app must first be allowed "restricted settings": App info page for the app, menu (three dots), "Allow restricted settings".
4. Battery optimisation: exempt the app so the service is not killed.
5. Service running: Start. (It restarts on boot once you have started it once.)

Optional: under "Apps to open from the watch" choose up to four apps (slots). The "Test on this tablet" buttons run each command locally so you can verify the tablet side without a watch.

## Pairing

1. Tablet: tap "Pair a watch" (pairing mode lasts 2 minutes).
2. Watch: open Tab Remote, tap Pair. It searches, then both devices show a 6-digit code.
3. Check the codes match. Tap Confirm on the tablet first, then Confirm on the watch. (If you confirm on the watch first it says to confirm on the tablet; just tap Confirm again.)
4. The watch stores the key and then syncs the app slot names automatically. A new pairing replaces the old one (one watch only).

## Usage

- App: buttons for Lock, Play/Pause, Volume +/-, Ring (toggle, auto-stops after 30 s), Close app, one button per configured app slot, Sync apps, Re-pair. The crown/bezel scrolls the list.
- Tile: add the "Tab Remote" tile (Lock, Play, Vol -, Vol +, Ring, Close app). Slot apps are not on the tile.
- Lock is the first button in the app and on the tile (there is no separate launcher entry).

### Commands

| Command | Id | Notes |
|---|---|---|
| PING | 0 | Used to finish pairing |
| LOCK | 1 | Accessibility service |
| PLAY_PAUSE | 2 | Media key |
| VOLUME_UP / VOLUME_DOWN | 3 / 4 | Music stream, shows the volume UI |
| RING | 5 | Toggle |
| KILL_APP | 6 | Goes Home, then kills the foreground app's background process |
| OPEN_APP | 7 | 1 arg byte: slot 0..3 |
| SYNC_APPS | 8 | Lets the watch read slot labels once |
| SWITCH_APP | 9 | 1 arg byte: slot 0..3. Closes the foreground app (if suitable), then opens the slot app |

Result codes: 0 success, 0x05 auth failed, 0x06 unknown command, 0x90 failed, 0x91 accessibility off, 0x92 pairing rejected / not active, 0x93 rate limited, 0x94 nothing to close, 0x95 slot empty, 0x96 could not start app. (These sit in the ATT application-error range but avoid Android's local stack statuses 0x80..0x8F, such as 133; the watch maps those to a connection failure.)

### App slots, Sync apps, Switch

- Choose apps for slots 1 to 4 on the tablet. Then tap "Sync apps" on the watch (it also runs after pairing). The watch then shows one button per non-empty slot. Labels are only readable by the paired watch, after a signed SYNC_APPS.
- The toggle "Close current app first" (above the slot buttons, off by default) makes the slot buttons read "Switch to <app>" and send SWITCH_APP instead of OPEN_APP. Having nothing to close is not an error for SWITCH_APP.
- KILL_APP / SWITCH_APP limitation: the previous app is ended with `killBackgroundProcesses`, which only ends its process if nothing (for example a foreground service) keeps it alive. The app also stays in Recents. The foreground app is never closed if it is this app, the launcher or System UI.

## Watch app updates from Utils (Wi-Fi)

Utils (`android-utils`, `:feature-remote`) bundles this watch APK and can push it to the watch, so adb is needed only once.

One-time setup (adb, wireless debugging on the watch):
```
adb install -r dist/tab-remote-watch-0.3.0-debug.apk
adb shell appops set dev.ilgax.tabremote.wear REQUEST_INSTALL_PACKAGES allow
```
The second command allows Tab Remote to install packages; without it the watch shows this exact command instead of installing.

Flow: open Tab Remote on the watch (it reports its versionCode with HELLO), then in Utils > Watch remote > Watch app tap "Update watch app". Utils starts a one-shot HTTP server (random token path, stops after one full download or 10 min) and offers it over BLE (GET_OFFER, then a long read of the OFFER characteristic, HMAC-authenticated and bound to that frame's nonce). The watch asks Android for a Wi-Fi network, downloads from the offered tablet addresses, checks the SHA-256 from the authenticated offer, and installs with PackageInstaller (same signing key required, so the APK is signed with `keystore/debug.keystore`). If the tap was under 3 minutes ago and Tab Remote is open it starts by itself; otherwise the app shows "Update to vN". The watch must be on the tablet's hotspot or the same Wi-Fi.

New commands: HELLO (10, 3 arg bytes versionCode), GET_OFFER (11), UPDATE_STATE (12, phase + value). Old peers answer 0x06, which is handled. OFFER characteristic `9c4e7a12-3b58-4f6d-8e21-6a0d5b7c9f34` (read + notify; the notification is a content-free ping).

## Macros (0.3.0)

Macros are configured in Utils on the tablet; the watch lists their names (below the app slots, with "Add macros in Utils on your tablet" when there are none) and starts one by id. The section is hidden when the tablet's Utils predates macros.

- RUN_MACRO (13): 3 arg bytes, the macro id big-endian. The tablet answers as soon as the macro is accepted and started (SUCCESS = "started"), not when it ends. New status `MACRO_BUSY` 0x97 (another macro is running; the watch shows "Busy") and `MACRO_NOT_FOUND` 0x98 (unknown id or a macro with no steps). Other failures during the run are reported on the tablet (notification), not to the watch.
- SYNC_MACROS (14): 2 arg bytes, the page index. Grants one authenticated read of the MACROS characteristic `e1c2a7d4-5b39-4a86-bd10-72f3c8a94e55`, exactly like GET_OFFER / OFFER: tag = HMAC-SHA256(key, "mcl" | nonce | body) bound to that frame's nonce.
- Page body: `format(1) | version(4) | total(4) | page(2) | n(1) | n * (id(3) | nameLen(1) | name UTF-8) | hasMore(1)`, tag 32 bytes, at most 480 bytes. The list is unlimited; the watch pages until `hasMore` is 0 (capped at 400 pages). `version` changes with every edit in Utils, so the watch stops after page 0 when it already holds that version. The list is cached on the watch.
- Edits on the tablet ping subscribed watches on the OFFER notification channel (first byte 2 = macros changed; 1 = update offer); an old watch just treats it as an offer check.
- Compatibility: an old watch never sends these; a new watch with an old Utils finds no MACROS characteristic (or gets 0x06) and hides the section. Android may cache the old GATT table of a bonded device until Bluetooth is toggled.

## Security model

- Pairing: ephemeral ECDH P-256 key exchange; both devices derive a 6-digit code from the shared secret and the user compares them on both screens (numeric comparison). The tablet only keeps the new key if the user confirmed on the tablet and a valid PING signed with it arrives during the pairing window.
- Every command is `cmdId | args | tag`, where tag is 16 bytes of HMAC-SHA256 over a fresh 16-byte per-connection nonce plus the command. The nonce is replaced after every attempt, so a captured frame cannot be replayed. Verification is constant time; 5 consecutive failures lock a device out for 30 s.
- The long-term key lives in the Android Keystore on both devices (imported as a non-exportable HMAC key); it is never written to disk or logged. One watch can be paired at a time.
- Not protected: the BLE link itself is not encrypted (commands are authenticated, not confidential), and the advertisement exposes a random tablet id. The tile activity on the watch is exported so the tile host can start it; it only accepts a fixed set of commands.

## Troubleshooting

- "Tablet not found": on the tablet check the service is running (and Bluetooth is on), exempt the app from battery optimisation, and make sure the watch is close. After a reboot, open the app once or enable background start.
- Lock / Open app / Close app fail with "Enable accessibility on tablet": turn on the accessibility service; if the switch is greyed out allow restricted settings (see setup). If Android turns the service off after an update, re-enable it.
- Close app says "Nothing to close": the foreground app was this app, the launcher or System UI.
- Pairing never finds the tablet: start pairing mode on the tablet first (2 minute window) and keep it in the foreground. Codes not matching: cancel on both sides, someone else may be pairing nearby.
- Watch says "Not authorised, re-pair": the tablet's key was removed or replaced; pair again.
