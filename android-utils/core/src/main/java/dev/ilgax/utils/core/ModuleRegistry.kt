package dev.ilgax.utils.core

/** The ordered set of modules the shell shows. Adding a module is one more entry here. */
class ModuleRegistry(modules: List<UtilModule>) {
    val modules: List<UtilModule> = modules.toList()

    init {
        val duplicate = this.modules.groupBy { it.id }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Duplicate module id: ${duplicate?.key}" }
    }

    fun find(id: String?): UtilModule? = id?.let { wanted -> modules.firstOrNull { it.id == wanted } }

    companion object {
        fun of(vararg modules: UtilModule) = ModuleRegistry(modules.toList())
    }
}
