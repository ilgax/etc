package dev.ilgax.tabremote.tablet

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** In-process state shared between the service and the UI. Listeners are invoked on the main thread. */
object TabletState {
    enum class Service { STOPPED, RUNNING, NO_PERMISSION, BLUETOOTH_OFF, ERROR }

    sealed interface PairingUi {
        data object Off : PairingUi
        data class Waiting(val deadline: Long) : PairingUi
        data class Code(val code: String, val deadline: Long, val confirmed: Boolean) : PairingUi
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var service = Service.STOPPED
        set(v) { field = v; changed() }

    @Volatile var serviceDetail: String? = null

    @Volatile var pairing: PairingUi = PairingUi.Off
        set(v) { field = v; changed() }

    @Volatile var ringing = false
        set(v) { field = v; changed() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun changed() {
        main.post { listeners.forEach { it() } }
    }
}
