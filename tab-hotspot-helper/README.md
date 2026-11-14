# tab-hotspot-helper

Tiny Java helper, run as the ADB shell user (uid 2000, `com.android.shell`) via `app_process`, that asks the
system tethering service to start/stop Wi-Fi tethering directly, bypassing Samsung's Settings UI. It prints
diagnostic output (stages, stack traces, hotspot SSID/password, AP state).

**Untested on a device.** All hidden APIs are reached via reflection and some signatures are guesses.

## Build

    ./build.sh

Produces `dist/hotspot.jar` (a dex-in-jar). Needs JDK and the Android SDK (`ANDROID_HOME`, default `/opt/android-sdk`).

## Install and run (from Termux, jar in the tablet's Download folder)

    adb shell cp /sdcard/Download/hotspot.jar /data/local/tmp/

    adb shell CLASSPATH=/data/local/tmp/hotspot.jar app_process /system/bin dev.ilgax.hotspot.Main start
    adb shell CLASSPATH=/data/local/tmp/hotspot.jar app_process /system/bin dev.ilgax.hotspot.Main stop
    adb shell CLASSPATH=/data/local/tmp/hotspot.jar app_process /system/bin dev.ilgax.hotspot.Main status

## Notes

- A hotspot started this way is stopped with `stop`.
- It must be re-run after every reboot.
- The device-wide effect is just normal Wi-Fi tethering.
