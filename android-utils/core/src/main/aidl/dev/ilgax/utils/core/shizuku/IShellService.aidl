package dev.ilgax.utils.core.shizuku;

// Runs in a Shizuku UserService (shell uid). Explicit ids because Shizuku calls transaction 16777115 to destroy the
// service (AIDL adds FIRST_CALL_TRANSACTION, so destroy() is id 16777114); AIDL wants all ids or none.
interface IShellService {
    /**
     * Runs one allow-listed system command (no shell, no pipes) and waits up to timeoutSeconds. The answer is the exit
     * code on the first line, then the combined output. Throws IllegalStateException for a refused command.
     */
    String run(in String[] argv, int timeoutSeconds) = 0;

    /** Shizuku calls this when the service is unbound; the process exits. */
    void destroy() = 16777114;
}
