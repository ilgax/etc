package dev.ilgax.utils.remote

import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Status
import dev.ilgax.utils.remote.macros.MacroUi
import dev.ilgax.utils.remote.macros.RunRecord
import dev.ilgax.utils.remote.macros.RunningMacro

data class SlotUi(val label: String, val packageName: String)

/** The "Watch app" section. Null in [RemoteUiState] when this build bundles no watch APK. */
data class WatchAppUi(
    val bundledName: String,
    val bundledCode: Int,
    /** versionCode the watch reported over the authenticated channel; null = never (old watch build). */
    val reportedCode: Int?,
    val update: WatchUpdateState = WatchUpdateState.Idle,
) {
    /** The bundled build is newer than what the watch runs. A watch that never reported is not known to be older. */
    val updateAvailable: Boolean get() = reportedCode != null && bundledCode > reportedCode
    val upToDate: Boolean get() = reportedCode != null && bundledCode <= reportedCode
    val active: Boolean get() = update !is WatchUpdateState.Idle && update !is WatchUpdateState.TimedOut && update !is WatchUpdateState.Failed
}

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
    val watchApp: WatchAppUi? = null,
    val macros: List<MacroUi> = emptyList(),
    /** Newest first. */
    val runs: List<RunRecord> = emptyList(),
    val runningMacro: RunningMacro? = null,
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
    val onUpdateWatch: () -> Unit = {},
    val onCancelUpdate: () -> Unit = {},
    val onRunMacro: (Int) -> Unit = {},
    val onAddMacro: () -> Unit = {},
    val onEditMacro: (Int) -> Unit = {},
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

/** Status line of the "Watch app" section. */
internal fun WatchAppUi.statusRes(): Int = when (val u = update) {
    WatchUpdateState.Idle -> when {
        updateAvailable -> R.string.remote_app_st_available
        upToDate -> R.string.remote_app_st_current
        else -> R.string.remote_app_st_unknown
    }
    is WatchUpdateState.Waiting -> R.string.remote_app_st_waiting
    is WatchUpdateState.Downloading -> R.string.remote_app_st_downloading
    WatchUpdateState.Installing -> R.string.remote_app_st_installing
    WatchUpdateState.TimedOut -> R.string.remote_app_st_timed_out
    is WatchUpdateState.Failed -> when (u.failure) {
        dev.ilgax.tabremote.protocol.UpdateReport.Failure.NO_WIFI -> R.string.remote_app_st_no_wifi
        dev.ilgax.tabremote.protocol.UpdateReport.Failure.DOWNLOAD -> R.string.remote_app_st_download
        dev.ilgax.tabremote.protocol.UpdateReport.Failure.HASH_MISMATCH -> R.string.remote_app_st_hash
        dev.ilgax.tabremote.protocol.UpdateReport.Failure.INSTALL_BLOCKED -> R.string.remote_app_st_blocked
        dev.ilgax.tabremote.protocol.UpdateReport.Failure.INSTALL_FAILED -> R.string.remote_app_st_install_failed
        dev.ilgax.tabremote.protocol.UpdateReport.Failure.UNKNOWN -> R.string.remote_app_st_failed
    }
}
