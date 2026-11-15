package dev.ilgax.utils.math.recognition

import java.io.File

/**
 * How many ONNX Runtime worker threads to use. Four is the sweet spot on the target tablet (4 big + 4 little
 * cores): more threads end up on the little cores and make every parallel op wait for the slowest one. The
 * count is lowered only when fewer than four cores run at a useful speed (some phones pair two big cores with
 * six much slower ones).
 */
object CpuTopology {
    const val MAX_THREADS = 4
    private const val FAST_FRACTION = 0.6

    /** [maxFreqKhz]: each core's maximum frequency; empty when unknown. */
    fun chooseThreads(maxFreqKhz: List<Long>, cores: Int): Int {
        val known = maxFreqKhz.filter { it > 0 }
        if (known.isEmpty()) return cores.coerceIn(1, MAX_THREADS)
        val top = known.max()
        val fast = known.count { it >= top * FAST_FRACTION }
        return fast.coerceIn(1, MAX_THREADS).coerceAtMost(cores.coerceAtLeast(1))
    }

    fun threads(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return chooseThreads(readMaxFreqs(cores), cores)
    }

    private fun readMaxFreqs(cores: Int): List<Long> = (0 until cores).mapNotNull { i ->
        runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
    }
}
