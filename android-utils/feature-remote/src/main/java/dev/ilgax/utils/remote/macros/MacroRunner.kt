package dev.ilgax.utils.remote.macros

import android.content.Context
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.core.action.ActionType
import dev.ilgax.utils.core.action.ParamValues
import dev.ilgax.utils.core.action.StepResult
import dev.ilgax.utils.remote.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

sealed interface TriggerResult {
    data object Started : TriggerResult
    /** Another macro is running; nothing was started. */
    data object Busy : TriggerResult
    class Invalid(val issues: List<MacroIssue>) : TriggerResult
}

/** The macro that is running right now, for the UI. [step] is 0-based. */
data class RunningMacro(val macroId: Int, val name: String, val step: Int, val total: Int)

/**
 * Runs one macro at a time, step by step, on [scope]. Returns from [trigger] immediately; the run continues in the
 * background and ends up in [log]. [onFinished] gets every finished run (the engine posts the failure notification there).
 */
class MacroRunner(
    private val context: Context,
    private val registry: ActionRegistry,
    private val scope: CoroutineScope,
    private val log: RunLog,
    /** Resolves a step's UiText reason for the log. */
    private val text: (UiText) -> String,
    /** A short phrase for a step in the log ("Brightness 40%"). */
    private val summarize: (ActionType, ParamValues) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val onFinished: (RunRecord) -> Unit = {},
) {
    private val busy = AtomicBoolean(false)
    private val _running = MutableStateFlow<RunningMacro?>(null)
    val running: StateFlow<RunningMacro?> = _running.asStateFlow()

    /** [startDelayMs] lets a local test run give the user time to switch to the app a step acts on. */
    fun trigger(macro: Macro, origin: Origin, startDelayMs: Long = 0): TriggerResult {
        val issues = MacroRules.validate(macro, registry, requireSteps = true)
        if (issues.isNotEmpty()) return TriggerResult.Invalid(issues)
        if (!busy.compareAndSet(false, true)) return TriggerResult.Busy
        _running.value = RunningMacro(macro.id, macro.name, 0, macro.steps.size)
        scope.launch {
            try {
                if (startDelayMs > 0) delay(startDelayMs)
                val record = execute(macro, origin)
                log.add(record)
                try { onFinished(record) } catch (_: Exception) {}
            } finally {
                _running.value = null
                busy.set(false)
            }
        }
        return TriggerResult.Started
    }

    private suspend fun execute(macro: Macro, origin: Origin): RunRecord {
        val startedAt = now()
        val records = ArrayList<StepRecord>()
        var failedStep = 0
        var stopped = false
        for ((i, step) in macro.steps.withIndex()) {
            val action = registry.find(step.actionId)!! // validated by trigger
            val values = ParamValues.of(action.params, step.params)
            val summary = summarize(action, values)
            if (stopped) {
                records += StepRecord(i, step.actionId, summary, StepStatus.NotRun, null, 0)
                continue
            }
            _running.value = RunningMacro(macro.id, macro.name, i, macro.steps.size)
            val t0 = now()
            val result: StepResult = try {
                withTimeoutOrNull(action.waitMs(values) + STEP_TIMEOUT_MS) { action.run(context, values) }
                    ?: StepResult.Failed(UiText.Res(R.string.macro_step_timed_out))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                StepResult.Failed(UiText.Res(R.string.macro_step_crashed, listOf(e.javaClass.simpleName)))
            }
            val ms = now() - t0
            when (result) {
                StepResult.Ok -> records += StepRecord(i, step.actionId, summary, StepStatus.Ok, null, ms)
                is StepResult.Skipped -> records += StepRecord(i, step.actionId, summary, StepStatus.Skipped, text(result.reason), ms)
                is StepResult.Failed -> {
                    records += StepRecord(i, step.actionId, summary, StepStatus.Failed, text(result.reason), ms)
                    if (failedStep == 0) failedStep = i + 1
                    if (macro.stopOnFailure) stopped = true
                }
            }
        }
        return RunRecord(startedAt, macro.id, macro.name, origin, failedStep, records)
    }

    companion object {
        /** Added to a step's own wait: nothing but a hung binder call should take longer. */
        const val STEP_TIMEOUT_MS = 20_000L
    }
}
