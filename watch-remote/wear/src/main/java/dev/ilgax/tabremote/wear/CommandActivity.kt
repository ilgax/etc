package dev.ilgax.tabremote.wear

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Protocol

/** Sends one command immediately and shows a tiny status. Launched by the tile. */
class CommandActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Exported for the tile host: only a fixed set of commands is accepted, with validated args.
        val cmd = Command.fromId(intent.getIntExtra(EXTRA_CMD, -1))
        if (cmd == null || cmd !in ALLOWED) return finish()
        val slotCommand = cmd == Command.OPEN_APP || cmd == Command.SWITCH_APP
        val arg = intent.getIntExtra(EXTRA_ARG, -1)
        if (slotCommand && arg !in 0 until Protocol.APP_SLOTS) return finish()
        val args = if (slotCommand) byteArrayOf(arg.toByte()) else ByteArray(0)
        val label = if (slotCommand) WatchPrefs(this).slotLabels[arg].ifEmpty { null } else null

        val spinner = ProgressBar(this).apply { isIndeterminate = true }
        val text = TextView(this).apply { textSize = 15f; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(0xCC000000.toInt())
            addView(spinner, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(text)
        }
        setContentView(
            FrameLayout(this).apply {
                addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            },
        )

        RemoteClient.get(this).send(cmd, args) { result ->
            if (isDestroyed) return@send
            spinner.visibility = android.view.View.GONE
            text.text = ResultText.describe(this, result, cmd, label)
            if (result == Result.SUCCESS) Haptics.success(this) else Haptics.failure(this)
            main.postDelayed({ finish() }, 1000)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_ARG = "arg"
        private val ALLOWED = Command.entries.toSet() - setOf(Command.PING, Command.SYNC_APPS, Command.HELLO, Command.GET_OFFER, Command.UPDATE_STATE, Command.RUN_MACRO, Command.SYNC_MACROS)
    }
}
