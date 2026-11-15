package dev.ilgax.tabremote.protocol

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Downloads the offered APK, trying each offered address in order, and checks its SHA-256. Pure JVM. */
object UpdateFetch {
    /** An open HTTP body; [length] is -1 when unknown. */
    class Body(val stream: InputStream, val length: Long)

    sealed interface Outcome {
        class Ok(val address: String) : Outcome
        /** The downloaded bytes do not hash to the offered SHA-256. Nothing else is tried. */
        data object HashMismatch : Outcome
        /** Every address failed; [attempts] are the addresses tried, in order. */
        class Unreachable(val attempts: List<String>) : Outcome
    }

    /**
     * [open] returns the body for a URL or throws [IOException] (it must also refuse non-200 responses).
     * [target] is overwritten for each attempt and deleted unless the result is [Outcome.Ok].
     * [onProgress] gets 0..100 when the length is known.
     */
    fun fetch(
        offer: UpdateOffer,
        open: (String) -> Body,
        target: File,
        maxBytes: Long = 64L * 1024 * 1024,
        onProgress: (Int) -> Unit = {},
    ): Outcome {
        val tried = ArrayList<String>()
        for (address in offer.addresses) {
            tried += address
            val digest: ByteArray = try {
                download(open(offer.url(address)), target, maxBytes, onProgress)
            } catch (e: IOException) {
                continue
            }
            if (MessageDigest.isEqual(digest, offer.sha256)) return Outcome.Ok(address)
            target.delete()
            return Outcome.HashMismatch
        }
        target.delete()
        return Outcome.Unreachable(tried)
    }

    private fun download(body: Body, target: File, maxBytes: Long, onProgress: (Int) -> Unit): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        body.stream.use { input ->
            if (body.length > maxBytes) throw IOException("too large")
            target.outputStream().use { out ->
                val buf = ByteArray(16 * 1024)
                var total = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) throw IOException("too large")
                    md.update(buf, 0, n)
                    out.write(buf, 0, n)
                    if (body.length > 0) {
                        val pct = (total * 100 / body.length).toInt().coerceIn(0, 100)
                        if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                    }
                }
                if (body.length >= 0 && total != body.length) throw IOException("truncated")
            }
        }
        return md.digest()
    }

    fun sha256(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest()
    }
}
