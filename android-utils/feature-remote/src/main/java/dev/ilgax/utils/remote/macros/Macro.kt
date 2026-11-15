package dev.ilgax.utils.remote.macros

import dev.ilgax.tabremote.protocol.MacroItem
import dev.ilgax.tabremote.protocol.MacroList
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.core.action.ParamValues

/** One step of a macro: which action, with its parameter values as strings (see ParamSpec). */
data class MacroStep(val actionId: String, val params: Map<String, String> = emptyMap())

/**
 * A user-defined sequence of steps. [id] is assigned by the store, unique and never reused; 0 means "not saved yet".
 * Stops at the first failing step when [stopOnFailure] is set (the default).
 */
data class Macro(val id: Int, val name: String, val steps: List<MacroStep>, val stopOnFailure: Boolean = true) {
    fun toItem() = MacroItem(id, MacroList.cleanName(name))
}

/** Why a macro cannot be saved or run as it is. */
sealed interface MacroIssue {
    data object NameBlank : MacroIssue
    data object NameTooLong : MacroIssue
    data object NoSteps : MacroIssue
    data class WaitTooLong(val totalMs: Long) : MacroIssue
    data class UnknownAction(val index: Int, val actionId: String) : MacroIssue
    /** Step [index] (0-based) has a missing or out-of-range parameter, for example no app chosen. */
    data class BadParams(val index: Int, val keys: List<String>) : MacroIssue
}

object MacroRules {
    const val MAX_NAME = MacroList.MAX_NAME
    const val MAX_TOTAL_WAIT_MS = dev.ilgax.utils.core.action.CoreActions.MAX_TOTAL_WAIT_MS

    /** Sum of every step's wait. Unknown actions count as zero (they are reported separately). */
    fun totalWaitMs(macro: Macro, registry: ActionRegistry): Long = macro.steps.sumOf { step ->
        val action = registry.find(step.actionId) ?: return@sumOf 0L
        action.waitMs(ParamValues.of(action.params, step.params))
    }

    /** Everything wrong with [macro]; empty means it can be saved. [NoSteps] is only reported when [requireSteps]. */
    fun validate(macro: Macro, registry: ActionRegistry, requireSteps: Boolean = false): List<MacroIssue> {
        val issues = ArrayList<MacroIssue>()
        if (macro.name.isBlank()) issues += MacroIssue.NameBlank
        else if (macro.name.codePointCount(0, macro.name.length) > MAX_NAME) issues += MacroIssue.NameTooLong
        if (requireSteps && macro.steps.isEmpty()) issues += MacroIssue.NoSteps
        macro.steps.forEachIndexed { i, step ->
            val action = registry.find(step.actionId)
            if (action == null) {
                issues += MacroIssue.UnknownAction(i, step.actionId)
            } else {
                val bad = ParamValues.problems(action.params, step.params).map { it.first.key }
                if (bad.isNotEmpty()) issues += MacroIssue.BadParams(i, bad)
            }
        }
        val total = totalWaitMs(macro, registry)
        if (total > MAX_TOTAL_WAIT_MS) issues += MacroIssue.WaitTooLong(total)
        return issues
    }
}
