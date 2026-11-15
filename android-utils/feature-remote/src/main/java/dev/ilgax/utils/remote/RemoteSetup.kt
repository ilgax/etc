package dev.ilgax.utils.remote

/** The five things the listener needs before it can work. Pure so the checklist logic is testable. */
enum class SetupItem { BluetoothPermission, Notifications, BluetoothOn, Accessibility, Battery }

data class SetupChecks(
    val bluetoothPermission: Boolean,
    val notifications: Boolean,
    val bluetoothOn: Boolean,
    val accessibility: Boolean,
    val battery: Boolean,
) {
    fun isDone(item: SetupItem): Boolean = when (item) {
        SetupItem.BluetoothPermission -> bluetoothPermission
        SetupItem.Notifications -> notifications
        SetupItem.BluetoothOn -> bluetoothOn
        SetupItem.Accessibility -> accessibility
        SetupItem.Battery -> battery
    }

    val pending: List<SetupItem> get() = SetupItem.entries.filterNot(::isDone)
    val allSet: Boolean get() = pending.isEmpty()
}
