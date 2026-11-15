package dev.ilgax.utils.remote.macros

import android.content.Context
import android.content.ContextWrapper
import dev.ilgax.utils.core.UiText
import dev.ilgax.utils.core.action.ActionGroup
import dev.ilgax.utils.core.action.ActionProvider
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.core.action.ActionType
import dev.ilgax.utils.core.action.ParamSpec
import dev.ilgax.utils.core.action.SimpleAction
import dev.ilgax.utils.core.action.StepResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MacroRunnerTest {
    private val context: Context = object : ContextWrapper(null) {}
    private val ran = ArrayList<String>()
    private val gate = CompletableDeferred<Unit>()

    private fun action(id: String, result: StepResult = StepResult.Ok, waitMs: Long = 0, params: List<ParamSpec> = emptyList(), body: suspend () -> Unit = {}): ActionType =
        SimpleAction(id, UiText.Res(1), ActionGroup.System, 0, params, waitFn = { waitMs }) { _, _ ->
            ran += id
            body()
            result
        }

    private val registry = ActionRegistry().also {
        it.register(object : ActionProvider {
            override val id = "fake"
            override val actions = listOf(
                action("ok"), action("ok2"),
                action("fail", StepResult.failed(1)),
                action("skip", StepResult.skipped(1)),
                action("boom") { throw IllegalStateException("x") },
                action("slow") { gate.await() },
                action("wait5", waitMs = 5_000) { delay(5_000) },
                action("hang", waitMs = 0) { delay(10 * 60_000L) },
                action("needs_app", params = listOf(ParamSpec.AppPackage("pkg", UiText.Res(1)))),
            )
        })
    }

    private fun macro(vararg ids: String, stop: Boolean = true, id: Int = 1) =
        Macro(id, "M$id", ids.map { MacroStep(it) }, stop)

    private fun TestScope.runner(finished: MutableList<RunRecord> = ArrayList(), log: RunLog = RunLog(null)) = MacroRunner(
        context, registry, CoroutineScope(StandardTestDispatcher(testScheduler)), log,
        text = { "reason" }, summarize = { a, _ -> a.id }, now = { testScheduler.currentTime }, onFinished = { finished += it },
    ).also { this@MacroRunnerTest.lastLog = log }

    private var lastLog = RunLog(null)

    @Test fun runsStepsInOrderAndLogsThem() = runTest {
        val r = runner()
        assertEquals(TriggerResult.Started, r.trigger(macro("ok", "ok2", "skip", "ok"), Origin.Local))
        advanceUntilIdle()
        assertEquals(listOf("ok", "ok2", "skip", "ok"), ran)
        val rec = lastLog.runs.value.single()
        assertTrue(rec.ok)
        assertEquals(listOf(StepStatus.Ok, StepStatus.Ok, StepStatus.Skipped, StepStatus.Ok), rec.steps.map { it.status })
        assertEquals("reason", rec.steps[2].message)
        assertEquals(null, r.running.value)
    }

    @Test fun stopOnFailureStopsAndMarksTheRestNotRun() = runTest {
        val finished = ArrayList<RunRecord>()
        val r = runner(finished)
        r.trigger(macro("ok", "fail", "ok2"), Origin.Watch)
        advanceUntilIdle()
        assertEquals(listOf("ok", "fail"), ran)
        val rec = finished.single()
        assertEquals(2, rec.failedStep)
        assertEquals(listOf(StepStatus.Ok, StepStatus.Failed, StepStatus.NotRun), rec.steps.map { it.status })
        assertEquals(Origin.Watch, rec.origin)
    }

    @Test fun withoutStopOnFailureTheRunContinuesButStillReportsTheFirstFailure() = runTest {
        val r = runner()
        r.trigger(macro("fail", "ok", "boom", "ok2", stop = false), Origin.Local)
        advanceUntilIdle()
        assertEquals(listOf("fail", "ok", "boom", "ok2"), ran)
        val rec = lastLog.runs.value.single()
        assertEquals(1, rec.failedStep)
        assertEquals(listOf(StepStatus.Failed, StepStatus.Ok, StepStatus.Failed, StepStatus.Ok), rec.steps.map { it.status })
    }

    @Test fun anExceptionInAStepIsAFailureNotACrash() = runTest {
        val r = runner()
        r.trigger(macro("boom", "ok"), Origin.Local)
        advanceUntilIdle()
        assertEquals(listOf("boom"), ran)
        assertEquals(1, lastLog.runs.value.single().failedStep)
    }

    @Test fun aSecondTriggerWhileRunningIsBusyAndAfterwardsItWorks() = runTest {
        val r = runner()
        assertEquals(TriggerResult.Started, r.trigger(macro("slow", "ok"), Origin.Watch))
        testScheduler.runCurrent() // now suspended inside "slow" (idle-advancing would hit its timeout)
        assertEquals(1, r.running.value?.macroId)
        assertEquals(TriggerResult.Busy, r.trigger(macro("ok2", id = 2), Origin.Watch))
        assertEquals(TriggerResult.Busy, r.trigger(macro("ok2", id = 1), Origin.Local))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("slow", "ok"), ran) // the rejected macro never ran
        assertEquals(TriggerResult.Started, r.trigger(macro("ok2", id = 2), Origin.Watch))
        advanceUntilIdle()
        assertEquals(listOf("slow", "ok", "ok2"), ran)
    }

    @Test fun triggerReturnsWithoutWaitingForTheRun() = runTest {
        val r = runner()
        assertEquals(TriggerResult.Started, r.trigger(macro("wait5", "ok"), Origin.Watch))
        assertTrue(ran.isEmpty()) // nothing executed yet: the caller (the BLE answer) was not held
        advanceUntilIdle()
        assertEquals(listOf("wait5", "ok"), ran)
    }

    @Test fun startDelayPostponesTheFirstStep() = runTest {
        val r = runner()
        r.trigger(macro("ok"), Origin.Local, startDelayMs = 3_000)
        testScheduler.advanceTimeBy(2_900); testScheduler.runCurrent()
        assertTrue(ran.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("ok"), ran)
    }

    @Test fun invalidMacrosAreRejectedBeforeAnythingRuns() = runTest {
        val r = runner()
        assertTrue((r.trigger(macro(), Origin.Watch) as TriggerResult.Invalid).issues.contains(MacroIssue.NoSteps))
        val unknown = (r.trigger(macro("ok", "nope"), Origin.Watch) as TriggerResult.Invalid).issues
        assertEquals(listOf<MacroIssue>(MacroIssue.UnknownAction(1, "nope")), unknown)
        val bad = (r.trigger(macro("needs_app"), Origin.Watch) as TriggerResult.Invalid).issues
        assertEquals(listOf<MacroIssue>(MacroIssue.BadParams(0, listOf("pkg"))), bad)
        advanceUntilIdle()
        assertTrue(ran.isEmpty())
        // None of them took the "busy" slot.
        assertEquals(TriggerResult.Started, r.trigger(macro("ok"), Origin.Watch))
    }

    @Test fun totalWaitOver60SecondsIsRejected() = runTest {
        val r = runner()
        val ok = macro(*Array(12) { "wait5" }) // exactly 60 s
        assertEquals(60_000L, MacroRules.totalWaitMs(ok, registry))
        assertTrue(MacroRules.validate(ok, registry).isEmpty())
        val tooLong = macro(*Array(13) { "wait5" })
        assertEquals(listOf<MacroIssue>(MacroIssue.WaitTooLong(65_000)), MacroRules.validate(tooLong, registry))
        assertEquals(MacroIssue.WaitTooLong(65_000), (r.trigger(tooLong, Origin.Watch) as TriggerResult.Invalid).issues.single())
        advanceUntilIdle()
        assertTrue(ran.isEmpty())
    }

    @Test fun aHungStepTimesOutAndIsAFailure() = runTest {
        val r = runner()
        r.trigger(macro("hang", "ok"), Origin.Local)
        advanceUntilIdle()
        assertEquals(listOf("hang"), ran)
        assertEquals(1, lastLog.runs.value.single().failedStep)
    }

    @Test fun nameRulesAreValidated() {
        assertEquals(listOf<MacroIssue>(MacroIssue.NameBlank), MacroRules.validate(Macro(1, "  ", emptyList()), registry))
        assertEquals(listOf<MacroIssue>(MacroIssue.NameTooLong), MacroRules.validate(Macro(1, "x".repeat(25), emptyList()), registry))
        assertTrue(MacroRules.validate(Macro(1, "x".repeat(24), emptyList()), registry).isEmpty())
        // 24 code points of emoji are fine even though they are 48 chars.
        assertTrue(MacroRules.validate(Macro(1, "😀".repeat(24), emptyList()), registry).isEmpty())
    }
}
