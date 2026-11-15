package dev.ilgax.tabremote.wear

import android.content.Context
import dev.ilgax.tabremote.protocol.AppSlots
import dev.ilgax.tabremote.protocol.MacroItem
import dev.ilgax.tabremote.protocol.Protocol

class WatchPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("tabremote", Context.MODE_PRIVATE)

    var tabletId: ByteArray?
        get() {
            val hex = sp.getString(KEY_ID, null) ?: return null
            if (hex.length != Protocol.TABLET_ID_SIZE * 2) return null
            return try {
                ByteArray(Protocol.TABLET_ID_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            } catch (_: NumberFormatException) {
                null
            }
        }
        set(v) = sp.edit().apply {
            if (v == null) remove(KEY_ID) else putString(KEY_ID, v.joinToString("") { "%02x".format(it) })
        }.apply()

    /** Labels of the tablet's app slots from the last sync; empty string = unset. */
    var slotLabels: List<String>
        get() = AppSlots.decode(sp.getString(KEY_SLOTS, "")?.toByteArray(Charsets.UTF_8))
        set(v) = sp.edit().putString(KEY_SLOTS, String(AppSlots.encode(v), Charsets.UTF_8)).apply()

    /** When on, slot buttons send SWITCH_APP (close the current app, then open) instead of OPEN_APP. */
    var closeFirst: Boolean
        get() = sp.getBoolean(KEY_CLOSE_FIRST, false)
        set(v) = sp.edit().putBoolean(KEY_CLOSE_FIRST, v).apply()

    /** The tablet's macro list from the last sync, shown instantly (also offline). */
    var macros: List<MacroItem>
        get() = (sp.getString(KEY_MACROS, "") ?: "").split('\n').mapNotNull { line ->
            val tab = line.indexOf('\t')
            val id = if (tab > 0) line.substring(0, tab).toIntOrNull() else null
            if (id == null) null else MacroItem(id, line.substring(tab + 1))
        }
        set(v) = sp.edit().putString(KEY_MACROS, v.joinToString("\n") { "${it.id}\t${it.name.replace('\n', ' ').replace('\t', ' ')}" }).apply()

    /** Version of [macros] as the tablet reported it; null = never synced. */
    var macroVersion: Int?
        get() = if (sp.contains(KEY_MACRO_VERSION)) sp.getInt(KEY_MACRO_VERSION, 0) else null
        set(v) = sp.edit().apply { if (v == null) remove(KEY_MACRO_VERSION) else putInt(KEY_MACRO_VERSION, v) }.apply()

    /** True once the tablet answered a macro sync, false if its Utils predates macros, null = not known yet. */
    var macrosSupported: Boolean?
        get() = if (sp.contains(KEY_MACROS_SUPPORTED)) sp.getBoolean(KEY_MACROS_SUPPORTED, false) else null
        set(v) = sp.edit().apply { if (v == null) remove(KEY_MACROS_SUPPORTED) else putBoolean(KEY_MACROS_SUPPORTED, v) }.apply()

    fun clearPairing() = sp.edit().remove(KEY_ID).remove(KEY_SLOTS).remove(KEY_MACROS).remove(KEY_MACRO_VERSION).remove(KEY_MACROS_SUPPORTED).apply()

    private companion object {
        const val KEY_ID = "tablet_id"
        const val KEY_SLOTS = "slots"
        const val KEY_CLOSE_FIRST = "close_first"
        const val KEY_MACROS = "macros"
        const val KEY_MACRO_VERSION = "macro_version"
        const val KEY_MACROS_SUPPORTED = "macros_supported"
    }
}
