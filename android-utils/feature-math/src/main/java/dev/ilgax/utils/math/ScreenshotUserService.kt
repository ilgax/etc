package dev.ilgax.utils.math

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService entry point; runs as the shell uid in its own (daemon) process. Shizuku instantiates it
 * through the no-arg constructor.
 */
class ScreenshotUserService : IScreenshotService.Stub() {
    /** PNG fallback: the whole PNG is read first so a failed capture is reported as an exception with the stderr text. */
    override fun screencap(): ParcelFileDescriptor {
        val process = ProcessBuilder("/system/bin/screencap", "-p").start()
        val errors = ByteArrayOutputStream()
        val errReader = thread(isDaemon = true) { runCatching { process.errorStream.copyTo(errors) } }
        var png = ByteArray(0)
        val outReader = thread(isDaemon = true) { runCatching { png = process.inputStream.readBytes() } }
        if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("screencap timed out after ${TIMEOUT_S}s")
        }
        outReader.join(2000)
        errReader.join(500)
        if (process.exitValue() != 0 || !isPng(png)) {
            val detail = errors.toString(Charsets.UTF_8.name()).trim().take(200)
            throw IllegalStateException("screencap exit ${process.exitValue()}, ${png.size} bytes${if (detail.isEmpty()) "" else ": $detail"}")
        }
        val pipe = ParcelFileDescriptor.createPipe()
        thread(isDaemon = true) {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { runCatching { it.write(png) } }
        }
        return pipe[0]
    }

    /**
     * Raw capture: no PNG encode here and no decode in the app. The pipe is returned as soon as screencap has
     * started and its stdout is copied straight into it. A failure shows up as a short or malformed stream,
     * which the client rejects (it checks the byte count against the header) and then falls back to [screencap].
     */
    override fun screencapRaw(): ParcelFileDescriptor {
        val process = ProcessBuilder("/system/bin/screencap").start()
        val pipe = ParcelFileDescriptor.createPipe()
        thread(isDaemon = true) { runCatching { process.errorStream.copyTo(ByteArrayOutputStream()) } }
        thread(isDaemon = true) {
            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                runCatching { process.inputStream.copyTo(out, 1 shl 20) }
            }
        }
        thread(isDaemon = true) {
            if (!process.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) process.destroyForcibly()
        }
        return pipe[0]
    }

    override fun grantOverlayPermission(packageName: String): String {
        if (!PACKAGE_NAME.matches(packageName)) return "bad package name"
        val attempts = listOf(
            listOf("/system/bin/appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
            listOf("/system/bin/cmd", "appops", "set", packageName, "SYSTEM_ALERT_WINDOW", "allow"),
        )
        var last = "appops not found"
        for (cmd in attempts) {
            try {
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val text = p.inputStream.bufferedReader().readText().trim()
                if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) { p.destroyForcibly(); last = "appops timed out"; continue }
                if (p.exitValue() == 0) return ""
                last = "appops exit ${p.exitValue()}: ${text.take(160)}"
            } catch (t: Throwable) {
                last = t.message ?: t.javaClass.simpleName
            }
        }
        return last
    }

    override fun destroy() {
        exitProcess(0)
    }

    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private companion object {
        const val TIMEOUT_S = 8L
        val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    }
}
