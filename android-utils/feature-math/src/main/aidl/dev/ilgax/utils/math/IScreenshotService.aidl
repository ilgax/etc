package dev.ilgax.utils.math;

import android.os.ParcelFileDescriptor;

// Runs in a Shizuku UserService (shell uid). screencap returns the read end of a pipe that carries the PNG
// bytes, so the picture is not limited by the 1 MB binder transaction size. Errors are thrown as
// IllegalStateException. Explicit ids because Shizuku calls transaction 16777115 to destroy the service
// (AIDL adds FIRST_CALL_TRANSACTION, so destroy() is id 16777114); AIDL wants all ids or none.
interface IScreenshotService {
    ParcelFileDescriptor screencap() = 0;

    /** Shizuku calls this when the service is unbound; the process exits. */
    void destroy() = 16777114;
}
