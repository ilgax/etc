package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Status

data class SlotUi(val label: String, val packageName: String)

/** Everything the remote detail screen draws. Plain data so it can be rendered from fixtures. */
data class RemoteUiState(
    val service: RemoteState.Service,
    val serviceDetail: String? = null,
    val checks: SetupChecks,
    val paired: Boolean,
    val pairing: RemoteState.PairingUi = RemoteState.PairingUi.Off,
    /** Always [dev.ilgax.tabremote.protocol.Protocol.APP_SLOTS] entries; null = empty slot. */
    val slots: List<SlotUi?>,
    /** True when Shizuku is ready, i.e. closing apps will force-stop them. */
    val forceStop: Boolean,
)

/** What the pairing sheet is showing. */
sealed interface PairingStep {
    data class Waiting(val secondsLeft: Int) : PairingStep
    data class Code(val code: String, val secondsLeft: Int, val confirmed: Boolean) : PairingStep
    data object Success : PairingStep
}

class RemoteActions(
    val onListeningChange: (Boolean) -> Unit = {},
    val onSetup: (SetupItem) -> Unit = {},
    val onStartPairing: () -> Unit = {},
    val onCancelPairing: () -> Unit = {},
    val onConfirmPairing: () -> Unit = {},
    val onUnpair: () -> Unit = {},
    val onClearSlot: (Int) -> Unit = {},
    val onPickSlot: (Int, String) -> Unit = { _, _ -> },
    /** Runs a command locally and returns its result; called off the main thread. */
    val runCommand: suspend (Command, ByteArray) -> Status = { _, _ -> Status.SUCCESS },
    val loadApps: suspend () -> List<AppCatalog.App> = { emptyList() },
)

internal fun statusTextRes(s: Status): Int = when (s) {
    Status.SUCCESS -> R.string.remote_res_ok
    Status.ACCESSIBILITY_DISABLED -> R.string.remote_res_a11y
    Status.NOTHING_TO_CLOSE -> R.string.remote_res_nothing_to_close
    Status.SLOT_EMPTY -> R.string.remote_res_slot_empty
    Status.APP_START_FAILED -> R.string.remote_res_start_failed
    else -> R.string.remote_res_failed
}

/** Pairing UI state to the sheet step; [secondsLeft] is computed by the caller from the deadline. */
internal fun pairingStep(p: RemoteState.PairingUi, secondsLeft: Int): PairingStep? = when (p) {
    RemoteState.PairingUi.Off -> null
    is RemoteState.PairingUi.Waiting -> PairingStep.Waiting(secondsLeft)
    is RemoteState.PairingUi.Code -> PairingStep.Code(p.code.chunked(3).joinToString(" "), secondsLeft, p.confirmed)
}
