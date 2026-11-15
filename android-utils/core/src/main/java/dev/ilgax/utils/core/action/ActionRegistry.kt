package dev.ilgax.utils.core.action

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The set of steps the macro editor and runner know. Each module registers an [ActionProvider] from its init
 * (feature modules never reference each other); the core's own steps are always there.
 */
class ActionRegistry(builtIn: List<ActionProvider> = emptyList()) {
    private val providers = CopyOnWriteArrayList<ActionProvider>()

    init {
        builtIn.forEach(::register)
    }

    /** Adds [provider]; one with the same [ActionProvider.id] is replaced. Two providers may not share an action id. */
    @Synchronized
    fun register(provider: ActionProvider) {
        val others = providers.filter { it.id != provider.id }
        val ids = HashSet<String>()
        for (a in others.flatMap { it.actions } + provider.actions) {
            require(ids.add(a.id)) { "Duplicate action id: ${a.id}" }
        }
        providers.removeAll { it.id == provider.id }
        providers.add(provider)
    }

    fun all(): List<ActionType> = providers.flatMap { it.actions }

    fun find(id: String): ActionType? = all().firstOrNull { it.id == id }

    /** Picker order: groups in their declared order, steps in registration order inside a group. */
    fun grouped(): List<Pair<ActionGroup, List<ActionType>>> {
        val actions = all()
        return ActionGroup.entries.mapNotNull { g -> actions.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }
    }

    companion object {
        /** The process-wide registry. */
        val shared: ActionRegistry by lazy { ActionRegistry(listOf(CoreActions)) }
    }
}

/** Facts about other modules' state that a step's warning needs, without the modules knowing each other. */
object ActionFacts {
    enum class Fact { HotspotActive }

    private val probes = java.util.concurrent.ConcurrentHashMap<Fact, () -> Boolean>()

    fun provide(fact: Fact, probe: () -> Boolean) { probes[fact] = probe }

    fun isTrue(fact: Fact): Boolean = try { probes[fact]?.invoke() == true } catch (_: Throwable) { false }

    internal fun resetForTest() = probes.clear()
}
