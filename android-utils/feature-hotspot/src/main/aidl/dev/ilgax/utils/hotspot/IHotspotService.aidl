package dev.ilgax.utils.hotspot;

import dev.ilgax.utils.hotspot.HotspotStatus;

// Runs in a Shizuku UserService (shell uid). Method ids are explicit because Shizuku calls transaction
// 16777115 (ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy) to destroy the service; AIDL adds
// FIRST_CALL_TRANSACTION (1) to the id, so destroy() is id 16777114, and AIDL wants all ids or none.
interface IHotspotService {
    HotspotStatus start() = 0;
    HotspotStatus stop() = 1;
    HotspotStatus status(boolean includePassword) = 2;

    /** Shizuku calls this when the service is unbound; the process exits. */
    void destroy() = 16777114;
}
