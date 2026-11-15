package dev.ilgax.utils.remote

import android.content.Context
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ilgax.utils.core.ModuleCardState
import dev.ilgax.utils.core.UtilModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class RemoteModule(context: Context) : UtilModule {
    private val app = context.applicationContext

    override val id = "remote"
    override val title: String = app.getString(R.string.remote_title)
    override val summary: String = app.getString(R.string.remote_summary)
    override val iconRes = R.drawable.ic_watch

    private val _cardState = MutableStateFlow(compute())
    override val cardState: StateFlow<ModuleCardState> = _cardState.asStateFlow()

    init {
        // Lives as long as the process, like the module itself.
        RemoteState.addListener { _cardState.value = compute() }
    }

    private fun compute(): ModuleCardState {
        val service = RemoteState.service
        val status = RemoteText.cardStatus(service, RemoteControl.isPaired(app), RemoteControl.hasBluetoothPermission(app))
        return ModuleCardState(RemoteText.cardMode(service), status.resolve(app))
    }

    override suspend fun toggle() {
        withContext(Dispatchers.Default) {
            val on = !RemoteText.isListening(RemoteState.service)
            if (!RemoteControl.setListening(app, on) && on && RemoteControl.hasBluetoothPermission(app)) {
                RemoteState.serviceDetail = app.getString(R.string.remote_start_refused)
                RemoteState.service = RemoteState.Service.ERROR
            }
        }
    }

    override fun onAppVisible(visible: Boolean) {
        if (visible) _cardState.value = compute()
    }

    @Composable
    override fun Detail(contentPadding: PaddingValues, modifier: Modifier) = RemoteDetail(contentPadding, modifier)
}
