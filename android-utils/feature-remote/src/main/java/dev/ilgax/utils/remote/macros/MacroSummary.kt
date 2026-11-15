package dev.ilgax.utils.remote.macros

import android.content.Context
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.core.action.ParamValues

object MacroSummary {
    private const val SHOWN = 4

    /** "Play/pause · Close app · Lock" (the first few steps, then an ellipsis). */
    fun of(context: Context, registry: ActionRegistry, macro: Macro): String {
        val parts = macro.steps.take(SHOWN).map { step ->
            val action = registry.find(step.actionId) ?: return@map step.actionId
            action.summary(context, ParamValues.of(action.params, step.params))
        }
        return parts.joinToString(" · ") + if (macro.steps.size > SHOWN) " …" else ""
    }
}
