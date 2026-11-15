package dev.ilgax.utils

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.Modifier
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.ModuleCardState
import dev.ilgax.utils.core.UtilModule
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.hotspot.ApState
import dev.ilgax.utils.hotspot.HotspotScreen
import dev.ilgax.utils.hotspot.HotspotUiState
import dev.ilgax.utils.math.MathActions
import dev.ilgax.utils.math.MathScreen
import dev.ilgax.utils.math.MathUiState
import dev.ilgax.utils.math.ModelRow
import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.tabremote.protocol.Status
import dev.ilgax.utils.remote.PairingPanel
import dev.ilgax.utils.remote.PairingStep
import dev.ilgax.utils.remote.RemoteActions
import dev.ilgax.utils.remote.RemoteScreen
import dev.ilgax.utils.remote.RemoteState
import dev.ilgax.utils.remote.RemoteUiState
import dev.ilgax.utils.remote.SetupChecks
import dev.ilgax.utils.remote.SlotUi
import dev.ilgax.utils.remote.WatchAppUi
import dev.ilgax.utils.remote.WatchUpdateState
import dev.ilgax.utils.speaker.SpeakerActions
import dev.ilgax.utils.core.action.ActionProvider
import dev.ilgax.utils.core.action.ActionRegistry
import dev.ilgax.utils.core.action.CoreActions
import dev.ilgax.utils.core.action.RequirementChecker
import dev.ilgax.utils.core.action.RequirementEnv
import dev.ilgax.utils.core.action.RequirementKind
import dev.ilgax.utils.remote.macros.MacroDraft
import dev.ilgax.utils.remote.macros.MacroEditor
import dev.ilgax.utils.remote.macros.MacroEditorActions
import dev.ilgax.utils.remote.macros.MacroUi
import dev.ilgax.utils.remote.macros.Origin
import dev.ilgax.utils.remote.macros.RunRecord
import dev.ilgax.utils.remote.macros.StepDraft
import dev.ilgax.utils.remote.macros.StepRecord
import dev.ilgax.utils.remote.macros.StepStatus
import dev.ilgax.utils.speaker.SpeakerScreen
import dev.ilgax.utils.speaker.SpeakerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A module with canned state, so the real screens can be rendered on the JVM without Shizuku. */
class FakeModule(
    override val id: String,
    override val title: String,
    override val summary: String,
    override val iconRes: Int,
    card: ModuleCardState,
    override val quickSwitch: Boolean = true,
    private val detail: @Composable (PaddingValues, Modifier) -> Unit,
) : UtilModule {
    override val cardState: StateFlow<ModuleCardState> = MutableStateFlow(card)
    override suspend fun toggle() = Unit

    @Composable
    override fun Detail(contentPadding: PaddingValues, modifier: Modifier) = detail(contentPadding, modifier)
}

object Fixtures {
    val hotspotOn = HotspotUiState(
        shizuku = ShizukuStatus.Ready, apState = ApState.ENABLED, ssid = "xcynet", hidden = true,
        securityType = 1, passphrase = "correct-horse-battery", bands = 1, clientCount = 1,
        upstreamSsid = "NAVONA", upstreamFrequency = 5200,
        diagnostics = DIAG_HOTSPOT,
    )
    val hotspotOff = hotspotOn.copy(
        apState = ApState.DISABLED, clientCount = -1, upstreamSsid = null, upstreamFrequency = 0, hidden = false,
        ssid = "Tab-S9FE", bands = 3, diagnostics = "",
    )
    val hotspotOpen = hotspotOn.copy(securityType = 0, passphrase = null, hidden = false, ssid = "FreeWifi", clientCount = 0)
    val speakerOn = SpeakerUiState(
        enabled = true, pauseOnDisconnect = true, shizuku = ShizukuStatus.Ready,
        statusLine = "ON | Shizuku READY | speaker blocked", diagnostics = DIAG_SPEAKER,
    )
    val noop = SpeakerActions({}, {}, {})

    val remoteListening = RemoteUiState(
        service = RemoteState.Service.RUNNING, checks = SetupChecks(true, true, true, true, true), paired = true,
        slots = listOf(SlotUi("Firefox", "org.mozilla.firefox"), SlotUi("Spotify", "com.spotify.music"), null, SlotUi("Files", "com.android.documentsui")),
        forceStop = true,
    )
    val remoteUpdateAvailable = remoteListening.copy(watchApp = WatchAppUi("0.2.1", 3, 2))
    val remoteUpToDate = remoteListening.copy(watchApp = WatchAppUi("0.2.1", 3, 3))
    val remoteUpdateDownloading = remoteListening.copy(watchApp = WatchAppUi("0.2.1", 3, 2, WatchUpdateState.Downloading(46)))
    val remoteMacros = remoteListening.copy(
        macros = listOf(
            MacroUi(1, "Leaving the desk", 4, "Play/pause · Close current app · Lock screen · Hotspot off"),
            MacroUi(2, "Movie night", 5, "Brightness 20% · Do Not Disturb on · Open Firefox · Wait 2 s …"),
            MacroUi(3, "Gece modu İş", 3, "Rotation lock · Volume 30% · Ringer: Vibrate"),
        ),
        runs = listOf(
            RunRecord(1_700_000_000_000, 2, "Movie night", Origin.Watch, 2, listOf(
                StepRecord(0, "screen.brightness", "Brightness 20%", StepStatus.Ok, null, 40),
                StepRecord(1, "sound.dnd_on", "Do Not Disturb on", StepStatus.Failed, "Needs Do Not Disturb access", 12),
                StepRecord(2, "app.open", "Open Firefox", StepStatus.NotRun, null, 0),
            )),
            RunRecord(1_699_999_000_000, 1, "Leaving the desk", Origin.Local, 0, listOf(StepRecord(0, "remote.lock", "Lock screen", StepStatus.Ok, null, 90))),
        ),
    )
    val remoteSetup = remoteListening.copy(checks = SetupChecks(true, true, true, false, false))

    val mathReady = MathUiState(
        shizuku = ShizukuStatus.Ready, accessibilityOn = false,
        models = listOf(
            ModelRow("bundled", "Pix2Text-MFR 1.5 (int8)", true, "34", true),
            ModelRow("texteller-3-int8", "TexTeller 3 (int8)", false, "318", false),
        ),
        dot = DotMeaning.Multiply, corrections = true, correctionCount = 7,
        tryText = "\\frac{x}{13}=\\frac{15+x}{16}=\\frac{35+x}{y}",
    )

    fun math(card: ModuleCardState, state: MathUiState = mathReady) = FakeModule(
        "math", "Equation solver", "Circle handwritten math with the S Pen and see the answer next to it.",
        dev.ilgax.utils.math.R.drawable.ic_math, card, quickSwitch = false,
    ) { padding, mod -> MathScreen(state, MathActions(), padding, mod) }

    fun remote(card: ModuleCardState, state: RemoteUiState = remoteListening) = FakeModule(
        "remote", "Watch remote", "Lets your paired watch lock, ring and control this tablet over Bluetooth.",
        dev.ilgax.utils.remote.R.drawable.ic_watch, card,
    ) { padding, mod -> RemoteScreen(state, RemoteActions(runCommand = { _, _ -> Status.SUCCESS }), padding, mod) }

    /** Real steps from the core and remote providers, with Shizuku and write-settings missing and accessibility on. */
    @Composable
    private fun macroRegistry(): ActionRegistry {
        val context = androidx.compose.ui.platform.LocalContext.current
        return remember { ActionRegistry(listOf<ActionProvider>(CoreActions, dev.ilgax.utils.speaker.SpeakerMacroActions, dev.ilgax.utils.remote.RemoteMacroActions(context))) }
    }
    private val macroChecker = RequirementChecker(RequirementEnv { it == RequirementKind.Accessibility })

    val macroDraft = MacroDraft(
        2, "Movie night", true,
        listOf(
            StepDraft(1, "remote.play_pause"),
            StepDraft(2, "screen.brightness", mapOf("percent" to "20")),
            StepDraft(3, "flow.wait", mapOf("ms" to "2500")),
            StepDraft(4, "radio.wifi_off"),
            StepDraft(5, "remote.open_slot", mapOf("slot" to "0")),
        ),
    )

    fun macroEditor(card: ModuleCardState, draft: MacroDraft = macroDraft) = FakeModule(
        "remote", "Watch remote", "Lets your paired watch lock, ring and control this tablet over Bluetooth.",
        dev.ilgax.utils.remote.R.drawable.ic_watch, card,
    ) { padding, mod ->
        MacroEditor(
            draft, draft, macroRegistry(), macroChecker, listOf("Firefox", "Spotify", null, "Files"),
            MacroEditorActions(), padding, mod,
        )
    }

    fun addStepPanel(): @Composable () -> Unit = {
        dev.ilgax.utils.remote.macros.AddStepPanel(macroRegistry().grouped(), {}, Modifier.fillMaxHeight(0.9f))
    }

    fun speaker(card: ModuleCardState, state: SpeakerUiState = speakerOn) = FakeModule(
        "speaker", "Speaker kill switch", "Keeps the built-in speaker silent while Bluetooth earbuds keep working.",
        dev.ilgax.utils.speaker.R.drawable.ic_speaker_off, card,
    ) { padding, mod -> SpeakerScreen(state, noop, padding, mod) }

    fun hotspot(card: ModuleCardState, state: HotspotUiState) = FakeModule(
        "hotspot", "Wi-Fi hotspot", "Starts the tablet's Wi-Fi hotspot in one tap.",
        dev.ilgax.utils.hotspot.R.drawable.ic_wifi_tethering, card,
    ) { padding, mod -> HotspotScreen(state, {}, padding, mod, onOpenSettings = { true }) }

    private const val DIAG_HOTSPOT = """SDK_INT=36 RELEASE=16 uid=2000
STAGE: context bootstrap
note: direct ActivityThread path failed, trying systemMain()
OK: context=ShellContext pkg=com.android.shell
STAGE: TetheringManager construction
Hotspot on
Name: xcynet (hidden)
Security type: 1
Band mask: 1
Clients: 1
Wi-Fi: NAVONA 5200 MHz"""

    private const val DIAG_SPEAKER = """Android 16 (SDK 36) samsung SM-X516B
Switch: ON  Shizuku: READY  status: ON | Shizuku READY | speaker blocked

Output devices:
  [2] BUILTIN_SPEAKER(2) 'Speaker' addr=''
  [41] BLUETOOTH_A2DP(8) 'Galaxy Buds' addr='AA:BB'"""
}

