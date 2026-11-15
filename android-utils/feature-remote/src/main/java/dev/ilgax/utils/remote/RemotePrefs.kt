package dev.ilgax.utils.remote

import android.content.Context
import dev.ilgax.tabremote.protocol.Protocol
import java.security.SecureRandom

class RemotePrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("watch_remote", Context.MODE_PRIVATE)

    val tabletId: ByteArray
        get() {
            val hex = sp.getString(KEY_ID, null)
            if (hex != null && hex.length == Protocol.TABLET_ID_SIZE * 2) {
                return ByteArray(Protocol.TABLET_ID_SIZE) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            val id = ByteArray(Protocol.TABLET_ID_SIZE).also { SecureRandom().nextBytes(it) }
            sp.edit().putString(KEY_ID, id.joinToString("") { "%02x".format(it) }).apply()
            return id
        }

    var paired: Boolean
        get() = sp.getBoolean(KEY_PAIRED, false)
        set(v) = sp.edit().putBoolean(KEY_PAIRED, v).apply()

    var runInBackground: Boolean
        get() = sp.getBoolean(KEY_BG, false)
        set(v) = sp.edit().putBoolean(KEY_BG, v).apply()

    /** versionCode the watch last reported with HELLO; -1 = never (old watch build, or not seen yet). */
    var watchVersion: Int
        get() = sp.getInt(KEY_WATCH_VERSION, -1)
        set(v) = sp.edit().putInt(KEY_WATCH_VERSION, v).apply()

    fun slot(i: Int): String? = sp.getString("slot_$i", null)

    fun setSlot(i: Int, pkg: String?) {
        sp.edit().apply { if (pkg == null) remove("slot_$i") else putString("slot_$i", pkg) }.apply()
    }

    private companion object {
        const val KEY_ID = "tablet_id"
        const val KEY_PAIRED = "paired"
        const val KEY_BG = "run_in_background"
        const val KEY_WATCH_VERSION = "watch_version"
    }
}
