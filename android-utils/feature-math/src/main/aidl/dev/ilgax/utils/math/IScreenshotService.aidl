package dev.ilgax.utils.math;

import android.os.ParcelFileDescriptor;

// Runs in a Shizuku UserService (shell uid, daemon: the process outlives the app process). The screencap calls
// return the read end of a pipe, so the picture is not limited by the 1 MB binder transaction size. Errors are
// thrown as IllegalStateException. Explicit ids because Shizuku calls transaction 16777115 to destroy the service
// (AIDL adds FIRST_CALL_TRANSACTION, so destroy() is id 16777114); AIDL wants all ids or none.
interface IScreenshotService {
    /** `screencap -p`: PNG bytes, fully produced before the pipe is returned (fallback path). */
    ParcelFileDescriptor screencap() = 0;

    /** `screencap` without -p: 12/16-byte header + raw RGBA, streamed into the pipe while screencap runs. */
    ParcelFileDescriptor screencapRaw() = 1;

    /** `appops set <package> SYSTEM_ALERT_WINDOW allow`. Returns "" on success, else a short error text. */
    String grantOverlayPermission(String packageName) = 2;

    /** Shizuku calls this when the service is unbound; the process exits. */
    void destroy() = 16777114;
}
