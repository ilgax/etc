package dev.ilgax.tabremote.wear

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.ilgax.tabremote.protocol.Command
import dev.ilgax.tabremote.protocol.UpdateReport

class MainActivity : Activity() {
    private enum class Mode { LIST, PAIR_SEARCH, PAIR_CODE }

    private lateinit var client: RemoteClient
    private lateinit var prefs: WatchPrefs
    private lateinit var updater: WatchUpdater
    private lateinit var scroll: ScrollView
    private lateinit var content: LinearLayout
    private lateinit var statusView: TextView
    private var mode = Mode.LIST
    private var code = ""
    private var statusText = ""
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = RemoteClient.get(this)
        prefs = WatchPrefs(this)
        updater = WatchUpdater.get(this)
        statusText = getString(R.string.status_ready)

        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isFocusable = true
            isFocusableInTouchMode = true
            isVerticalScrollBarEnabled = false
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // Rotary crown / bezel scrolling.
            setOnGenericMotionListener { v, e ->
                if (e.action == MotionEvent.ACTION_SCROLL && e.isFromSource(android.view.InputDevice.SOURCE_ROTARY_ENCODER)) {
                    val delta = -e.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this@MainActivity).scaledVerticalScrollFactor
                    v.scrollBy(0, delta.toInt())
                    true
                } else false
            }
        }
        // Extra top/bottom padding lets every item reach the middle of a round screen.
        val round = resources.configuration.isScreenRound
        val side = if (round) (resources.displayMetrics.widthPixels * 0.12f).toInt() else dp(8)
        content.setPadding(side, dp(if (round) 44 else 16), side, dp(if (round) 64 else 24))
        setContentView(scroll)
    }

    override fun onStart() {
        super.onStart()
        // Opening the app is the moment the watch tells the tablet its version and picks up an update offer.
        client.setForeground(true)
        client.onUpdateHint = { checkForUpdate() }
        updater.listener = { render(); launchPendingConfirm() }
        checkForUpdate()
    }

    override fun onStop() {
        updater.listener = null
        client.onUpdateHint = null
        client.setForeground(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        render()
        scroll.requestFocus()
        launchPendingConfirm()
    }

    override fun onDestroy() {
        if (mode != Mode.LIST) client.cancelPairing()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        pendingAction = null
        if (hasPermissions()) action?.invoke() else setStatus(getString(R.string.res_no_permission))
    }

    // region rendering

    private fun render() {
        content.removeAllViews()
        statusView = TextView(this).apply {
            text = statusText
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(8))
        }
        content.addView(statusView)
        when {
            mode == Mode.PAIR_SEARCH -> renderSearching()
            mode == Mode.PAIR_CODE -> renderCode()
            !client.isPaired -> renderUnpaired()
            else -> renderList()
        }
    }

    private fun renderUnpaired() {
        info(R.string.unpaired_info)
        pill(R.string.btn_pair, primary = true) { beginPairing() }
    }

    private fun renderList() {
        renderUpdate()
        pill(R.string.btn_lock, primary = true) { run(Command.LOCK) }
        pill(R.string.btn_play_pause) { run(Command.PLAY_PAUSE) }
        pill(R.string.btn_vol_up) { run(Command.VOLUME_UP) }
        pill(R.string.btn_vol_down) { run(Command.VOLUME_DOWN) }
        pill(R.string.btn_ring) { run(Command.RING) }
        pill(R.string.btn_close_app) { run(Command.KILL_APP) }

        val labels = prefs.slotLabels
        if (labels.any { it.isNotEmpty() }) {
            content.addView(Switch(this).apply {
                setText(R.string.toggle_close_first)
                textSize = 14f
                setTextColor(Color.WHITE)
                isChecked = prefs.closeFirst
                isFocusable = false
                setOnCheckedChangeListener { _, checked ->
                    prefs.closeFirst = checked
                    render()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(4) })
            val switching = prefs.closeFirst
            labels.forEachIndexed { i, label ->
                if (label.isEmpty()) return@forEachIndexed
                val text = getString(if (switching) R.string.btn_switch_to else R.string.btn_open, label)
                pill(text) { run(if (switching) Command.SWITCH_APP else Command.OPEN_APP, i, label) }
            }
        }
        pill(R.string.btn_sync) { sync() }
        pill(R.string.btn_repair) { beginPairing() }
        versionLabel()
    }

    private fun renderUpdate() {
        when (val u = updater.state) {
            UpdateUi.Idle -> Unit
            is UpdateUi.Available -> {
                info(R.string.update_available)
                pill(getString(R.string.btn_update_to, u.offer.versionCode), primary = true) { updater.start(u.offer) }
                pill(R.string.btn_dismiss) { updater.dismiss() }
            }
            is UpdateUi.Downloading -> info(getString(R.string.update_downloading, u.percent))
            is UpdateUi.Installing -> info(getString(R.string.update_installing, u.version))
            is UpdateUi.Confirm -> info(getString(R.string.update_confirm))
            UpdateUi.NeedsInstallPermission -> {
                info(getString(R.string.update_allow_installs))
                info(WatchUpdater.ADB_FIX, size = 11f)
                pill(R.string.btn_dismiss) { updater.dismiss() }
            }
            is UpdateUi.Failed -> {
                info(
                    getString(
                        when (u.failure) {
                            UpdateReport.Failure.NO_WIFI -> R.string.update_fail_no_wifi
                            UpdateReport.Failure.DOWNLOAD -> R.string.update_fail_download
                            UpdateReport.Failure.HASH_MISMATCH -> R.string.update_fail_hash
                            UpdateReport.Failure.INSTALL_FAILED -> R.string.update_fail_install
                            else -> R.string.update_fail_unknown
                        },
                    ),
                )
                pill(R.string.btn_dismiss) { updater.dismiss() }
            }
        }
    }

    private fun renderSearching() {
        info(R.string.pair_search_info)
        pill(R.string.btn_cancel) { cancelPairing() }
    }

    private fun renderCode() {
        info(R.string.pair_compare)
        content.addView(TextView(this).apply {
            text = getString(R.string.code_display, code.substring(0, 3), code.substring(3))
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(12))
        })
        pill(R.string.btn_confirm, primary = true) {
            setStatus(getString(R.string.status_confirming))
            client.confirmPairing()
        }
        pill(R.string.btn_cancel) { cancelPairing() }
    }

    // endregion

    // region actions

    private fun run(command: Command, slot: Int = -1, label: String? = null) {
        withPermissions {
            setStatus(getString(R.string.status_sending))
            val args = if (slot >= 0) byteArrayOf(slot.toByte()) else ByteArray(0)
            client.send(command, args) { r ->
                if (isDestroyed) return@send
                setStatus(ResultText.describe(this, r, command, label))
                if (r == Result.SUCCESS) Haptics.success(this) else Haptics.failure(this)
            }
        }
    }

    private fun sync() = withPermissions {
        setStatus(getString(R.string.status_syncing))
        client.syncApps { r ->
            if (isDestroyed) return@syncApps
            if (r == Result.SUCCESS) {
                setStatus(getString(R.string.status_synced))
                Haptics.success(this)
                render()
            } else {
                setStatus(ResultText.describe(this, r, Command.SYNC_APPS))
                Haptics.failure(this)
            }
        }
    }

    private fun beginPairing() = withPermissions {
        mode = Mode.PAIR_SEARCH
        statusText = getString(R.string.status_searching)
        render()
        client.startPairing(object : PairingListener {
            override fun onCode(code: String) {
                if (isDestroyed) return
                this@MainActivity.code = code
                mode = Mode.PAIR_CODE
                statusText = getString(R.string.status_check_code)
                render()
            }

            override fun onTabletNotConfirmed() {
                if (!isDestroyed) setStatus(getString(R.string.status_confirm_tablet))
            }

            override fun onFinished(result: Result) {
                if (isDestroyed || result == Result.CANCELLED) return
                mode = Mode.LIST
                statusText = if (result == Result.SUCCESS) getString(R.string.status_paired)
                else ResultText.describe(this@MainActivity, result, null)
                if (result == Result.SUCCESS) Haptics.success(this@MainActivity) else Haptics.failure(this@MainActivity)
                render()
                if (result == Result.SUCCESS) checkForUpdate()
            }
        })
    }

    /** HELLO + offer pickup. Silent on any problem: this is background housekeeping, not a user action. */
    private fun checkForUpdate() {
        if (!client.isPaired || !hasPermissions() || updater.isBusy) return
        client.checkForUpdate(WatchUpdater.versionCode(this)) { check ->
            if (isDestroyed) return@checkForUpdate
            if (check is UpdateCheck.Offer) updater.onOffer(check.offer, auto = true)
        }
    }

    /** The system install dialog must be started from a visible activity. */
    private fun launchPendingConfirm() {
        val intent = updater.takePendingConfirm() ?: return
        try {
            startActivity(intent)
        } catch (e: Exception) {
            setStatus(getString(R.string.update_fail_install))
        }
    }

    private fun cancelPairing() {
        client.cancelPairing()
        mode = Mode.LIST
        statusText = getString(R.string.status_ready)
        render()
    }

    private fun hasPermissions() =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun withPermissions(action: () -> Unit) {
        if (hasPermissions()) return action()
        pendingAction = action
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), 1)
    }

    private fun setStatus(text: String) {
        statusText = text
        statusView.text = text
    }

    // endregion

    // region view helpers

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun info(res: Int) = info(getString(res))

    private fun info(text: String, size: Float = 13f) {
        content.addView(TextView(this).apply {
            this.text = text
            textSize = size
            gravity = Gravity.CENTER
            setTextColor(0xFFB0BEC5.toInt())
            setPadding(0, 0, 0, dp(8))
        })
    }

    /** Small, dim app version at the very end of the list. */
    private fun versionLabel() {
        val name = packageManager.getPackageInfo(packageName, 0).versionName ?: return
        content.addView(TextView(this).apply {
            text = getString(R.string.app_version, name)
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFF78909C.toInt())
            setPadding(0, dp(8), 0, 0)
        })
    }

    private fun pill(res: Int, primary: Boolean = false, onClick: () -> Unit) = pill(getString(res), primary, onClick)

    private fun pill(text: String, primary: Boolean = false, onClick: () -> Unit) {
        content.addView(
            Button(this).apply {
                this.text = text
                isAllCaps = false
                textSize = 16f
                setTextColor(Color.WHITE)
                minHeight = dp(52)
                minimumHeight = dp(52)
                isFocusable = false // keep focus on the ScrollView so the rotary input scrolls it
                background = GradientDrawable().apply {
                    cornerRadius = dp(26).toFloat()
                    setColor(getColor(if (primary) R.color.button_primary else R.color.button_bg))
                }
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(4)
            },
        )
    }

    // endregion
}
