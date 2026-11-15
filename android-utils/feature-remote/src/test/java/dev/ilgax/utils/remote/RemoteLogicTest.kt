package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.Status
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.remote.RemoteState.Service
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteLogicTest {
    private val none = SetupChecks(false, false, false, false, false)
    private val all = SetupChecks(true, true, true, true, true)

    @Test
    fun setupCollapsesOnlyWhenEverythingIsDone() {
        assertTrue(all.allSet)
        assertEquals(SetupItem.entries, none.pending)
        assertEquals(listOf(SetupItem.Accessibility), all.copy(accessibility = false).pending)
        assertFalse(all.copy(battery = false).allSet)
    }

    @Test
    fun cardStatusCoversEveryServiceState() {
        fun id(s: Service, paired: Boolean = true, bt: Boolean = true) = RemoteText.cardStatus(s, paired, bt).id
        assertEquals(R.string.remote_card_listening_paired, id(Service.RUNNING))
        assertEquals(R.string.remote_card_listening_unpaired, id(Service.RUNNING, paired = false))
        assertEquals(R.string.remote_card_bluetooth_off, id(Service.BLUETOOTH_OFF))
        assertEquals(R.string.remote_card_permission, id(Service.NO_PERMISSION))
        assertEquals(R.string.remote_card_permission, id(Service.STOPPED, bt = false))
        assertEquals(R.string.remote_card_not_paired, id(Service.STOPPED, paired = false))
        assertEquals(R.string.remote_card_off, id(Service.STOPPED))
        assertEquals(R.string.remote_card_error, id(Service.ERROR))
    }

    @Test
    fun cardIsOnWhileListeningEvenWithoutBluetooth() {
        assertEquals(CardMode.On, RemoteText.cardMode(Service.RUNNING))
        assertEquals(CardMode.On, RemoteText.cardMode(Service.BLUETOOTH_OFF))
        assertEquals(CardMode.Off, RemoteText.cardMode(Service.STOPPED))
        assertEquals(CardMode.Off, RemoteText.cardMode(Service.ERROR))
    }

    @Test
    fun pairingStepsAndCodeFormatting() {
        assertNull(pairingStep(RemoteState.PairingUi.Off, 0))
        assertEquals(PairingStep.Waiting(42), pairingStep(RemoteState.PairingUi.Waiting(1L), 42))
        assertEquals(
            PairingStep.Code("482 913", 30, true),
            pairingStep(RemoteState.PairingUi.Code("482913", 1L, true), 30),
        )
    }

    @Test
    fun resultCodesMapToMessages() {
        assertEquals(R.string.remote_res_ok, statusTextRes(Status.SUCCESS))
        assertEquals(R.string.remote_res_a11y, statusTextRes(Status.ACCESSIBILITY_DISABLED))
        assertEquals(R.string.remote_res_nothing_to_close, statusTextRes(Status.NOTHING_TO_CLOSE))
        assertEquals(R.string.remote_res_failed, statusTextRes(Status.RATE_LIMITED))
    }
}
