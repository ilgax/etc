package dev.ilgax.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.foundation.Image
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import dev.ilgax.utils.math.CardUi
import dev.ilgax.utils.math.LocalKatexRenderer
import dev.ilgax.utils.math.OverlayActions
import dev.ilgax.utils.math.FloatingState
import dev.ilgax.utils.math.OverlayState
import dev.ilgax.utils.math.SolveTimings
import dev.ilgax.utils.math.Stage
import dev.ilgax.utils.math.SolveOverlay
import dev.ilgax.utils.math.normalize.DotMeaning
import dev.ilgax.utils.math.normalize.PxRect
import dev.ilgax.utils.math.normalize.SolvePipeline
import dev.ilgax.utils.math.CaptureProblem
import dev.ilgax.utils.remote.PairingPanel
import dev.ilgax.utils.remote.PairingStep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ilgax.utils.core.CardMode
import dev.ilgax.utils.core.ModuleCardState
import dev.ilgax.utils.core.UtilModule
import dev.ilgax.utils.core.shizuku.ShizukuStatus
import dev.ilgax.utils.core.ui.theme.UtilsTheme
import dev.ilgax.utils.ui.UtilsRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real shell and screens with canned module state. Roborazzi only writes the PNGs when run
 * through `recordRoborazziDebug`; a plain `testDebugUnitTest` just checks that everything composes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val hotspotCardOn = ModuleCardState(CardMode.On, "xcynet · 1 device")
    private val speakerCardOn = ModuleCardState(CardMode.On, "Speaker blocked")

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { UtilsTheme(darkTheme = dark, dynamicColor = false) { content() } }
        compose.onRoot().captureRoboImage("../screenshots/$name.png")
    }

    private fun root(modules: List<UtilModule>, shizuku: ShizukuStatus = ShizukuStatus.Ready, selected: String? = null): @Composable () -> Unit = {
        UtilsRoot(modules, shizuku, selected, onSelect = {}, onToggle = {}, onShizukuAction = {})
    }

    private val remoteCardOn = ModuleCardState(CardMode.On, "Listening · paired")

    private fun readyModules(speakerCard: ModuleCardState = speakerCardOn, hotspotCard: ModuleCardState = hotspotCardOn) = listOf(
        Fixtures.speaker(speakerCard),
        Fixtures.hotspot(hotspotCard, Fixtures.hotspotOn),
        Fixtures.remote(remoteCardOn),
    )

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun homePhoneLight() = shot("01-home-phone-light", false, root(readyModules(ModuleCardState(CardMode.Off, "Off · the speaker works normally"))))

    @Test
    @Config(qualifiers = "w411dp-h891dp-night-xhdpi")
    fun homePhoneDark() = shot("02-home-phone-dark", true, root(readyModules()))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-hdpi")
    fun homeTabletHotspotDark() = shot("03-tablet-two-pane-hotspot-dark", true, root(readyModules(), selected = "hotspot"))

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xhdpi")
    fun hotspotOffPhoneLight() = shot(
        "04-hotspot-off-phone-light", false,
        detailPage(Fixtures.hotspot(ModuleCardState(CardMode.Off, "Off"), Fixtures.hotspotOff)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xhdpi")
    fun hotspotOpenWarningPhoneLight() = shot(
        "05-hotspot-open-warning-phone-light", false,
        detailPage(Fixtures.hotspot(ModuleCardState(CardMode.On, "FreeWifi · 0 devices"), Fixtures.hotspotOpen)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1500dp-night-xhdpi")
    fun speakerOnPhoneDark() = shot("06-speaker-on-phone-dark", true, detailPage(Fixtures.speaker(speakerCardOn)))

    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun homeShizukuBannerPhoneLight() = shot(
        "07-home-shizuku-banner-phone-light", false,
        root(
            listOf(
                Fixtures.speaker(ModuleCardState(CardMode.On, "Volume only · no Shizuku")),
                Fixtures.hotspot(ModuleCardState(CardMode.Unavailable, "Needs Shizuku · not running"), Fixtures.hotspotOff),
                Fixtures.remote(ModuleCardState(CardMode.Off, "Not paired")),
            ),
            shizuku = ShizukuStatus.NotRunning,
        ),
    )

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xhdpi")
    fun remoteSetupPhoneLight() = shot(
        "08-watch-remote-phone-light", false,
        detailPage(Fixtures.remote(remoteCardOn, Fixtures.remoteSetup)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xhdpi")
    fun watchAppUpdateAvailablePhoneLight() = shot(
        "17-watch-app-update-available-phone-light", false,
        detailPage(Fixtures.remote(remoteCardOn, Fixtures.remoteUpdateAvailable)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1900dp-xhdpi")
    fun watchAppUpToDatePhoneLight() = shot(
        "18-watch-app-up-to-date-phone-light", false,
        detailPage(Fixtures.remote(remoteCardOn, Fixtures.remoteUpToDate)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1900dp-night-xhdpi")
    fun watchAppDownloadingPhoneDark() = shot(
        "19-watch-app-downloading-phone-dark", true,
        detailPage(Fixtures.remote(remoteCardOn, Fixtures.remoteUpdateDownloading)),
    )

    /** The sheet content on a dimmed detail page (Robolectric cannot capture the sheet's own window). */
    @Test
    @Config(qualifiers = "w411dp-h891dp-xhdpi")
    fun pairingSheetPhoneLight() = shot("09-pairing-sheet-phone-light", false) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            detailPage(Fixtures.remote(remoteCardOn)).invoke()
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)),
            )
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth(),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(top = 16.dp)) {
                    PairingPanel(PairingStep.Code("482 913", 87, false), {}, {}, {})
                }
            }
        }
    }

    // ---- Macros ----

    @Test
    @Config(qualifiers = "w411dp-h2300dp-xhdpi")
    fun macrosSectionPhoneLight() = shot(
        "20-macros-section-phone-light", false,
        detailPage(Fixtures.remote(remoteCardOn, Fixtures.remoteMacros)),
    )

    @Test
    @Config(qualifiers = "w411dp-h1500dp-xhdpi")
    fun macroEditorPhoneLight() = shot("21-macro-editor-phone-light", false, detailPage(Fixtures.macroEditor(remoteCardOn)))

    @Test
    @Config(qualifiers = "w411dp-h1500dp-night-xhdpi")
    fun macroEditorPhoneDark() = shot("22-macro-editor-phone-dark", true, detailPage(Fixtures.macroEditor(remoteCardOn)))

    /** The sheet content on a dimmed editor (Robolectric cannot capture the sheet's own window). */
    @Test
    @Config(qualifiers = "w411dp-h1500dp-xhdpi")
    fun addStepPickerPhoneLight() = shot("23-macro-add-step-phone-light", false) {
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
            detailPage(Fixtures.macroEditor(remoteCardOn)).invoke()
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.32f)),
            )
            androidx.compose.material3.Surface(
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.85f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                androidx.compose.foundation.layout.Column(Modifier.padding(top = 16.dp)) { Fixtures.addStepPanel().invoke() }
            }
        }
    }

    // ---- Equation solver ----

    private val mathCard = ModuleCardState(CardMode.On, "Ready · Shizuku · Pix2Text-MFR 1.5 (int8)")

    @Test
    @Config(qualifiers = "w411dp-h2400dp-xhdpi")
    fun mathDetailPhoneLight() = shot("10-math-detail-phone-light", false, withKatex("try-light", detailPage(Fixtures.math(mathCard))))

    @Test
    @Config(qualifiers = "w411dp-h2400dp-night-xhdpi")
    fun mathDetailPhoneDark() = shot("11-math-detail-phone-dark", true, withKatex("try-dark", detailPage(Fixtures.math(mathCard))))

    /** The floating card over the live page (no frozen screenshot any more), with the timings line. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    fun solveResultTabletLight() = shot("12-solve-result-tablet-light", false, floatingScene("result-light", minimized = false))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-night-hdpi")
    fun solveResultTabletDark() = shot("13-solve-result-tablet-dark", true, floatingScene("result-dark", minimized = false))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    fun solveLassoTabletLight() = shot("14-solve-lasso-tablet-light", false, lassoScene())

    /** Minimised: just the answer, tap to expand. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    fun solveMinimisedPillTabletLight() = shot("16-solve-pill-tablet-light", false, floatingScene("result-light", minimized = true))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    fun solveCaptureProblemTabletLight() = shot("15-solve-no-capture-tablet-light", false) {
        val state = remember { OverlayState().apply { problem = CaptureProblem("Shizuku: UserService binder is not alive", true) } }
        Box(Modifier.fillMaxSize()) {
            Image(pageBitmap().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            SolveOverlay(state, FloatingState(), OverlayActions())
        }
    }

    /** 0.4.0: an inequality solved with a sign chart, and a logarithm equation where one candidate is rejected in its Check step. */
    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    fun solveInequalityTabletLight() =
        shot("24-solve-inequality-tablet-light", false, floatingScene("inequality-light", minimized = false, input = "\\frac{x-1}{x+2}\\ge 0"))

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land-hdpi")
    fun solveLogRejectedRootTabletLight() =
        shot("25-solve-log-rejected-tablet-light", false, floatingScene("log-light", minimized = false, input = "\\log x+\\log(x-3)=1"))

    /** KaTeX pre-rendered by headless Chrome from the same page and JSON the card's WebView gets (Robolectric cannot draw a WebView). */
    private fun withKatex(fixture: String, content: @Composable () -> Unit): @Composable () -> Unit = {
        val png = remember(fixture) { BitmapFactory.decodeStream(javaClass.getResourceAsStream("/katex/$fixture.png")!!).asImageBitmap() }
        CompositionLocalProvider(
            LocalKatexRenderer provides { _, m -> Image(png, null, m.fillMaxWidth(), contentScale = ContentScale.FillWidth) },
            content = content,
        )
    }

    /** A fake "Flexcil page": white, with one of the user's handwritten samples on it. */
    private fun pageBitmap(): Bitmap {
        val sample = BitmapFactory.decodeStream(javaClass.getResourceAsStream("/samples/s02.jpg")!!)
        val page = Bitmap.createBitmap(1920, 1200, Bitmap.Config.ARGB_8888)
        val c = AndroidCanvas(page)
        c.drawColor(android.graphics.Color.WHITE)
        val rule = android.graphics.Paint().apply { color = 0xFFD8DEE8.toInt(); strokeWidth = 2f }
        for (y in 140..1200 step 70) c.drawLine(0f, y.toFloat(), 1920f, y.toFloat(), rule)
        c.drawBitmap(sample, null, android.graphics.Rect(200, 330, 200 + 860, 330 + 353), null)
        return page
    }

    private val circleCx = 630f
    private val circleCy = 506f
    private val circleAnchor = PxRect(circleCx - 494f, circleCy - 236f, circleCx + 494f, circleCy + 236f)

    /** Frozen-screenshot phase: the page, dimmed, with the freehand loop (hdpi 1.5 gives a 1920x1200 window). */
    private fun lassoScene(): @Composable () -> Unit = {
        val state = remember {
            OverlayState().apply {
                screenshot = pageBitmap().asImageBitmap()
                lasso = (0 until 72).map { i ->
                    val a = i / 72f * 6.2832f
                    val wobble = 1f + 0.05f * kotlin.math.sin(a * 5f)
                    Offset(circleCx + 470f * wobble * kotlin.math.cos(a), circleCy + 225f * wobble * kotlin.math.sin(a))
                }
            }
        }
        SolveOverlay(state, FloatingState(), OverlayActions())
    }

    /** What the user sees after the lasso: the live page, un-dimmed, with the card window on top of it. */
    private fun floatingScene(fixture: String, minimized: Boolean, input: String = "2x^{2}+144=400"): @Composable () -> Unit = withKatex(fixture) {
        val floating = remember {
            FloatingState().apply {
                anchor = circleAnchor
                val r = SolvePipeline.run(input, DotMeaning.Multiply)
                val t = SolveTimings.format(
                    mapOf(
                        Stage.CaptureRequested to 1000L, Stage.BitmapReady to 1180L, Stage.LassoDone to 3000L, Stage.SessionReady to 1500L,
                        Stage.EncoderDone to 3190L, Stage.DecoderDone to 3420L, Stage.SolveDone to 3423L, Stage.KatexRendered to 3463L,
                    ),
                    tokens = 14,
                )
                card = CardUi(latex = input, reading = false, result = r, stepsOpen = !minimized, minimized = minimized, timings = t)
            }
        }
        Box(Modifier.fillMaxSize()) {
            Image(pageBitmap().asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            SolveOverlay(OverlayState(), floating, OverlayActions())
        }
    }

    /** Single-column detail page, as shown after tapping a card on a phone. */
    private fun detailPage(module: UtilModule) = root(listOf(module), selected = module.id)
}
