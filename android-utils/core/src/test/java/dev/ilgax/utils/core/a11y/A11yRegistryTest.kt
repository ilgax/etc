package dev.ilgax.utils.core.a11y

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class A11yRegistryTest {
    private class Recorder(val name: String, val log: MutableList<String>) : A11yFeature {
        override fun onConnected(service: AccessibilityService) { log += "$name:connected" }
        override fun onEvent(event: AccessibilityEvent) { log += "$name:event" }
        override fun onDisconnected() { log += "$name:disconnected" }
    }

    /** The dispatch only passes the instances around, so objects built without running any constructor are enough. */
    private fun <T> blank(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private fun fakeService(): AccessibilityService = object : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }
    private fun fakeEvent(): AccessibilityEvent = blank(AccessibilityEvent::class.java)

    @After fun reset() = A11y.resetForTest()

    @Test fun dispatchReachesEveryFeatureInRegistrationOrder() {
        val log = ArrayList<String>()
        A11y.register(Recorder("remote", log))
        A11y.register(Recorder("math", log))
        val s = fakeService()
        A11y.dispatchConnected(s)
        A11y.dispatchEvent(fakeEvent())
        A11y.dispatchDisconnected(s)
        assertEquals(
            listOf("remote:connected", "math:connected", "remote:event", "math:event", "remote:disconnected", "math:disconnected"),
            log,
        )
    }

    @Test fun serviceStateFollowsConnection() {
        assertNull(A11y.service.value)
        val s = fakeService()
        A11y.dispatchConnected(s)
        assertSame(s, A11y.service.value)
        A11y.dispatchDisconnected(s)
        assertNull(A11y.service.value)
    }

    @Test fun lateRegistrationIsToldAboutTheRunningService() {
        val log = ArrayList<String>()
        A11y.dispatchConnected(fakeService())
        A11y.register(Recorder("late", log))
        assertEquals(listOf("late:connected"), log)
    }

    @Test fun registeringTwiceDeliversOnce() {
        val log = ArrayList<String>()
        val f = Recorder("a", log)
        A11y.register(f); A11y.register(f)
        A11y.dispatchEvent(fakeEvent())
        assertEquals(listOf("a:event"), log)
    }

    @Test fun staleServiceDisconnectIsIgnored() {
        val log = ArrayList<String>()
        A11y.register(Recorder("a", log))
        val old = fakeService(); val fresh = fakeService()
        A11y.dispatchConnected(old)
        A11y.dispatchConnected(fresh)
        A11y.dispatchDisconnected(old) // the old instance dies after the new one connected
        assertSame(fresh, A11y.service.value)
        assertEquals(listOf("a:connected", "a:connected"), log)
    }

    @Test fun unregisteredFeatureGetsNothing() {
        val log = ArrayList<String>()
        val f = Recorder("a", log)
        A11y.register(f); A11y.unregister(f)
        A11y.dispatchEvent(fakeEvent())
        assertEquals(emptyList<String>(), log)
    }
}
