package dev.ilgax.utils.remote.macros

import dev.ilgax.tabremote.protocol.MacroItem
import dev.ilgax.tabremote.protocol.MacroList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The macro list, persisted as JSON in app-private storage. Ids come from a counter that only goes up, so a deleted
 * macro's id is never handed out again (a watch holding an old list cannot run a different macro by mistake).
 * Every change bumps [MacroData.revision], which is mixed into the version the watch compares.
 */
class MacroStore(private val file: File, private val onChanged: () -> Unit = {}) {
    private val _state = MutableStateFlow(MacroData(emptyList(), 1, 0))
    val state: StateFlow<MacroData> = _state.asStateFlow()

    /** False when the file was written by a newer app version: shown but never overwritten. */
    @Volatile var writable = true
        private set

    init {
        load()
    }

    val macros: List<Macro> get() = _state.value.macros

    fun get(id: Int): Macro? = _state.value.macros.firstOrNull { it.id == id }

    /** The list as the watch sees it, and its version, from one consistent state. */
    fun watchView(): Pair<Int, List<MacroItem>> {
        val s = _state.value
        val items = s.macros.map { it.toItem() }
        return MacroList.versionOf(s.revision, items) to items
    }

    fun listVersion(): Int = watchView().first

    /** Saves [macro]; id 0 creates a new one. Returns the stored macro. */
    @Synchronized
    fun save(macro: Macro): Macro {
        check(writable) { "macro file is from a newer version" }
        val s = _state.value
        val stored: Macro
        val list: List<Macro>
        var nextId = s.nextId
        if (macro.id == 0) {
            check(nextId <= MacroList.MAX_ID) { "out of macro ids" }
            stored = macro.copy(id = nextId++)
            list = s.macros + stored
        } else {
            stored = macro
            list = if (s.macros.any { it.id == macro.id }) s.macros.map { if (it.id == macro.id) stored else it } else s.macros + stored
            nextId = maxOf(nextId, macro.id + 1)
        }
        commit(MacroData(list, nextId, s.revision + 1))
        return stored
    }

    @Synchronized
    fun delete(id: Int): Boolean {
        check(writable) { "macro file is from a newer version" }
        val s = _state.value
        if (s.macros.none { it.id == id }) return false
        commit(MacroData(s.macros.filter { it.id != id }, s.nextId, s.revision + 1))
        return true
    }

    @Synchronized
    fun duplicate(id: Int): Macro? {
        val original = get(id) ?: return null
        val suffix = " copy"
        val base = MacroList.cleanName(original.name)
        val room = MacroRules.MAX_NAME - suffix.length
        val trimmed = if (base.codePointCount(0, base.length) > room) base.substring(0, base.offsetByCodePoints(0, room)).trim() else base
        return save(original.copy(id = 0, name = trimmed + suffix))
    }

    private fun commit(data: MacroData) {
        write(data)
        _state.value = data
        onChanged()
    }

    private fun write(data: MacroData) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(MacroJson.encode(data))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun load() {
        if (!file.exists()) return
        when (val d = try { MacroJson.decode(file.readText()) } catch (e: Exception) { MacroDecode.Corrupt }) {
            is MacroDecode.Ok -> _state.value = d.data
            is MacroDecode.Newer -> writable = false
            MacroDecode.Corrupt -> {
                // Keep the unreadable file for inspection and start empty rather than crash on every start.
                runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
            }
        }
    }
}
