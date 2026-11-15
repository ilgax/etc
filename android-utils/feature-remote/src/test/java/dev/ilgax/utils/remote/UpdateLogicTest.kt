package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.UpdateReport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLogicTest {
    private fun c(iface: String, ip: String) = UpdateAddresses.Candidate(iface, ip)

    @Test fun addressesAreOrderedHotspotThenWifiThenOthersAndCapped() {
        val picked = UpdateAddresses.pick(
            listOf(
                c("rmnet0", "10.20.30.40"), c("wlan0", "192.168.1.20"), c("lo", "127.0.0.1"),
                c("swlan0", "192.168.43.1"), c("wlan0", "192.168.1.20"), c("eth0", "172.16.0.9"),
                c("dummy0", "169.254.3.4"), c("rndis0", "192.168.42.129"), c("p2p0", "10.1.1.1"),
            ),
        )
        assertEquals(listOf("192.168.43.1", "192.168.1.20", "10.20.30.40", "172.16.0.9"), picked)
    }

    @Test fun unusableAddressesAreDropped() {
        assertFalse(UpdateAddresses.usable("127.0.0.1"))
        assertFalse(UpdateAddresses.usable("0.0.0.0"))
        assertFalse(UpdateAddresses.usable("169.254.1.1"))
        assertFalse(UpdateAddresses.usable("224.0.0.1"))
        assertFalse(UpdateAddresses.usable("fe80::1"))
        assertTrue(UpdateAddresses.usable("192.168.43.1"))
    }

    private fun snap(
        ended: UpdateServer.Reason? = null, served: Long = 0, report: UpdateReport? = null, deadline: Long = 600_000,
    ) = UpdateSnapshot(ended, served, 1000, deadline, report)

    @Test fun stateMapping() {
        assertEquals(WatchUpdateState.Idle, UpdateHost.stateOf(null, 0))
        assertEquals(WatchUpdateState.Waiting(590), UpdateHost.stateOf(snap(), 10_000))
        assertEquals(WatchUpdateState.Downloading(25), UpdateHost.stateOf(snap(served = 250), 0))
        assertEquals(
            WatchUpdateState.Downloading(0),
            UpdateHost.stateOf(snap(report = UpdateReport(UpdateReport.Phase.DOWNLOADING, 0)), 0),
        )
        assertEquals(WatchUpdateState.Installing, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.COMPLETED, served = 1000), 0))
        assertEquals(
            WatchUpdateState.Installing,
            UpdateHost.stateOf(snap(served = 1000, report = UpdateReport(UpdateReport.Phase.INSTALLING, 0)), 0),
        )
        assertEquals(WatchUpdateState.TimedOut, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.TIMEOUT), 0))
        assertEquals(WatchUpdateState.Idle, UpdateHost.stateOf(snap(ended = UpdateServer.Reason.STOPPED), 0))
        assertEquals(
            WatchUpdateState.Failed(UpdateReport.Failure.HASH_MISMATCH),
            UpdateHost.stateOf(snap(served = 1000, report = UpdateReport.failed(UpdateReport.Failure.HASH_MISMATCH)), 0),
        )
    }

    @Test fun versionStates() {
        fun ui(reported: Int?, update: WatchUpdateState = WatchUpdateState.Idle) = WatchAppUi("0.2.0", 2, reported, update)
        assertTrue(ui(1).updateAvailable)
        assertFalse(ui(2).updateAvailable)
        assertTrue(ui(2).upToDate)
        assertFalse(ui(null).updateAvailable)
        assertFalse(ui(null).upToDate)
        assertEquals(R.string.remote_app_st_available, ui(1).statusRes())
        assertEquals(R.string.remote_app_st_current, ui(2).statusRes())
        assertEquals(R.string.remote_app_st_unknown, ui(null).statusRes())
        assertEquals(R.string.remote_app_st_waiting, ui(1, WatchUpdateState.Waiting(100)).statusRes())
        assertTrue(ui(1, WatchUpdateState.Waiting(100)).active)
        assertFalse(ui(1, WatchUpdateState.TimedOut).active)
        assertEquals(
            R.string.remote_app_st_hash,
            ui(1, WatchUpdateState.Failed(UpdateReport.Failure.HASH_MISMATCH)).statusRes(),
        )
    }
}
