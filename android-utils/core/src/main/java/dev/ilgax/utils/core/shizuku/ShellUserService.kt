package dev.ilgax.utils.core.shizuku

import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku UserService entry point (shell uid, own process). Runs the few system commands the macro steps need
 * (`settings`, `svc`, `cmd`, `am`). Shizuku instantiates it through the no-arg constructor.
 */
class ShellUserService : IShellService.Stub() {
    override fun run(argv: Array<String>, timeoutSeconds: Int): String {
        if (!ShellPolicy.allowed(argv.toList())) throw IllegalStateException("command not allowed: ${argv.firstOrNull()}")
        val process = ProcessBuilder(argv.toList()).redirectErrorStream(true).start()
        process.outputStream.close()
        val out = StringBuilder()
        val reader = Thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } }.apply { isDaemon = true; start() }
        if (!process.waitFor(timeoutSeconds.coerceIn(1, 30).toLong(), TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("${argv.first()} timed out")
        }
        reader.join(1000)
        return "${process.exitValue()}\n${out.toString().trim().take(MAX_OUTPUT)}"
    }

    override fun destroy() {
        exitProcess(0)
    }

    private companion object {
        const val MAX_OUTPUT = 2000
    }
}

/** What the shell service will run. Pure, so it is unit-tested. */
internal object ShellPolicy {
    private val BINARIES = setOf("/system/bin/settings", "/system/bin/svc", "/system/bin/cmd", "/system/bin/am")

    fun allowed(argv: List<String>): Boolean =
        argv.isNotEmpty() && argv[0] in BINARIES && argv.all { it.length <= 256 && '\u0000' !in it }
}
