package dev.ilgax.utils.core.shizuku

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStatusTest {
    @Test
    fun resolvesEveryCombination() {
        assertEquals(ShizukuStatus.Ready, resolveShizukuStatus(binderAlive = true, permissionGranted = true, installed = true))
        assertEquals(ShizukuStatus.PermissionNeeded, resolveShizukuStatus(true, false, true))
        assertEquals(ShizukuStatus.NotRunning, resolveShizukuStatus(false, false, true))
        assertEquals(ShizukuStatus.NotInstalled, resolveShizukuStatus(false, false, false))
    }

    @Test
    fun diagnosticsNamesMatchTheOriginalApp() {
        assertEquals(
            listOf("NOT_INSTALLED", "NOT_RUNNING", "NEEDS_PERMISSION", "READY"),
            ShizukuStatus.entries.map { it.diagName },
        )
    }
}
