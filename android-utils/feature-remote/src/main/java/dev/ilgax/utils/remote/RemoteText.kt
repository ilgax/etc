package dev.ilgax.utils.remote

import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.remote.RemoteState.Service

/** Pure text selection for the remote module. */
internal object RemoteText {
    fun isListening(service: Service) = service == Service.RUNNING || service == Service.BLUETOOTH_OFF

    fun cardMode(service: Service) = if (isListening(service)) CardMode.On else CardMode.Off

    fun cardStatus(service: Service, paired: Boolean, bluetoothPermission: Boolean): UiText.Res = UiText.Res(
        when (service) {
            Service.RUNNING -> if (paired) R.string.remote_card_listening_paired else R.string.remote_card_listening_unpaired
            Service.BLUETOOTH_OFF -> R.string.remote_card_bluetooth_off
            Service.NO_PERMISSION -> R.string.remote_card_permission
            Service.ERROR -> R.string.remote_card_error
            Service.STOPPED -> when {
                !bluetoothPermission -> R.string.remote_card_permission
                !paired -> R.string.remote_card_not_paired
                else -> R.string.remote_card_off
            }
        },
    )

    fun headline(service: Service, bluetoothPermission: Boolean) = UiText.Res(
        when (service) {
            Service.RUNNING -> R.string.remote_headline_listening
            Service.BLUETOOTH_OFF -> R.string.remote_headline_bluetooth_off
            Service.NO_PERMISSION -> R.string.remote_headline_permission
            Service.ERROR -> R.string.remote_headline_error
            Service.STOPPED -> if (bluetoothPermission) R.string.remote_headline_off else R.string.remote_headline_permission
        },
    )

    fun supporting(service: Service, paired: Boolean, bluetoothPermission: Boolean, detail: String?): UiText = when (service) {
        Service.RUNNING ->
            UiText.Res(if (paired) R.string.remote_supporting_listening_paired else R.string.remote_supporting_listening_unpaired)
        Service.BLUETOOTH_OFF -> UiText.Res(R.string.remote_supporting_bluetooth_off)
        Service.NO_PERMISSION -> UiText.Res(R.string.remote_supporting_permission)
        Service.ERROR -> UiText.Res(R.string.remote_supporting_error, listOf(detail ?: ""))
        Service.STOPPED ->
            UiText.Res(if (bluetoothPermission) R.string.remote_supporting_off else R.string.remote_supporting_permission)
    }
}
