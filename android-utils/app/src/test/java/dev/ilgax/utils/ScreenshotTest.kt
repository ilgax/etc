package dev.ilgax.utils

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ilgax.utils.remote.PairingPanel
import dev.ilgax.utils.remote.PairingStep
import androidx.compose.runtime.Composable
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

    /** Single-column detail page, as shown after tapping a card on a phone. */
    private fun detailPage(module: UtilModule) = root(listOf(module), selected = module.id)
}
