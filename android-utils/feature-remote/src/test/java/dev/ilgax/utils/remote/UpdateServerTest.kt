package dev.ilgax.utils.remote

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdateServerTest {
    private val apk = ByteArray(300_000) { (it * 31).toByte() }
    private val path = "/watch-update/00112233445566778899aabbccddeeff.apk"

    private class Response(val status: Int, val body: ByteArray)

    private fun server(
        timeoutMs: Long = 10_000,
        open: () -> InputStream = { ByteArrayInputStream(apk) },
        finished: CopyOnWriteArrayList<UpdateServer.Reason> = CopyOnWriteArrayList(),
        latch: CountDownLatch = CountDownLatch(1),
    ) = UpdateServer(path, apk.size.toLong(), open, timeoutMs, object : UpdateServer.Listener {
        override fun onFinished(reason: UpdateServer.Reason) { finished += reason; latch.countDown() }
    })

    /** Raw request over a real localhost socket; returns status and body. */
    private fun request(port: Int, line: String): Response {
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("$line\r\nHost: x\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val all = s.getInputStream().readBytes()
            val text = String(all, Charsets.ISO_8859_1)
            val status = text.substringBefore("\r\n").split(' ')[1].toInt()
            val bodyStart = text.indexOf("\r\n\r\n") + 4
            return Response(status, all.copyOfRange(bodyStart, all.size))
        }
    }

    @Test fun servesTheApkOnTheTokenPathThenStops() {
        val finished = CopyOnWriteArrayList<UpdateServer.Reason>()
        val latch = CountDownLatch(1)
        val srv = server(finished = finished, latch = latch)
        val port = srv.start()
        assertTrue(port > 0)
        val r = request(port, "GET $path HTTP/1.1")
        assertEquals(200, r.status)
        assertArrayEquals(apk, r.body)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(UpdateServer.Reason.COMPLETED), finished.toList())
        assertEquals(apk.size.toLong(), srv.bytesServed)
        assertFalse(srv.isRunning)
    }

    @Test fun wrongPathsAndMethodsGet404() {
        val srv = server()
        val port = srv.start()
        try {
            assertEquals(404, request(port, "GET / HTTP/1.1").status)
            assertEquals(404, request(port, "GET ${path}x HTTP/1.1").status)
            assertEquals(404, request(port, "GET $path?a=1 HTTP/1.1").status)
            assertEquals(404, request(port, "GET /watch-update/ffffffffffffffffffffffffffffffff.apk HTTP/1.1").status)
            assertEquals(404, request(port, "POST $path HTTP/1.1").status)
            assertEquals(404, request(port, "HEAD $path HTTP/1.1").status)
            assertEquals(404, request(port, "GET $path SPDY/3").status)
            assertEquals(0L, srv.bytesServed)
            assertTrue(srv.isRunning)
        } finally {
            srv.stop()
        }
    }

    @Test fun singleUseAfterACompleteDownload() {
        val latch = CountDownLatch(1)
        val srv = server(latch = latch)
        val port = srv.start()
        assertEquals(200, request(port, "GET $path HTTP/1.1").status)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // The listening socket is closed: a second download cannot connect at all.
        val reconnected = try { Socket(InetAddress.getLoopbackAddress(), port).use { true } } catch (e: java.io.IOException) { false }
        assertFalse(reconnected)
    }

    @Test fun anAbortedDownloadCanBeRetried() {
        val srv = server()
        val port = srv.start()
        try {
            // Read only the start of the response, then drop the connection.
            Socket(InetAddress.getLoopbackAddress(), port).use { s ->
                s.getOutputStream().write("GET $path HTTP/1.1\r\n\r\n".toByteArray())
                s.getInputStream().read(ByteArray(200))
            }
            assertTrue(srv.isRunning)
            val r = request(port, "GET $path HTTP/1.1")
            assertEquals(200, r.status)
            assertArrayEquals(apk, r.body)
        } finally {
            srv.stop()
        }
    }

    @Test fun stopsAfterTheTimeout() {
        val finished = CopyOnWriteArrayList<UpdateServer.Reason>()
        val latch = CountDownLatch(1)
        val srv = server(timeoutMs = 300, finished = finished, latch = latch)
        val port = srv.start()
        assertTrue(srv.isRunning)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(UpdateServer.Reason.TIMEOUT), finished.toList())
        assertFalse(srv.isRunning)
        val connected = try { Socket(InetAddress.getLoopbackAddress(), port).use { true } } catch (e: java.io.IOException) { false }
        assertFalse(connected)
    }

    @Test fun stopIsFinalAndReportedOnce() {
        val finished = CopyOnWriteArrayList<UpdateServer.Reason>()
        val latch = CountDownLatch(1)
        val srv = server(finished = finished, latch = latch)
        srv.start()
        srv.stop()
        srv.stop()
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(UpdateServer.Reason.STOPPED), finished.toList())
    }
}
