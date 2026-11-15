package dev.ilgax.utils.core.action

import dev.ilgax.utils.core.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionFrameworkTest {
    private fun fake(
        id: String,
        group: ActionGroup = ActionGroup.System,
        params: List<ParamSpec> = emptyList(),
        requirements: List<Requirement> = emptyList(),
    ) = SimpleAction(id, UiText.Res(1), group, 0, params, requirements) { _, _ -> StepResult.Ok }

    private fun provider(pid: String, vararg actions: ActionType) = object : ActionProvider {
        override val id = pid
        override val actions = actions.toList()
    }

    // region registry

    @Test fun providersContributeAndFind() {
        val r = ActionRegistry()
        val a = fake("x.a", ActionGroup.Network)
        val b = fake("y.b", ActionGroup.Flow)
        r.register(provider("x", a))
        r.register(provider("y", b))
        assertSame(a, r.find("x.a"))
        assertSame(b, r.find("y.b"))
        assertNull(r.find("nope"))
        assertEquals(listOf("x.a", "y.b"), r.all().map { it.id })
    }

    @Test fun groupedFollowsGroupOrderNotRegistrationOrder() {
        val r = ActionRegistry()
        r.register(provider("p", fake("n", ActionGroup.Network), fake("f", ActionGroup.Flow), fake("n2", ActionGroup.Network)))
        val grouped = r.grouped()
        assertEquals(listOf(ActionGroup.Flow, ActionGroup.Network), grouped.map { it.first })
        assertEquals(listOf("n", "n2"), grouped.last().second.map { it.id })
    }

    @Test fun registeringSameProviderIdReplaces() {
        val r = ActionRegistry()
        r.register(provider("p", fake("one")))
        r.register(provider("p", fake("one"), fake("two")))
        assertEquals(listOf("one", "two"), r.all().map { it.id })
    }

    @Test fun duplicateActionIdsAcrossProvidersRejected() {
        val r = ActionRegistry()
        r.register(provider("p", fake("same")))
        assertThrows(IllegalArgumentException::class.java) { r.register(provider("q", fake("same"))) }
        // The failed registration left nothing behind.
        assertEquals(listOf("same"), r.all().map { it.id })
    }

    @Test fun coreProviderShipsEveryPlannedStepWithUniqueIds() {
        val ids = CoreActions.actions.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        for (expected in listOf(
            "flow.wait", "media.next", "media.previous", "media.volume", "media.mute", "media.unmute", "sound.ringer",
            "sound.dnd_on", "sound.dnd_off", "system.home", "system.back", "system.recents", "system.notifications",
            "system.quick_settings", "system.screenshot", "screen.brightness", "screen.auto_brightness_on",
            "screen.auto_brightness_off", "screen.rotation_lock_on", "screen.rotation_lock_off", "screen.timeout",
            "radio.wifi_on", "radio.wifi_off", "app.open", "app.url", "app.force_stop",
        )) assertTrue("missing $expected", expected in ids)
        // Bluetooth off would cut the remote link mid-macro: never offered.
        assertTrue(ids.none { it.contains("bluetooth") })
    }

    // endregion

    // region params

    @Test fun paramSpecsValidateAndCoerce() {
        val pct = ParamSpec.Percent("p", UiText.Res(1), 40)
        assertNull(pct.error("0")); assertNull(pct.error("100"))
        assertTrue(pct.error("101") != null); assertTrue(pct.error("-1") != null); assertTrue(pct.error("x") != null)
        assertEquals("40", pct.coerce(null)); assertEquals("40", pct.coerce("500")); assertEquals("75", pct.coerce("75"))

        val sec = ParamSpec.Seconds("ms", UiText.Res(1))
        assertNull(sec.error("500")); assertNull(sec.error("30000")); assertNull(sec.error("1500"))
        assertTrue(sec.error("400") != null); assertTrue(sec.error("30500") != null); assertTrue(sec.error("1250") != null)

        val url = ParamSpec.Text("u", UiText.Res(1), url = true)
        assertNull(url.error("https://example.com/a?b=1")); assertNull(url.error("HTTP://example.com"))
        assertTrue(url.error("javascript:alert(1)") != null); assertTrue(url.error("example.com") != null)
        assertTrue(url.error("https://a b") != null); assertTrue(url.error("") != null)

        val pkg = ParamSpec.AppPackage("k", UiText.Res(1))
        assertNull(pkg.error("org.mozilla.firefox")); assertTrue(pkg.error("") != null); assertTrue(pkg.error("not a package") != null)

        val slot = ParamSpec.AppSlot("s", UiText.Res(1))
        assertNull(slot.error("0")); assertNull(slot.error("3")); assertTrue(slot.error("4") != null)

        val choice = ParamSpec.Choice("m", UiText.Res(1), listOf(ParamSpec.Option("a", UiText.Res(1)), ParamSpec.Option("b", UiText.Res(1))), "b")
        assertNull(choice.error("a")); assertTrue(choice.error("c") != null); assertEquals("b", choice.coerce(null))

        val int = ParamSpec.IntRange("i", UiText.Res(1), 2, 9, 5)
        assertNull(int.error("2")); assertTrue(int.error("1") != null)
        assertNull(ParamSpec.Bool("b", UiText.Res(1)).error("true")); assertTrue(ParamSpec.Bool("b", UiText.Res(1)).error("yes") != null)
    }

    @Test fun paramValuesFillDefaultsAndDropInvalid() {
        val specs = listOf(ParamSpec.Percent("percent", UiText.Res(1), 30), ParamSpec.Bool("on", UiText.Res(1), true))
        val v = ParamValues.of(specs, mapOf("percent" to "900", "extra" to "ignored"))
        assertEquals(30, v.int("percent"))
        assertTrue(v.bool("on"))
        assertFalse(v.asMap().containsKey("extra"))
    }

    @Test fun problemsFlagInvalidStoredValuesAndMissingRequiredOnes() {
        val specs = listOf(ParamSpec.Percent("percent", UiText.Res(1), 30), ParamSpec.AppPackage("pkg", UiText.Res(1)))
        val problems = ParamValues.problems(specs, mapOf("percent" to "200"))
        assertEquals(listOf("percent", "pkg"), problems.map { it.first.key })
        assertTrue(ParamValues.problems(specs, mapOf("percent" to "20", "pkg" to "a.b")).isEmpty())
    }

    // endregion

    // region requirements

    private class Env(vararg val met: RequirementKind) : RequirementEnv {
        override fun isMet(kind: RequirementKind) = kind in met
    }

    @Test fun checkerReportsOnlyUnmetRequirements() {
        val action = fake("a", requirements = listOf(Requirement.of(RequirementKind.Accessibility), Requirement.of(RequirementKind.Shizuku)))
        val missing = RequirementChecker(Env(RequirementKind.Accessibility)).missing(action, ParamValues.empty())
        assertEquals(1, missing.size)
        assertEquals(listOf(RequirementKind.Shizuku), missing.single().anyOf)
        assertTrue(RequirementChecker(Env(RequirementKind.Accessibility, RequirementKind.Shizuku)).missing(action, ParamValues.empty()).isEmpty())
    }

    @Test fun oneOfRequirementIsMetByAnyAlternative() {
        val action = fake("a", requirements = listOf(Requirement.oneOf(RequirementKind.WriteSettings, RequirementKind.Shizuku)))
        assertTrue(RequirementChecker(Env(RequirementKind.Shizuku)).missing(action, ParamValues.empty()).isEmpty())
        assertTrue(RequirementChecker(Env(RequirementKind.WriteSettings)).missing(action, ParamValues.empty()).isEmpty())
        assertEquals(1, RequirementChecker(Env()).missing(action, ParamValues.empty()).size)
    }

    @Test fun everyRequirementKindIsCheckable() {
        for (kind in RequirementKind.entries) {
            val action = fake("a", requirements = listOf(Requirement.of(kind)))
            assertEquals(1, RequirementChecker(Env()).missing(action, ParamValues.empty()).size)
            assertTrue(RequirementChecker(Env(kind)).missing(action, ParamValues.empty()).isEmpty())
        }
    }

    @Test fun requirementsCanDependOnValues() {
        val specs = listOf(ParamSpec.Bool("strict", UiText.Res(1)))
        val action = SimpleAction(
            "a", UiText.Res(1), ActionGroup.System, 0, specs,
            requirementsFn = { v -> if (v.bool("strict")) listOf(Requirement.of(RequirementKind.Shizuku)) else emptyList() },
        ) { _, _ -> StepResult.Ok }
        val checker = RequirementChecker(Env())
        assertTrue(checker.missing(action, ParamValues.of(specs, mapOf("strict" to "false"))).isEmpty())
        assertEquals(1, checker.missing(action, ParamValues.of(specs, mapOf("strict" to "true"))).size)
    }

    @Test fun emptyRequirementIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { Requirement(emptyList()) }
    }

    @Test fun coreStepsDeclareTheirRequirements() {
        fun req(id: String) = CoreActions.actions.first { it.id == id }.requirements.flatMap { it.anyOf }.toSet()
        assertEquals(setOf(RequirementKind.WriteSettings, RequirementKind.Shizuku), req("screen.brightness"))
        assertEquals(setOf(RequirementKind.NotificationPolicy, RequirementKind.Shizuku), req("sound.dnd_on"))
        assertEquals(setOf(RequirementKind.Shizuku), req("radio.wifi_off"))
        assertEquals(setOf(RequirementKind.Accessibility), req("system.screenshot"))
        assertEquals(setOf(RequirementKind.Shizuku), req("app.force_stop"))
        assertTrue(req("flow.wait").isEmpty())
    }

    // endregion

    // region waits and warnings

    @Test fun waitActionReportsItsDuration() {
        val wait = CoreActions.actions.first { it.id == CoreActions.WAIT_ID }
        val values = ParamValues.of(wait.params, mapOf("ms" to "2500"))
        assertEquals(2500L, wait.waitMs(values))
        assertEquals(1000L, wait.waitMs(ParamValues.of(wait.params, emptyMap())))
        assertEquals("2.5", CoreActions.formatSeconds(2500)); assertEquals("3", CoreActions.formatSeconds(3000))
    }

    @Test fun wifiOffWarnsOnlyWhileTheHotspotIsOn() {
        ActionFacts.resetForTest()
        val wifiOff = CoreActions.actions.first { it.id == "radio.wifi_off" }
        assertTrue(wifiOff.warnings(ParamValues.empty()).isEmpty())
        var active = false
        ActionFacts.provide(ActionFacts.Fact.HotspotActive) { active }
        assertTrue(wifiOff.warnings(ParamValues.empty()).isEmpty())
        active = true
        assertEquals(1, wifiOff.warnings(ParamValues.empty()).size)
        assertTrue(CoreActions.actions.first { it.id == "radio.wifi_on" }.warnings(ParamValues.empty()).isEmpty())
        ActionFacts.resetForTest()
    }

    // endregion
}
