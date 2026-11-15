package dev.ilgax.utils.math

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.ModuleCardState
import dev.ilgax.utils.core.ModuleShortcut
import dev.ilgax.utils.core.UtilModule
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** "Equation solver": circle handwritten math, get the answer. The card shows capture readiness and the model. */
class MathModule(context: Context) : UtilModule {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val grabber = ScreenGrabber(app)
    private val recognizer = Recognizer.get(app)
    private val prefs = MathPrefs.get(app)
    private val refresh = MutableStateFlow(0)

    override val id = "math"
    override val title: String = app.getString(R.string.math_title)
    override val summary: String = app.getString(R.string.math_summary)
    override val iconRes = R.drawable.ic_math
    override val quickSwitch = false

    private fun compute(shizuku: ShizukuStatus): ModuleCardState {
        val method = chooseMethod(shizuku == ShizukuStatus.Ready, grabber.accessibilityConnected())
        val model = recognizer.selected()?.name
        return when {
            method == CaptureMethod.None -> ModuleCardState(CardMode.Unavailable, app.getString(R.string.math_card_not_ready))
            model == null -> ModuleCardState(CardMode.On, app.getString(R.string.math_card_no_model))
            method == CaptureMethod.Shizuku -> ModuleCardState(CardMode.On, app.getString(R.string.math_card_ready_shizuku, model))
            else -> ModuleCardState(CardMode.On, app.getString(R.string.math_card_ready_a11y, model))
        }
    }

    override val cardState: StateFlow<ModuleCardState> = combine(
        ShizukuState.status, A11y.service, prefs.selectedModel, refresh,
    ) { shizuku, _, _, _ -> compute(shizuku) }
        .stateIn(scope, SharingStarted.Eagerly, ModuleCardState(CardMode.Unavailable, app.getString(R.string.math_card_not_ready)))

    init {
        A11y.register(MathA11y(app))
        // The Shizuku UserService is a daemon: bind it as soon as Utils starts (and whenever Shizuku becomes ready)
        // if that is the capture path, so the first Solve finds it connected.
        scope.launch {
            combine(ShizukuState.status, A11y.service) { _, _ -> Unit }.collect { grabber.prewarm() }
        }
    }

    override val shortcut = ModuleShortcut(
        id = "solve_equation",
        shortLabel = app.getString(R.string.math_shortcut_short),
        longLabel = app.getString(R.string.math_shortcut_long),
        iconRes = R.drawable.ic_math,
        activityClass = SolveActivity::class.java,
    )

    /** Nothing to switch; the home card has no switch for this module. */
    override suspend fun toggle() = Unit

    override fun onAppVisible(visible: Boolean) {
        if (visible) scope.launch {
            recognizer.refreshModels()
            refresh.value++
        }
    }

    @Composable
    override fun Detail(contentPadding: PaddingValues, modifier: Modifier) = MathDetail(contentPadding, modifier)

    companion object {
        fun launchIntent(context: Context): Intent = Intent(context, SolveActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
