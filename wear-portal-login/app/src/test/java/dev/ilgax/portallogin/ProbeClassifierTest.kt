package dev.ilgax.portallogin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProbeClassifierTest {
    private val probe = ProbeClassifier.PROBE_URL

    @Test fun http204IsOnline() =
        assertEquals(ProbeResult.Online, ProbeClassifier.classify(204, null, null))

    @Test fun redirectWithAbsoluteLocation() =
        assertEquals(
            ProbeResult.Portal("http://portal.example.com/login?x=1"),
            ProbeClassifier.classify(302, "http://portal.example.com/login?x=1", null),
        )

    @Test fun redirectWithRelativeLocation() =
        assertEquals(
            ProbeResult.Portal("http://connectivitycheck.gstatic.com/login"),
            ProbeClassifier.classify(302, "/login", null),
        )

    @Test fun okWithBodyLoadsProbeUrl() =
        assertEquals(ProbeResult.Portal(probe), ProbeClassifier.classify(200, null, "<html>login</html>"))

    @Test fun okEmptyBodyLoadsProbeUrl() =
        assertEquals(ProbeResult.Portal(probe), ProbeClassifier.classify(200, null, ""))

    @Test fun serverErrorLoadsProbeUrl() =
        assertEquals(ProbeResult.Portal(probe), ProbeClassifier.classify(500, null, null))

    @Test fun redirectWithoutLocationLoadsProbeUrl() =
        assertEquals(ProbeResult.Portal(probe), ProbeClassifier.classify(302, null, null))
}

class UrlNormalizerTest {
    @Test fun addsHttp() = assertEquals("http://example.com", UrlNormalizer.normalize("example.com"))
    @Test fun trims() = assertEquals("http://example.com/a", UrlNormalizer.normalize("  example.com/a \n"))
    @Test fun keepsHttps() = assertEquals("https://example.com", UrlNormalizer.normalize("https://example.com"))
    @Test fun keepsHttp() = assertEquals("http://example.com", UrlNormalizer.normalize("http://example.com"))
    @Test fun rejectsEmpty() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("   "))
    }
}
