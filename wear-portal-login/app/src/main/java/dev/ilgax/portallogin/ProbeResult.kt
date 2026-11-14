package dev.ilgax.portallogin

import java.net.URI

/** Outcome of the connectivity probe. Pure Kotlin so it can be unit tested on the JVM. */
sealed class ProbeResult {
    object Online : ProbeResult()
    data class Portal(val url: String) : ProbeResult()
    data class Error(val message: String) : ProbeResult()
}

object ProbeClassifier {
    const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"

    fun classify(code: Int, location: String?, body: String?, probeUrl: String = PROBE_URL): ProbeResult =
        when {
            code == 204 -> ProbeResult.Online
            code in 300..399 && !location.isNullOrBlank() ->
                ProbeResult.Portal(resolve(probeUrl, location.trim()))
            else -> ProbeResult.Portal(probeUrl)
        }

    private fun resolve(base: String, location: String): String =
        try {
            URI(base).resolve(location).toString()
        } catch (e: Exception) {
            location
        }
}
