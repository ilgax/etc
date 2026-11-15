package dev.ilgax.tabremote.tablet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.Protocol
import dev.ilgax.tabremote.protocol.Status
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: TabletPrefs
    private lateinit var vault: KeyVault
    private lateinit var executor: CommandExecutor
    private lateinit var content: LinearLayout
    private val main = Handler(Looper.getMainLooper())
    private var pairingText: TextView? = null

    private val stateListener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            updatePairingText()
            if (TabletState.pairing != TabletState.PairingUi.Off) main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TabletPrefs(this)
        vault = KeyVault()
        executor = CommandExecutor(this)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val maxWidth = dp(MAX_WIDTH_DP)
        val frame = FrameLayout(this)
        frame.addView(content, FrameLayout.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(frame, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Targeting API 35 draws edge to edge; keep content clear of the system bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        content.setPadding(dp(16), dp(16), dp(16), dp(24))
    }

    override fun onResume() {
        super.onResume()
        TabletState.addListener(stateListener)
        render()
    }

    override fun onPause() {
        TabletState.removeListener(stateListener)
        main.removeCallbacks(tick)
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    // region rendering

    private fun render() {
        content.removeAllViews()
        pairingText = null
        main.removeCallbacks(tick)

        title(R.string.app_name)
        body(R.string.intro)

        section(R.string.section_status)
        val btOk = RemoteService.hasBluetoothPermissions(this)
        check(btOk, R.string.chk_bt_permission, R.string.btn_grant) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            check(ok, R.string.chk_notifications, R.string.btn_grant) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        }
        check(bluetoothOn(), R.string.chk_bluetooth_on, R.string.btn_enable) {
            startActivitySafe(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
        val a11y = accessibilityEnabled()
        check(a11y, R.string.chk_accessibility, R.string.btn_open_settings) {
            startActivitySafe(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        if (!a11y) note(R.string.a11y_note)
        val battery = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
        check(battery, R.string.chk_battery, R.string.btn_exempt) {
            startActivitySafe(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        }
        val running = TabletState.service == TabletState.Service.RUNNING || TabletState.service == TabletState.Service.BLUETOOTH_OFF
        check(running, R.string.chk_service, if (running) R.string.btn_stop else R.string.btn_start) {
            if (running) {
                prefs.runInBackground = false
                stopService(Intent(this, RemoteService::class.java))
            } else {
                prefs.runInBackground = true
                startService()
            }
        }
        serviceProblem()?.let { note(it) }
        check(prefs.paired && vault.has(), R.string.chk_paired, if (prefs.paired) R.string.btn_unpair else null) { confirmUnpair() }

        renderPairing()
        renderSlots()
        renderTests()
    }

    private fun serviceProblem(): String? = when (TabletState.service) {
        TabletState.Service.NO_PERMISSION -> getString(R.string.svc_no_permission)
        TabletState.Service.BLUETOOTH_OFF -> getString(R.string.svc_bluetooth_off)
        TabletState.Service.ERROR -> getString(R.string.svc_error, TabletState.serviceDetail ?: "")
        else -> null
    }

    private fun renderPairing() {
        section(R.string.section_pairing)
        when (val p = TabletState.pairing) {
            TabletState.PairingUi.Off -> button(R.string.btn_pair) { beginPairing() }
            is TabletState.PairingUi.Waiting -> {
                pairingText = body("")
                button(R.string.btn_cancel) { RemoteService.instance?.cancelPairing() }
            }
            is TabletState.PairingUi.Code -> {
                pairingText = body("")
                pairingText?.textSize = 28f
                if (!p.confirmed) button(R.string.btn_confirm) { RemoteService.instance?.confirmPairing() }
                button(R.string.btn_cancel) { RemoteService.instance?.cancelPairing() }
            }
        }
        updatePairingText()
        if (TabletState.pairing != TabletState.PairingUi.Off) main.postDelayed(tick, 1000)
    }

    private fun updatePairingText() {
        val tv = pairingText ?: return
        when (val p = TabletState.pairing) {
            TabletState.PairingUi.Off -> Unit
            is TabletState.PairingUi.Waiting ->
                tv.text = getString(R.string.pairing_waiting, secondsLeft(p.deadline))
            is TabletState.PairingUi.Code -> {
                val c = p.code
                val spaced = c.substring(0, 3) + " " + c.substring(3)
                tv.text = getString(if (p.confirmed) R.string.pairing_confirmed else R.string.pairing_code, spaced, secondsLeft(p.deadline))
            }
        }
    }

    private fun secondsLeft(deadline: Long) = maxOf(0L, (deadline - System.currentTimeMillis()) / 1000)

    private fun beginPairing() {
        if (!RemoteService.hasBluetoothPermissions(this) || !bluetoothOn()) {
            Toast.makeText(this, R.string.pair_prereq, Toast.LENGTH_LONG).show()
            return
        }
        prefs.runInBackground = true
        val svc = RemoteService.instance
        if (svc != null) svc.startPairing() else startService(RemoteService.ACTION_PAIR)
    }

    private fun renderSlots() {
        section(R.string.section_slots)
        body(R.string.slots_intro)
        for (i in 0 until Protocol.APP_SLOTS) {
            val label = AppCatalog.label(this, prefs.slot(i))
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(
                TextView(this).apply {
                    text = getString(R.string.slot_row, i + 1, label ?: getString(R.string.slot_unset))
                    textSize = 16f
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            row.addView(Button(this).apply { setText(R.string.btn_choose); setOnClickListener { chooseApp(i) } })
            if (prefs.slot(i) != null) {
                row.addView(Button(this).apply {
                    setText(R.string.btn_clear)
                    setOnClickListener { prefs.setSlot(i, null); render() }
                })
            }
            content.addView(row)
        }
    }

    private fun chooseApp(slot: Int) {
        val apps = AppCatalog.launchableApps(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.choose_app_title, slot + 1))
            .setItems(apps.map { it.label }.toTypedArray()) { _, which ->
                prefs.setSlot(slot, apps[which].packageName)
                render()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun renderTests() {
        section(R.string.section_test)
        body(R.string.test_intro)
        val tests = listOf(
            R.string.test_lock to { run(Command.LOCK) },
            R.string.test_play_pause to { run(Command.PLAY_PAUSE) },
            R.string.test_vol_up to { run(Command.VOLUME_UP) },
            R.string.test_vol_down to { run(Command.VOLUME_DOWN) },
            R.string.test_ring to { run(Command.RING) },
            R.string.test_close_app to { main.postDelayed({ run(Command.KILL_APP) }, 3000) },
        )
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((res, action) in tests) {
            wrap.addView(Button(this).apply { setText(res); setOnClickListener { action() } })
        }
        for ((res, cmd) in listOf(R.string.test_open_slot to Command.OPEN_APP, R.string.test_switch_slot to Command.SWITCH_APP)) {
            for (i in 0 until Protocol.APP_SLOTS) {
                wrap.addView(Button(this).apply {
                    text = getString(res, i + 1)
                    setOnClickListener { run(cmd, byteArrayOf(i.toByte())) }
                })
            }
        }
        content.addView(wrap)
    }

    private fun run(command: Command, args: ByteArray = ByteArray(0)) {
        thread(name = "test-command") {
            val status = executor.execute(command, args)
            main.post { Toast.makeText(this, statusText(status), Toast.LENGTH_SHORT).show() }
        }
    }

    private fun statusText(s: Status): String = when (s) {
        Status.SUCCESS -> getString(R.string.res_ok)
        Status.ACCESSIBILITY_DISABLED -> getString(R.string.res_a11y)
        Status.NOTHING_TO_CLOSE -> getString(R.string.res_nothing_to_close)
        Status.SLOT_EMPTY -> getString(R.string.res_slot_empty)
        Status.APP_START_FAILED -> getString(R.string.res_start_failed)
        else -> getString(R.string.res_failed)
    }

    // endregion

    // region checks and actions

    private fun bluetoothOn(): Boolean = try {
        getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (e: SecurityException) {
        false
    }

    private fun accessibilityEnabled(): Boolean {
        val am = getSystemService(AccessibilityManager::class.java)
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
                it.resolveInfo.serviceInfo.name == LockAccessibilityService::class.java.name
        }
    }

    private fun startService(action: String? = null) {
        if (!RemoteService.hasBluetoothPermissions(this)) {
            Toast.makeText(this, R.string.pair_prereq, Toast.LENGTH_LONG).show()
        } else if (!RemoteService.start(this, action)) {
            Toast.makeText(this, R.string.svc_start_refused, Toast.LENGTH_LONG).show()
        }
    }

    private fun startActivitySafe(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.no_handler, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmUnpair() {
        AlertDialog.Builder(this)
            .setMessage(R.string.unpair_confirm)
            .setPositiveButton(R.string.btn_unpair) { _, _ ->
                vault.delete()
                prefs.paired = false
                render()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    // endregion

    // region view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun title(res: Int) {
        content.addView(TextView(this).apply { setText(res); textSize = 24f; setPadding(0, 0, 0, dp(8)) })
    }

    private fun section(res: Int) {
        content.addView(TextView(this).apply {
            setText(res); textSize = 18f; setPadding(0, dp(24), 0, dp(8))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun body(res: Int) = body(getString(res))

    private fun body(text: String): TextView =
        TextView(this).apply { this.text = text; textSize = 16f; setPadding(0, dp(4), 0, dp(4)) }
            .also { content.addView(it) }

    private fun note(res: Int) = note(getString(res))

    private fun note(text: String) {
        content.addView(TextView(this).apply {
            this.text = text; textSize = 13f; alpha = 0.75f; setPadding(dp(8), 0, 0, dp(8))
        })
    }

    private fun button(res: Int, onClick: () -> Unit) {
        content.addView(Button(this).apply { setText(res); setOnClickListener { onClick() } })
    }

    private fun check(ok: Boolean, label: Int, action: Int?, onClick: () -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(
            TextView(this).apply {
                text = getString(if (ok) R.string.check_ok else R.string.check_bad, getString(label))
                textSize = 16f
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        // Unmet items get a button; a few met items keep one for the opposite action (stop / unpair).
        if (action != null && (!ok || label == R.string.chk_service || label == R.string.chk_paired)) {
            row.addView(Button(this).apply { setText(action); setOnClickListener { onClick() } })
        }
        content.addView(row)
    }

    // endregion

    private companion object {
        const val MAX_WIDTH_DP = 640
    }
}
