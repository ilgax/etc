package dev.ilgax.speakerkillswitch

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private lateinit var shizukuStatus: TextView
    private lateinit var grant: Button
    private lateinit var mainSwitch: MaterialSwitch
    private lateinit var pauseSwitch: MaterialSwitch
    private lateinit var diag: TextView
    private var lastDiag = ""
    private var resumed = false

    private val permListener = Shizuku.OnRequestPermissionResultListener { _, _ -> handler.post { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener {
        SpeakerController.retryPendingRelease(this)
        handler.post { refresh() }
    }
    private val deadListener = Shizuku.OnBinderDeadListener { handler.post { refresh() } }

    private val ticker = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            handler.postDelayed(this, 2000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        shizukuStatus = findViewById(R.id.shizukuStatus)
        grant = findViewById(R.id.grantButton)
        mainSwitch = findViewById(R.id.mainSwitch)
        pauseSwitch = findViewById(R.id.pauseSwitch)
        diag = findViewById(R.id.diagnostics)

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListener(binderListener)
        Shizuku.addBinderDeadListener(deadListener)

        grant.setOnClickListener {
            try { Shizuku.requestPermission(1001) } catch (t: Throwable) {
                SpeakerController.error(this, "requestPermission", t)
            }
        }
        mainSwitch.isChecked = SpeakerController.isEnabled(this)
        mainSwitch.setOnCheckedChangeListener { _, on ->
            if (on && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            SpeakerController.setEnabled(this, on)
            handler.postDelayed({ refresh() }, 400)
        }
        pauseSwitch.isChecked = SpeakerController.pauseOnDisconnect(this)
        pauseSwitch.setOnCheckedChangeListener { _, v -> SpeakerController.setPauseOnDisconnect(this, v) }
        findViewById<Button>(R.id.reapplyButton).setOnClickListener {
            SpeakerController.log(this, "manual re-apply")
            SpeakerController.enforce(this, "manual", true)
            handler.postDelayed({ refresh() }, 600)
        }
        findViewById<Button>(R.id.copyButton).setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("diagnostics", lastDiag))
            Toast.makeText(this, "Diagnostics copied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        SpeakerController.retryPendingRelease(this)
        mainSwitch.isChecked = SpeakerController.isEnabled(this)
        ticker.run()
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    private fun refresh() {
        val state = SpeakerController.shizukuState(this)
        shizukuStatus.text = when (state) {
            ShizukuState.NOT_INSTALLED -> "Shizuku: not installed"
            ShizukuState.NOT_RUNNING -> "Shizuku: installed but not running (start it, e.g. via wireless debugging)"
            ShizukuState.NEEDS_PERMISSION -> "Shizuku: running, permission needed"
            ShizukuState.READY -> "Shizuku: ready"
        }
        grant.visibility = if (state == ShizukuState.NEEDS_PERMISSION) android.view.View.VISIBLE else android.view.View.GONE
        bg.execute {
            val text = SpeakerController.diagnostics(this)
            handler.post { lastDiag = text; diag.text = text }
        }
    }
}
