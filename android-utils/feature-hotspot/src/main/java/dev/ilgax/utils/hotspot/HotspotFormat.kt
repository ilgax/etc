package dev.ilgax.utils.hotspot

import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.shizuku.ShizukuStatus

/** WifiManager.WIFI_AP_STATE_* values reported by the service. */
object ApState {
    const val UNKNOWN = -1
    const val DISABLING = 10
    const val DISABLED = 11
    const val ENABLING = 12
    const val ENABLED = 13
    const val FAILED = 14
}

enum class HotspotPhase { Unknown, Off, TurningOn, On, TurningOff, Failed }

fun phaseOf(apState: Int): HotspotPhase = when (apState) {
    ApState.DISABLED -> HotspotPhase.Off
    ApState.ENABLING -> HotspotPhase.TurningOn
    ApState.ENABLED -> HotspotPhase.On
    ApState.DISABLING -> HotspotPhase.TurningOff
    ApState.FAILED -> HotspotPhase.Failed
    else -> HotspotPhase.Unknown
}

/** SoftApConfiguration.SECURITY_TYPE_* grouped for display. */
enum class SecurityKind(val labelRes: Int) {
    Open(R.string.hotspot_security_open),
    Wpa2(R.string.hotspot_security_wpa2),
    Wpa2Wpa3(R.string.hotspot_security_wpa2_wpa3),
    Wpa3(R.string.hotspot_security_wpa3),
    Other(R.string.hotspot_security_other),
    Unknown(R.string.hotspot_value_unknown),
}

fun securityKindOf(type: Int): SecurityKind = when (type) {
    0 -> SecurityKind.Open
    1 -> SecurityKind.Wpa2
    2 -> SecurityKind.Wpa2Wpa3
    3 -> SecurityKind.Wpa3
    in 4..Int.MAX_VALUE -> SecurityKind.Other
    else -> SecurityKind.Unknown
}

private val BAND_NAMES = listOf("2.4 GHz", "5 GHz", "6 GHz", "60 GHz")

/** "2.4 GHz + 5 GHz" from the band bit mask; empty when unknown. */
fun bandsLabel(mask: Int): String =
    BAND_NAMES.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.joinToString(" + ")

/** Everything the hotspot screen and card show, already in plain Kotlin types. */
data class HotspotUiState(
    val shizuku: ShizukuStatus = ShizukuStatus.Ready,
    val apState: Int = ApState.UNKNOWN,
    val busy: Boolean = false,
    val ssid: String? = null,
    val hidden: Boolean = false,
    val securityType: Int = -1,
    val passphrase: String? = null,
    val bands: Int = 0,
    val clientCount: Int = -1,
    val upstreamSsid: String? = null,
    /** MHz; 0 unknown/not connected, -1 Wi-Fi off. */
    val upstreamFrequency: Int = 0,
    /** Raw error name from the service or controller, null when fine. */
    val error: String? = null,
    val diagnostics: String = "",
) {
    val phase: HotspotPhase get() = phaseOf(apState)
    val security: SecurityKind get() = securityKindOf(securityType)
    val isOpenNetwork: Boolean get() = security == SecurityKind.Open
    val isOn: Boolean get() = phase == HotspotPhase.On
    val shizukuReady: Boolean get() = shizuku == ShizukuStatus.Ready
}

/** Pure mapping from [HotspotUiState] to what the UI says. */
object HotspotText {
    fun cardMode(s: HotspotUiState): CardMode = when {
        !s.shizukuReady -> CardMode.Unavailable
        s.busy || s.phase == HotspotPhase.TurningOn || s.phase == HotspotPhase.TurningOff -> CardMode.Busy
        s.phase == HotspotPhase.On -> CardMode.On
        else -> CardMode.Off
    }

    fun cardStatus(s: HotspotUiState): UiText = when {
        !s.shizukuReady -> shizukuNeeded(s.shizuku)
        s.phase == HotspotPhase.TurningOn -> UiText.Res(R.string.hotspot_card_turning_on)
        s.phase == HotspotPhase.TurningOff -> UiText.Res(R.string.hotspot_card_turning_off)
        s.busy -> UiText.Res(R.string.hotspot_card_working)
        s.phase == HotspotPhase.On -> onStatus(s)
        s.error != null -> UiText.Res(R.string.hotspot_card_failed)
        else -> UiText.Res(R.string.hotspot_card_off)
    }

    private fun onStatus(s: HotspotUiState): UiText = when {
        s.ssid == null -> UiText.Res(R.string.hotspot_card_on)
        s.clientCount >= 0 ->
            UiText.Plural(R.plurals.hotspot_card_on_devices, s.clientCount, listOf(s.ssid, s.clientCount))
        else -> UiText.Res(R.string.hotspot_card_on_named, listOf(s.ssid))
    }

    fun shizukuNeeded(status: ShizukuStatus): UiText = UiText.Res(
        when (status) {
            ShizukuStatus.NotInstalled -> R.string.hotspot_needs_shizuku_install
            ShizukuStatus.NotRunning -> R.string.hotspot_needs_shizuku_start
            else -> R.string.hotspot_needs_shizuku_permission
        },
    )

    fun headline(s: HotspotUiState): UiText = UiText.Res(
        when {
            !s.shizukuReady -> R.string.hotspot_headline_unavailable
            s.phase == HotspotPhase.TurningOn -> R.string.hotspot_headline_turning_on
            s.phase == HotspotPhase.TurningOff -> R.string.hotspot_headline_turning_off
            s.phase == HotspotPhase.On -> R.string.hotspot_headline_on
            s.phase == HotspotPhase.Failed || s.error != null -> R.string.hotspot_headline_failed
            else -> R.string.hotspot_headline_off
        },
    )

    fun supporting(s: HotspotUiState): UiText = when {
        !s.shizukuReady -> shizukuNeeded(s.shizuku)
        s.phase == HotspotPhase.On -> upstreamSummary(s)
        s.error != null -> UiText.Res(R.string.hotspot_supporting_failed)
        else -> UiText.Res(R.string.hotspot_supporting_off)
    }

    /** "Sharing NAVONA · 5200 MHz", or why nothing is being shared. */
    fun upstreamSummary(s: HotspotUiState): UiText = when {
        s.upstreamSsid != null -> UiText.Res(R.string.hotspot_supporting_sharing, listOf(upstreamValue(s)))
        else -> UiText.Res(R.string.hotspot_supporting_on_idle)
    }

    /** Value of the "Shared Wi-Fi" row. */
    fun upstreamValue(s: HotspotUiState): String = when {
        s.upstreamSsid != null -> "${s.upstreamSsid} · ${s.upstreamFrequency} MHz"
        else -> ""
    }

    fun upstreamRow(s: HotspotUiState): UiText = when {
        s.upstreamSsid != null -> UiText.Res(R.string.hotspot_upstream_value, listOf(s.upstreamSsid, s.upstreamFrequency))
        s.upstreamFrequency < 0 -> UiText.Res(R.string.hotspot_upstream_wifi_off)
        else -> UiText.Res(R.string.hotspot_upstream_none)
    }

    fun clientsRow(s: HotspotUiState): UiText =
        if (s.clientCount < 0) UiText.Res(R.string.hotspot_value_unknown)
        else UiText.Plural(R.plurals.hotspot_devices, s.clientCount, listOf(s.clientCount))

    fun bandRow(s: HotspotUiState): String = bandsLabel(s.bands)

    /** Result line for the shortcut toast. */
    fun toast(s: HotspotUiState): UiText = when {
        !s.shizukuReady -> shizukuNeeded(s.shizuku)
        s.phase == HotspotPhase.On -> UiText.Res(R.string.hotspot_toast_on)
        s.error != null -> UiText.Res(R.string.hotspot_toast_failed, listOf(s.error))
        s.phase == HotspotPhase.Off -> UiText.Res(R.string.hotspot_toast_off)
        else -> UiText.Res(R.string.hotspot_toast_working)
    }
}
