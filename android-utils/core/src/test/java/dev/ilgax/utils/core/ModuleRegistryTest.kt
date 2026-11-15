package dev.ilgax.utils.core

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

private class FakeModule(override val id: String) : UtilModule {
    override val title = id
    override val summary = ""
    override val iconRes = 0
    override val cardState: StateFlow<ModuleCardState> = MutableStateFlow(ModuleCardState(CardMode.Off, ""))
    override suspend fun toggle() = Unit

    @Composable
    override fun Detail(contentPadding: PaddingValues, modifier: Modifier) = Unit
}

class ModuleRegistryTest {
    @Test
    fun keepsRegistrationOrder() {
        val registry = ModuleRegistry.of(FakeModule("b"), FakeModule("a"), FakeModule("c"))
        assertEquals(listOf("b", "a", "c"), registry.modules.map { it.id })
    }

    @Test
    fun findsById() {
        val a = FakeModule("a")
        val registry = ModuleRegistry.of(a, FakeModule("b"))
        assertSame(a, registry.find("a"))
        assertNull(registry.find("missing"))
        assertNull(registry.find(null))
    }

    @Test
    fun rejectsDuplicateIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ModuleRegistry.of(FakeModule("a"), FakeModule("a"))
        }
    }

    @Test
    fun emptyRegistryIsFine() {
        assertEquals(0, ModuleRegistry(emptyList()).modules.size)
    }
}
