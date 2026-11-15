package dev.ilgax.utils.core.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellPolicyTest {
    @Test fun onlyTheMacroBinariesAreAllowed() {
        assertTrue(ShellPolicy.allowed(listOf("/system/bin/settings", "put", "system", "screen_brightness", "40")))
        assertTrue(ShellPolicy.allowed(listOf("/system/bin/svc", "wifi", "enable")))
        assertTrue(ShellPolicy.allowed(listOf("/system/bin/cmd", "notification", "set_dnd", "off")))
        assertTrue(ShellPolicy.allowed(listOf("/system/bin/am", "force-stop", "a.b")))
        assertFalse(ShellPolicy.allowed(emptyList()))
        assertFalse(ShellPolicy.allowed(listOf("/system/bin/sh", "-c", "id")))
        assertFalse(ShellPolicy.allowed(listOf("settings", "put")))
        assertFalse(ShellPolicy.allowed(listOf("/system/bin/svc", "a".repeat(300))))
    }

    @Test fun serviceAnswerIsParsed() {
        val ok = ShizukuShell.parse("0\ndone")
        assertTrue(ok.ok); assertEquals("done", ok.output)
        val bad = ShizukuShell.parse("1\nboom\nmore")
        assertFalse(bad.ok); assertEquals(1, bad.exitCode); assertEquals("boom\nmore", bad.output)
        assertEquals(-1, ShizukuShell.parse("garbage").exitCode)
        assertEquals(0, ShizukuShell.parse("0").exitCode)
    }
}
