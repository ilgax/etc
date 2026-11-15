package dev.ilgax.utils.remote.macros

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MacroStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun macro(name: String = "Night", vararg ids: String) =
        Macro(0, name, ids.map { MacroStep(it, mapOf("percent" to "40")) })

    // region JSON

    @Test fun jsonRoundTripKeepsEverything() {
        val data = MacroData(
            listOf(
                Macro(3, "Gece Şişli İ", listOf(MacroStep("flow.wait", mapOf("ms" to "1500")), MacroStep("remote.lock")), stopOnFailure = false),
                Macro(9, "B", emptyList()),
            ),
            nextId = 12, revision = 5,
        )
        val back = (MacroJson.decode(MacroJson.encode(data)) as MacroDecode.Ok).data
        assertEquals(data, back)
    }

    @Test fun stopOnFailureDefaultsToTrueWhenMissing() {
        val json = """{"schema":1,"nextId":2,"revision":0,"macros":[{"id":1,"name":"x","steps":[]}]}"""
        assertTrue((MacroJson.decode(json) as MacroDecode.Ok).data.macros.single().stopOnFailure)
    }

    @Test fun unknownFieldsAreIgnored() {
        val json = """{"schema":1,"nextId":2,"revision":0,"future":true,"macros":[{"id":1,"name":"x","color":"red","steps":[{"action":"a","params":{},"later":1}]}]}"""
        assertEquals("a", (MacroJson.decode(json) as MacroDecode.Ok).data.macros.single().steps.single().actionId)
    }

    @Test fun newerSchemaIsReportedNotParsed() {
        val d = MacroJson.decode("""{"schema":2,"macros":"whatever"}""")
        assertEquals(2, (d as MacroDecode.Newer).schema)
    }

    @Test fun garbageAndMissingSchemaAreCorrupt() {
        assertEquals(MacroDecode.Corrupt, MacroJson.decode("not json"))
        assertEquals(MacroDecode.Corrupt, MacroJson.decode("""{"macros":[]}"""))
        assertEquals(MacroDecode.Corrupt, MacroJson.decode("""{"schema":1,"macros":[{"id":1}]}"""))
    }

    @Test fun nextIdIsNeverBelowTheIdsInUse() {
        val json = """{"schema":1,"nextId":1,"revision":0,"macros":[{"id":7,"name":"x","steps":[]}]}"""
        assertEquals(8, (MacroJson.decode(json) as MacroDecode.Ok).data.nextId)
    }

    // endregion

    // region store

    @Test fun idsAreUniqueAndNeverReused() {
        val store = MacroStore(File(tmp.root, "m.json"))
        val a = store.save(macro("A"))
        val b = store.save(macro("B"))
        assertEquals(listOf(1, 2), listOf(a.id, b.id))
        assertTrue(store.delete(b.id))
        val c = store.save(macro("C"))
        assertEquals(3, c.id)
        // Also across a restart.
        val reopened = MacroStore(File(tmp.root, "m.json"))
        assertEquals(listOf("A", "C"), reopened.macros.map { it.name })
        assertEquals(4, reopened.save(macro("D")).id)
    }

    @Test fun unlimitedList() {
        val store = MacroStore(File(tmp.root, "m.json"))
        repeat(300) { store.save(macro("M$it", "flow.wait")) }
        assertEquals(300, store.macros.size)
        assertEquals(300, MacroStore(File(tmp.root, "m.json")).macros.size)
    }

    @Test fun savingExistingIdReplaces() {
        val store = MacroStore(File(tmp.root, "m.json"))
        val a = store.save(macro("A", "x"))
        store.save(a.copy(name = "A2", steps = emptyList()))
        assertEquals(1, store.macros.size)
        assertEquals("A2", store.get(a.id)!!.name)
    }

    @Test fun everyChangeBumpsTheListVersionAndNotifies() {
        var notified = 0
        val store = MacroStore(File(tmp.root, "m.json")) { notified++ }
        val v0 = store.listVersion()
        val a = store.save(macro("A"))
        val v1 = store.listVersion()
        store.save(a.copy(steps = listOf(MacroStep("x")))) // a step-only edit still bumps
        val v2 = store.listVersion()
        store.delete(a.id)
        val v3 = store.listVersion()
        assertEquals(4, setOf(v0, v1, v2, v3).size)
        assertEquals(3, notified)
        assertEquals(3, store.state.value.revision)
    }

    @Test fun duplicateCopiesStepsAndKeepsNameWithinTheLimit() {
        val store = MacroStore(File(tmp.root, "m.json"))
        val a = store.save(Macro(0, "A".repeat(24), listOf(MacroStep("x", mapOf("k" to "v"))), stopOnFailure = false))
        val copy = store.duplicate(a.id)!!
        assertNotEquals(a.id, copy.id)
        assertEquals(a.steps, copy.steps)
        assertFalse(copy.stopOnFailure)
        assertTrue(copy.name.codePointCount(0, copy.name.length) <= MacroRules.MAX_NAME)
        assertTrue(copy.name.endsWith(" copy"))
        assertNull(store.duplicate(999))
    }

    @Test fun watchViewListsCleanedNames() {
        val store = MacroStore(File(tmp.root, "m.json"))
        store.save(Macro(0, " Hi\n", emptyList()))
        val (version, items) = store.watchView()
        assertEquals("Hi", items.single().name)
        assertEquals(version, store.listVersion())
    }

    @Test fun newerFileIsLeftUntouched() {
        val f = File(tmp.root, "m.json")
        val text = """{"schema":99,"macros":[]}"""
        f.writeText(text)
        val store = MacroStore(f)
        assertFalse(store.writable)
        assertThrows(IllegalStateException::class.java) { store.save(macro("A")) }
        assertEquals(text, f.readText())
    }

    @Test fun corruptFileStartsEmptyAndIsKept() {
        val f = File(tmp.root, "m.json")
        f.writeText("{ broken")
        val store = MacroStore(f)
        assertTrue(store.macros.isEmpty())
        assertTrue(File(tmp.root, "m.json.corrupt").exists())
        assertEquals(1, store.save(macro("A")).id)
    }

    // endregion

    @Test fun runLogKeepsTheLast20NewestFirstAndSurvivesRestart() {
        val f = File(tmp.root, "runs.json")
        val log = RunLog(f)
        repeat(25) { i ->
            log.add(RunRecord(1000L + i, 1, "M", Origin.Local, if (i == 24) 2 else 0, listOf(StepRecord(0, "a", "A", StepStatus.Ok, null, 5), StepRecord(1, "b", "B", if (i == 24) StepStatus.Failed else StepStatus.Skipped, "why", 7))))
        }
        assertEquals(20, log.runs.value.size)
        assertEquals(1024L, log.runs.value.first().startedAt)
        val back = RunLog(f).runs.value
        assertEquals(20, back.size)
        assertEquals(2, back.first().failedStep)
        assertEquals("why", back.first().steps[1].message)
        assertNull(back.first().steps[0].message)
        assertFalse(back.first().ok)
    }
}
