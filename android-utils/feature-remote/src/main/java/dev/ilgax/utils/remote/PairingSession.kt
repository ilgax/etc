package dev.ilgax.utils.remote

import android.os.Handler
import android.os.Looper
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.CommandFrame
import dev.ilgax.tabremote.protocol.Hmac
import dev.ilgax.tabremote.protocol.Pairing
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.tabremote.protocol.Status

/** Tablet side of pairing. The candidate key is held in memory only until it is committed. */
class PairingSession(private val onModeChanged: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val expire = Runnable { cancel() }

    private var deadline = 0L
    private var derived: Pairing.Derived? = null
    private var tabletPub: ByteArray? = null
    private var device: String? = null
    private var confirmed = false

    val isActive: Boolean
        @Synchronized get() = deadline > System.currentTimeMillis()

    fun start() {
        synchronized(this) {
            clear()
            deadline = System.currentTimeMillis() + Protocol.PAIRING_WINDOW_MS
            RemoteState.pairing = RemoteState.PairingUi.Waiting(deadline)
            main.postDelayed(expire, Protocol.PAIRING_WINDOW_MS)
        }
        onModeChanged()
    }

    fun cancel() {
        synchronized(this) {
            if (deadline == 0L) return
            clear()
        }
        onModeChanged()
    }

    @Synchronized
    fun confirm() {
        val d = derived ?: return
        if (!isActive) return
        confirmed = true
        RemoteState.pairing = RemoteState.PairingUi.Code(d.code, deadline, true)
    }

    /** Handles a PAIR write from the watch. [isConnected] tells whether a previous pairing device is still around. */
    @Synchronized
    fun onWatchKey(address: String, watchPub: ByteArray, isConnected: (String) -> Boolean): Status {
        if (!isActive) return Status.PAIRING_REJECTED
        val prev = device
        if (prev != null && prev != address && isConnected(prev)) return Status.PAIRING_REJECTED
        return try {
            val peer = Pairing.decodePublicKey(watchPub)
            val kp = Pairing.generateKeyPair()
            val tPub = Pairing.encodePublicKey(kp.public)
            val d = Pairing.derive(kp.private, peer, watchPub, tPub)
            derived = d
            tabletPub = tPub
            device = address
            confirmed = false
            RemoteState.pairing = RemoteState.PairingUi.Code(d.code, deadline, false)
            Status.SUCCESS
        } catch (e: Exception) {
            Status.PAIRING_REJECTED
        }
    }

    @Synchronized
    fun tabletPublicKey(): ByteArray? = if (isActive) tabletPub else null

    /**
     * Checks a frame against the candidate key. Returns null when it does not verify with it (caller
     * should try the stored key). The key is committed only for a PING after the user confirmed here.
     */
    fun tryCommit(nonce: ByteArray, frame: ByteArray, store: (ByteArray) -> Unit): Status? {
        val status = synchronized(this) {
            if (!isActive) return null
            val d = derived ?: return null
            val r = CommandFrame.verify({ Hmac.sha256(d.key, it) }, nonce, frame)
            if (r !is CommandFrame.Result.Valid) return null
            if (r.command != Command.PING || !confirmed) return Status.PAIRING_REJECTED
            store(d.key)
            clear()
            Status.SUCCESS
        }
        onModeChanged()
        return status
    }

    private fun clear() {
        main.removeCallbacks(expire)
        deadline = 0L
        derived = null
        tabletPub = null
        device = null
        confirmed = false
        RemoteState.pairing = RemoteState.PairingUi.Off
    }
}
