package dev.ilgax.utils.core.action

import dev.ilgax.utils.core.UiText

/**
 * One typed parameter of an [ActionType]. Values are stored as strings (a step is `actionId` + a string map, so the
 * JSON stays trivial and forward compatible); the spec says how to read, validate and edit them.
 */
sealed class ParamSpec(val key: String, val label: UiText) {
    /** Value used when a step does not carry this key. */
    abstract val default: String

    /** Null when [raw] is a usable value, else a short reason. Missing values count as [default]. */
    abstract fun error(raw: String): String?

    /** [raw] if it is valid, else [default]. */
    fun coerce(raw: String?): String = if (raw != null && error(raw) == null) raw else default

    /** A whole number in [min]..[max]. */
    class IntRange(key: String, label: UiText, val min: Int, val max: Int, val initial: Int, val unit: UiText? = null) : ParamSpec(key, label) {
        override val default get() = initial.toString()
        override fun error(raw: String) = raw.toIntOrNull()?.takeIf { it in min..max }.let { if (it == null) "not in $min..$max" else null }
    }

    /** 0..100 percent. */
    class Percent(key: String, label: UiText, val initial: Int = 50) : ParamSpec(key, label) {
        override val default get() = initial.toString()
        override fun error(raw: String) = raw.toIntOrNull()?.takeIf { it in 0..100 }.let { if (it == null) "not a percentage" else null }
    }

    /** A duration stored in milliseconds, edited in seconds in [stepMs] steps. */
    class Seconds(key: String, label: UiText, val minMs: Int = 500, val maxMs: Int = 30_000, val initialMs: Int = 1_000, val stepMs: Int = 500) :
        ParamSpec(key, label) {
        override val default get() = initialMs.toString()
        override fun error(raw: String) = raw.toIntOrNull()?.takeIf { it in minMs..maxMs && (it - minMs) % stepMs == 0 }
            .let { if (it == null) "not a valid duration" else null }
    }

    class Bool(key: String, label: UiText, val initial: Boolean = false) : ParamSpec(key, label) {
        override val default get() = initial.toString()
        override fun error(raw: String) = if (raw == "true" || raw == "false") null else "not a boolean"
    }

    class Option(val value: String, val label: UiText)

    /** One of [options]. */
    class Choice(key: String, label: UiText, val options: List<Option>, val initial: String = options.first().value) : ParamSpec(key, label) {
        override val default get() = initial
        override fun error(raw: String) = if (options.any { it.value == raw }) null else "unknown option"
    }

    /** An installed app's package name. Has no default: the step is invalid until one is chosen. */
    class AppPackage(key: String, label: UiText) : ParamSpec(key, label) {
        override val default get() = ""
        override fun error(raw: String) = if (PACKAGE.matches(raw)) null else "choose an app"
    }

    /** Free text; [url] restricts it to http(s) links. */
    class Text(key: String, label: UiText, val url: Boolean = false, val maxLength: Int = 512) : ParamSpec(key, label) {
        override val default get() = ""
        override fun error(raw: String): String? = when {
            raw.isBlank() -> "empty"
            raw.length > maxLength -> "too long"
            url && !isWebUrl(raw) -> "not an http(s) link"
            else -> null
        }
    }

    /** A watch-remote app slot, stored 0-based. */
    class AppSlot(key: String, label: UiText, val slots: Int = 4) : ParamSpec(key, label) {
        override val default get() = "0"
        override fun error(raw: String) = raw.toIntOrNull()?.takeIf { it in 0 until slots }.let { if (it == null) "no such slot" else null }
    }

    companion object {
        val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

        fun isWebUrl(raw: String): Boolean {
            val t = raw.trim()
            val lower = t.lowercase()
            return (lower.startsWith("http://") || lower.startsWith("https://")) && t.length > 8 && ' ' !in t
        }
    }
}

/** The values of one step, completed with defaults and checked against the action's specs. */
class ParamValues private constructor(private val values: Map<String, String>) {
    fun string(key: String): String = values[key] ?: ""
    fun int(key: String): Int = values[key]?.toIntOrNull() ?: 0
    fun bool(key: String): Boolean = values[key] == "true"
    fun asMap(): Map<String, String> = values

    companion object {
        /** Every spec key present: the stored value when valid, else the default. */
        fun of(specs: List<ParamSpec>, stored: Map<String, String>): ParamValues =
            ParamValues(specs.associate { it.key to it.coerce(stored[it.key]) })

        fun empty() = ParamValues(emptyMap())

        /** Problems with the stored values: one entry per spec whose stored value is present but invalid, or has no usable default. */
        fun problems(specs: List<ParamSpec>, stored: Map<String, String>): List<Pair<ParamSpec, String>> =
            specs.mapNotNull { spec ->
                val raw = stored[spec.key] ?: spec.default
                spec.error(raw)?.let { spec to it }
            }
    }
}
