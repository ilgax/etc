# tab-hotspot-helper

Tiny Java helper, run as the shell user (uid 2000, `com.android.shell`) via `app_process`, that asks the
system tethering service to start/stop Wi-Fi tethering directly, bypassing Samsung's Settings UI. It is now
typically launched through Shizuku's `rish`, but plain `adb shell` works too.

All hidden APIs are reached via reflection.

## Build

    ./build.sh

Produces `dist/hotspot.jar` (a dex-in-jar). Needs JDK and the Android SDK (`ANDROID_HOME`, default `/opt/android-sdk`).

## Install and run

Copy the jar to `/data/local/tmp/hotspot.jar`, then (Shizuku `rish`):

    ~/rish -c "CLASSPATH=/data/local/tmp/hotspot.jar app_process /system/bin dev.ilgax.hotspot.Main toggle"

or via adb:

    adb shell CLASSPATH=/data/local/tmp/hotspot.jar app_process /system/bin dev.ilgax.hotspot.Main status -p

## Commands and flags

    start | stop | toggle | status  [-v] [-p]

- `start`: turn the hotspot on (`Hotspot already on` if it is).
- `stop`: turn it off (`Hotspot already off` if it is); waits up to ~3 s for it to shut down.
- `toggle`: stop if enabled/enabling, otherwise start.
- `status`: show state and summary.
- `-v` / `--verbose`: print stages, bootstrap notes, raw SoftApConfiguration and stack traces.
  Without it, failures are one line: `Error: <Exception>: <message> (run with -v for details)`.
- `-p` / `--show-password`: show the real hotspot password (hidden by default).

Exit code is 0 when the requested end state was reached (including "already on/off") and for `status`, else 1.

## Sample output

    Hotspot on
    Name: xcynet
    Security: WPA2
    Password: (hidden, use -p)
    Band: 2.4 GHz
    Clients: 1
    Wi-Fi: HomeNet 5180 MHz

## Status on a Tab S9 FE+ Wi-Fi (Android 16)

- Confirmed working: `start` (context bootstrap, TetheringManager, TetheringRequest, callback) and `status`
  (AP state, SoftAp configuration).
- `stop`: not yet confirmed on the device.
- New and unverified on the device: the upstream `Wi-Fi:` line (WifiManager bound to the shell context via
  `IWifiManager$Stub.asInterface` and the hidden `WifiManager(Context, IWifiManager)` constructor) and the
  `Clients:` count (`registerTetheringEventCallback` / `onClientsChanged`). Lines that cannot be read are omitted.

## Notes

- It must be re-run after every reboot.
- The device-wide effect is just normal Wi-Fi tethering.
