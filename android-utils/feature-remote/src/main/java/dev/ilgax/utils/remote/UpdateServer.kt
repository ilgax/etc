package dev.ilgax.utils.remote

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One-shot HTTP server for the watch APK. A plain [ServerSocket] on an ephemeral port that answers exactly one
 * request target, [path] (it contains the random token), with the APK; every other request gets 404. It stops
 * itself after the APK was sent completely once, or after [timeoutMs], whichever comes first.
 */
class UpdateServer(
    private val path: String,
    private val length: Long,
    private val open: () -> InputStream,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val listener: Listener = object : Listener {},
) {
    enum class Reason { COMPLETED, TIMEOUT, STOPPED }

    /** Called on server threads. */
    interface Listener {
        fun onFinished(reason: Reason) {}
    }

    private val served = AtomicLong()
    private val finished = AtomicBoolean()
    private val connections = Semaphore(MAX_CONNECTIONS)
    private val active = java.util.Collections.synchronizedSet(HashSet<Socket>())
    @Volatile private var socket: ServerSocket? = null

    /** Bytes of the APK written to clients so far (across attempts). */
    val bytesServed: Long get() = served.get()
    val isRunning: Boolean get() = socket != null && !finished.get()

    /** Binds an ephemeral port on all interfaces and starts serving; returns the port, or -1 on failure. */
    fun start(): Int {
        val s = try {
            ServerSocket(0, 8)
        } catch (e: IOException) {
            return -1
        }
        socket = s
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        Thread({ acceptLoop(s, deadline) }, "watch-update-http").apply { isDaemon = true }.start()
        return s.localPort
    }

    fun stop() = finish(Reason.STOPPED)

    private fun acceptLoop(s: ServerSocket, deadline: Long) {
        s.soTimeout = POLL_MS
        while (!finished.get()) {
            if (System.nanoTime() - deadline >= 0) return finish(Reason.TIMEOUT)
            val c = try {
                s.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                return finish(Reason.STOPPED)
            }
            if (!connections.tryAcquire()) {
                closeQuietly(c)
                continue
            }
            active.add(c)
            Thread({
                try {
                    handle(c)
                } catch (_: IOException) {
                } finally {
                    active.remove(c)
                    closeQuietly(c)
                    connections.release()
                }
            }, "watch-update-conn").apply { isDaemon = true }.start()
        }
    }

    private fun handle(c: Socket) {
        c.soTimeout = REQUEST_TIMEOUT_MS
        val target = readRequestTarget(c.getInputStream())
        val out = c.getOutputStream()
        if (target == null || !constantTimeEquals(target, path) || finished.get()) {
            val body = "Not found".toByteArray()
            out.write(
                ("HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray() + body,
            )
            out.flush()
            return
        }
        c.soTimeout = 0
        out.write(
            ("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\n" +
                "Content-Length: $length\r\nConnection: close\r\n\r\n").toByteArray(),
        )
        var sent = 0L
        open().use { input ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                sent += n
                served.addAndGet(n.toLong())
            }
        }
        out.flush()
        if (sent != length) return
        // Wait for the client to close, so "completed" means it read everything and not just that we wrote it.
        c.shutdownOutput()
        c.soTimeout = CLOSE_WAIT_MS
        try {
            if (c.getInputStream().read() == -1) finish(Reason.COMPLETED)
        } catch (_: SocketTimeoutException) {
        }
    }

    /** Returns the request target of a well-formed `GET <target> HTTP/1.x`, or null for anything else. */
    private fun readRequestTarget(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        var state = 0 // progress through \r\n\r\n
        while (head.size() < MAX_HEAD) {
            val b = try {
                input.read()
            } catch (_: SocketTimeoutException) {
                return null
            }
            if (b < 0) return null
            head.write(b)
            state = when {
                b == '\r'.code && (state == 0 || state == 2) -> state + 1
                b == '\n'.code && (state == 1 || state == 3) -> state + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        if (state != 4) return null
        val line = String(head.toByteArray(), Charsets.ISO_8859_1).substringBefore("\r\n").split(' ')
        if (line.size != 3 || line[0] != "GET" || !line[2].startsWith("HTTP/1.")) return null
        return line[1]
    }

    private fun finish(reason: Reason) {
        if (!finished.compareAndSet(false, true)) return
        closeQuietly(socket)
        synchronized(active) { active.toList() }.forEach { if (reason != Reason.COMPLETED) closeQuietly(it) }
        listener.onFinished(reason)
    }

    private fun constantTimeEquals(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.ISO_8859_1), b.toByteArray(Charsets.ISO_8859_1))

    private fun closeQuietly(c: java.io.Closeable?) {
        try {
            c?.close()
        } catch (_: IOException) {
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L
        private const val POLL_MS = 100
        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val CLOSE_WAIT_MS = 3_000
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_CONNECTIONS = 8
    }
}
