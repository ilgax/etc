package dev.ilgax.utils

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import dev.ilgax.utils.core.ModuleRegistry
import dev.ilgax.utils.core.ModuleShortcut
import dev.ilgax.utils.core.shizuku.ShizukuState
import dev.ilgax.utils.hotspot.HotspotModule
import dev.ilgax.utils.remote.RemoteModule
import dev.ilgax.utils.speaker.SpeakerModule

class UtilsApplication : Application() {
    /** The one place modules are registered. A new module is a new library plus one line here. */
    lateinit var registry: ModuleRegistry
        private set

    override fun onCreate() {
        super.onCreate()
        ShizukuState.init(this)
        registry = ModuleRegistry.of(
            SpeakerModule(this),
            HotspotModule(this),
            RemoteModule(this),
        )
        publishShortcuts(registry.modules.mapNotNull { it.shortcut })
    }

    /** Dynamic launcher shortcuts, one per module that offers one. */
    private fun publishShortcuts(shortcuts: List<ModuleShortcut>) {
        try {
            val manager = getSystemService(ShortcutManager::class.java) ?: return
            manager.dynamicShortcuts = shortcuts.map { s ->
                ShortcutInfo.Builder(this, s.id)
                    .setShortLabel(s.shortLabel)
                    .setLongLabel(s.longLabel)
                    .setIcon(shortcutIcon(this, s.iconRes))
                    .setIntent(Intent(Intent.ACTION_VIEW).setClassName(packageName, s.activityClass.name))
                    .build()
            }
        } catch (t: Throwable) {
            // Shortcuts are a convenience; never block app start on them.
        }
    }

    /** White glyph on the launcher-icon blue, as an adaptive bitmap so it works on any launcher shape. */
    private fun shortcutIcon(context: Context, iconRes: Int): Icon {
        val size = 216
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.ic_launcher_background))
        ContextCompat.getDrawable(context, iconRes)?.mutate()?.let {
            it.setTint(Color.WHITE)
            it.setBounds(60, 60, size - 60, size - 60)
            it.draw(canvas)
        }
        return Icon.createWithAdaptiveBitmap(bitmap)
    }
}
