package dev.ilgax.tabremote.wear

import dev.ilgax.tabremote.protocol.Command

/** Target of the "Lock Tablet" launcher alias: runs LOCK with no extras. */
class LockNowActivity : CommandActivity() {
    override val defaultCommand: Command = Command.LOCK
}
