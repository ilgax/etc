package dev.ilgax.utils.core

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible activity behind a launcher shortcut: runs [performToggle], toasts the result, finishes.
 * Subclasses are declared in their module's manifest with `Theme.Utils.Translucent`.
 */
abstract class ToggleTrampolineActivity : ComponentActivity() {
    /** Does the toggle (off the main thread) and returns the message to toast. */
    protected abstract suspend fun performToggle(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val message = try {
                withContext(Dispatchers.Default) { performToggle() }
            } catch (t: Throwable) {
                getString(R.string.toggle_failed, t.message ?: t.javaClass.simpleName)
            }
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
