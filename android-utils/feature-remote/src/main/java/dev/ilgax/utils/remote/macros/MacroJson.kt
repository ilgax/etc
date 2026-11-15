package dev.ilgax.utils.remote.macros

import org.json.JSONArray
import org.json.JSONObject

/** What is in the store file: the macros plus the counters that must survive deletions. */
data class MacroData(val macros: List<Macro>, val nextId: Int, val revision: Int)

sealed interface MacroDecode {
    class Ok(val data: MacroData) : MacroDecode
    /** Written by a newer version of the app; must not be overwritten. */
    class Newer(val schema: Int) : MacroDecode
    data object Corrupt : MacroDecode
}

/** JSON form of the macro store, with a schema version. */
object MacroJson {
    const val SCHEMA = 1

    fun encode(data: MacroData): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("nextId", data.nextId)
        put("revision", data.revision)
        put("macros", JSONArray().also { arr -> data.macros.forEach { arr.put(macroToJson(it)) } })
    }.toString()

    fun macroToJson(m: Macro): JSONObject = JSONObject().apply {
        put("id", m.id)
        put("name", m.name)
        put("stopOnFailure", m.stopOnFailure)
        put("steps", JSONArray().also { arr ->
            for (s in m.steps) {
                arr.put(JSONObject().apply {
                    put("action", s.actionId)
                    put("params", JSONObject().also { p -> s.params.forEach { (k, v) -> p.put(k, v) } })
                })
            }
        })
    }

    fun decode(text: String): MacroDecode = try {
        val root = JSONObject(text)
        val schema = root.optInt("schema", 0)
        when {
            schema > SCHEMA -> MacroDecode.Newer(schema)
            schema < 1 -> MacroDecode.Corrupt
            else -> {
                val arr = root.getJSONArray("macros")
                val macros = List(arr.length()) { macroFromJson(arr.getJSONObject(it)) }
                val maxId = macros.maxOfOrNull { it.id } ?: 0
                MacroDecode.Ok(
                    MacroData(
                        macros,
                        // Never trust a stored counter below the ids in use.
                        nextId = maxOf(root.optInt("nextId", 1), maxId + 1, 1),
                        revision = root.optInt("revision", 0),
                    ),
                )
            }
        }
    } catch (e: Exception) {
        MacroDecode.Corrupt
    }

    fun macroFromJson(o: JSONObject): Macro {
        val steps = o.getJSONArray("steps")
        return Macro(
            id = o.getInt("id"),
            name = o.getString("name"),
            stopOnFailure = o.optBoolean("stopOnFailure", true),
            steps = List(steps.length()) { i ->
                val s = steps.getJSONObject(i)
                val params = s.optJSONObject("params")
                MacroStep(
                    s.getString("action"),
                    params?.keys()?.asSequence()?.associateWith { params.getString(it) } ?: emptyMap(),
                )
            },
        )
    }
}
