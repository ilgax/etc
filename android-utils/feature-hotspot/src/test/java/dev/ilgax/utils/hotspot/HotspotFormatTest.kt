package dev.ilgax.utils.hotspot

import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HotspotFormatTest {
    private val on = HotspotUiState(
        apState = ApState.ENABLED, ssid = "xcynet", hidden = true, securityType = 1, bands = 1,
        clientCount = 1, upstreamSsid = "NAVONA", upstreamFrequency = 5200,
    )

    @Test
    fun apStatesMapToPhases() {
        assertEquals(HotspotPhase.Off, phaseOf(11))
        assertEquals(HotspotPhase.TurningOn, phaseOf(12))
        assertEquals(HotspotPhase.On, phaseOf(13))
        assertEquals(HotspotPhase.TurningOff, phaseOf(10))
        assertEquals(HotspotPhase.Failed, phaseOf(14))
        assertEquals(HotspotPhase.Unknown, phaseOf(-1))
        assertEquals(HotspotPhase.Unknown, phaseOf(99))
    }

    @Test
    fun securityTypesMapToLabels() {
        assertEquals(SecurityKind.Open, securityKindOf(0))
        assertEquals(SecurityKind.Wpa2, securityKindOf(1))
        assertEquals(SecurityKind.Wpa2Wpa3, securityKindOf(2))
        assertEquals(SecurityKind.Wpa3, securityKindOf(3))
        assertEquals(SecurityKind.Other, securityKindOf(5))
        assertEquals(SecurityKind.Unknown, securityKindOf(-1))
    }

    @Test
    fun bandMaskBecomesLabel() {
        assertEquals("2.4 GHz", bandsLabel(1))
        assertEquals("5 GHz", bandsLabel(2))
        assertEquals("2.4 GHz + 5 GHz", bandsLabel(3))
        assertEquals("2.4 GHz + 5 GHz + 6 GHz", bandsLabel(7))
        assertEquals("", bandsLabel(0))
    }

    @Test
    fun openNetworkIsFlagged() {
        assertTrue(on.copy(securityType = 0).isOpenNetwork)
        assertFalse(on.isOpenNetwork)
        assertFalse(HotspotUiState().isOpenNetwork) // unknown security is not "open"
    }

    @Test
    fun cardModeFollowsShizukuBusyAndPhase() {
        assertEquals(CardMode.On, HotspotText.cardMode(on))
        assertEquals(CardMode.Off, HotspotText.cardMode(on.copy(apState = ApState.DISABLED)))
        assertEquals(CardMode.Busy, HotspotText.cardMode(on.copy(apState = ApState.ENABLING)))
        assertEquals(CardMode.Busy, HotspotText.cardMode(on.copy(busy = true)))
        assertEquals(CardMode.Unavailable, HotspotText.cardMode(on.copy(shizuku = ShizukuStatus.NotRunning)))
    }

    @Test
    fun cardStatusDescribesDevicesAndErrors() {
        val plural = HotspotText.cardStatus(on) as UiText.Plural
        assertEquals(1, plural.count)
        assertEquals(listOf<Any>("xcynet", 1), plural.args)
        assertEquals(R.string.hotspot_card_on_named, (HotspotText.cardStatus(on.copy(clientCount = -1)) as UiText.Res).id)
        assertEquals(R.string.hotspot_card_off, (HotspotText.cardStatus(HotspotUiState(apState = ApState.DISABLED)) as UiText.Res).id)
        assertEquals(
            R.string.hotspot_card_failed,
            (HotspotText.cardStatus(HotspotUiState(apState = ApState.DISABLED, error = "X")) as UiText.Res).id,
        )
        assertEquals(
            R.string.hotspot_needs_shizuku_install,
            (HotspotText.cardStatus(on.copy(shizuku = ShizukuStatus.NotInstalled)) as UiText.Res).id,
        )
    }

    @Test
    fun upstreamRowCoversConnectedOffAndNone() {
        assertEquals(R.string.hotspot_upstream_value, (HotspotText.upstreamRow(on) as UiText.Res).id)
        assertEquals(R.string.hotspot_upstream_wifi_off, (HotspotText.upstreamRow(on.copy(upstreamSsid = null, upstreamFrequency = -1)) as UiText.Res).id)
        assertEquals(R.string.hotspot_upstream_none, (HotspotText.upstreamRow(on.copy(upstreamSsid = null, upstreamFrequency = 0)) as UiText.Res).id)
    }

    @Test
    fun headlineTracksPhase() {
        assertEquals(R.string.hotspot_headline_on, (HotspotText.headline(on) as UiText.Res).id)
        assertEquals(R.string.hotspot_headline_turning_on, (HotspotText.headline(on.copy(apState = ApState.ENABLING)) as UiText.Res).id)
        assertEquals(R.string.hotspot_headline_failed, (HotspotText.headline(on.copy(apState = ApState.FAILED)) as UiText.Res).id)
    }

    @Test
    fun serviceAnswerKeepsKnownFieldsAndDropsPasswordWhenNobodyIsLooking() {
        val withPass = on.copy(passphrase = "secretpass")
        val answer = HotspotStatus().apply { apState = ApState.ENABLED; securityType = 1; clientCount = 2 }
        assertEquals("secretpass", answer.applyTo(withPass, keepPassword = true).passphrase)
        assertNull(answer.applyTo(withPass, keepPassword = false).passphrase)
        assertEquals("xcynet", answer.applyTo(withPass, true).ssid) // unreadable config keeps the last known name
        assertEquals(2, answer.applyTo(withPass, true).clientCount)
    }

    @Test
    fun openNetworkNeverCarriesAPassword() {
        val answer = HotspotStatus().apply { apState = ApState.ENABLED; securityType = 0; ssid = "x" }
        assertNull(answer.applyTo(on.copy(passphrase = "old"), keepPassword = true).passphrase)
    }
}
