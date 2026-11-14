# Portal Login (Wear OS)

A minimal on-watch browser for captive-portal Wi-Fi (hotel logins, SMS-code portals) on Wear OS. It requests the Wi-Fi network itself (so Wi-Fi comes up even while the watch is connected to the phone over Bluetooth), binds the app's traffic to it, loads the portal page in a WebView so you can type with the watch keyboard, and detects when login succeeded ("Online ✓").

It is **not** a general browser, and it does not bypass portals or authenticate for you.

**Status: untested on real hardware as of writing.** It builds and the pure-logic unit tests pass, but it has never been run on a watch or emulator.

## Build

```
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk` (a prebuilt copy is in `dist/`).

## Install on the watch (wireless debugging)

1. Enable Developer options: Settings → About watch → Software information → tap Software version 5 times.
2. Developer options → turn on ADB debugging and Wireless debugging → Pair new device.
3. On your computer:
   ```
   adb pair IP:PORT        # pairing address/code shown on the watch
   adb connect IP:PORT     # address shown on the main Wireless debugging screen
   adb install -r app-debug.apk
   ```

## Usage

1. Join the hotel Wi-Fi on the watch (Settings → Connections → Wi-Fi).
2. Open Portal Login. It binds to the Wi-Fi and shows the portal page.
3. Log in, then wait for "Online ✓".

Control strip: Back, Reload, Check (re-probe connectivity), Fit (toggle round-safe inset / full-bleed), URL (open an address).

## Troubleshooting

- **The watch drops the Wi-Fi before you can log in.** Tell Android to ignore captive portals (device-wide):
  `adb shell settings put global captive_portal_mode 0`
  Revert with `adb shell settings delete global captive_portal_mode`.
- **Private DNS in strict mode** can break portal pages; turn it off or set it to automatic.
- **Session keeps expiring** on portals keyed to MAC address: set the network's MAC address type to the watch's device MAC instead of a randomized one.
- **Page never appears:** tap URL and open `http://neverssl.com`.
