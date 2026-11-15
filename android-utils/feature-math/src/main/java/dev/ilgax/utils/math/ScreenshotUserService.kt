package dev.ilgax.utils.math

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService entry point; runs as the shell uid in its own process. Shizuku instantiates it
 * through the no-arg constructor.
 */
class ScreenshotUserService : IScreenshotService.Stub() {
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

    override fun destroy() {
        exitProcess(0)
    }

    private fun isPng(b: ByteArray) = b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() && b[3] == 'G'.code.toByte()

    private companion object {
        const val TIMEOUT_S = 8L
    }
}
