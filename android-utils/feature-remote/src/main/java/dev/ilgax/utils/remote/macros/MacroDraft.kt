package dev.ilgax.utils.remote.macros

/** A step while it is being edited; [uid] keeps list items stable across reordering. */
data class StepDraft(val uid: Long, val actionId: String, val params: Map<String, String> = emptyMap())

/** The editor's working copy of a macro. */
data class MacroDraft(val id: Int, val name: String, val stopOnFailure: Boolean, val steps: List<StepDraft>) {
    fun toMacro() = Macro(id, name.trim(), steps.map { MacroStep(it.actionId, it.params) }, stopOnFailure)

    companion object {
        private var counter = 0L
        fun nextUid(): Long = synchronized(this) { ++counter }

        fun of(m: Macro) = MacroDraft(m.id, m.name, m.stopOnFailure, m.steps.map { StepDraft(nextUid(), it.actionId, it.params) })
        fun blank() = MacroDraft(0, "", true, emptyList())
    }
}
