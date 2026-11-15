package dev.ilgax.utils.core.action

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import dev.ilgax.utils.core.R
import dev.ilgax.utils.core.UiText

/** How a step ended. Reasons are shown to the user (editor history, failure notification). */
sealed interface StepResult {
    data object Ok : StepResult
    data class Failed(val reason: UiText) : StepResult
    /** Nothing to do (for example nothing to close); the macro carries on whatever its stop-on-failure setting. */
    data class Skipped(val reason: UiText) : StepResult

    companion object {
        fun failed(@StringRes id: Int, vararg args: Any): StepResult = Failed(UiText.Res(id, args.toList()))
        fun skipped(@StringRes id: Int, vararg args: Any): StepResult = Skipped(UiText.Res(id, args.toList()))
    }
}

/** The picker's categories, in display order. */
enum class ActionGroup(@StringRes val labelRes: Int) {
    WatchRemote(R.string.group_remote),
    Flow(R.string.group_flow),
    Media(R.string.group_media),
    System(R.string.group_system),
    Screen(R.string.group_screen),
    Network(R.string.group_network),
    Apps(R.string.group_apps),
}

/**
 * One kind of macro step. Modules contribute theirs through an [ActionProvider]; [id] is stored in saved macros, so it
 * never changes once shipped.
 */
interface ActionType {
    /** Stable, lower-case, dotted ("remote.lock"). Saved in macros. */
    val id: String
    val label: UiText
    val group: ActionGroup

    @get:DrawableRes
    val iconRes: Int
    val params: List<ParamSpec> get() = emptyList()

    /** Everything the step needs; each entry may be satisfiable in more than one way (see [Requirement]). */
    val requirements: List<Requirement> get() = emptyList()

    /** Requirements that depend on the chosen values (for example only some ringer modes need a permission). */
    fun requirementsFor(values: ParamValues): List<Requirement> = requirements

    /** Non-blocking caveats for the editor, such as "the hotspot is on". */
    fun warnings(values: ParamValues): List<UiText> = emptyList()

    /** A short phrase for lists ("Brightness 40%"); the label by default. */
    fun summary(context: Context, values: ParamValues): String = label.resolve(context)

    /** Milliseconds this step spends waiting; summed and limited per macro. */
    fun waitMs(values: ParamValues): Long = 0L

    /** Runs the step. Suspends; called on a background dispatcher. Must not throw for ordinary failures. */
    suspend fun run(context: Context, params: ParamValues): StepResult
}

/** A module's contribution to the macro editor. Registered from the module's init, like an A11yFeature. */
interface ActionProvider {
    /** Unique per provider; registering the same id again replaces the earlier provider. */
    val id: String
    val actions: List<ActionType>
}

/** [ActionType] built from parts, so a module declares its steps without a class each. */
class SimpleAction(
    override val id: String,
    override val label: UiText,
    override val group: ActionGroup,
    @DrawableRes override val iconRes: Int,
    override val params: List<ParamSpec> = emptyList(),
    override val requirements: List<Requirement> = emptyList(),
    private val requirementsFn: ((ParamValues) -> List<Requirement>)? = null,
    private val warningsFn: ((ParamValues) -> List<UiText>)? = null,
    private val summaryFn: ((Context, ParamValues) -> String)? = null,
    private val waitFn: ((ParamValues) -> Long)? = null,
    private val body: suspend (Context, ParamValues) -> StepResult,
) : ActionType {
    override fun requirementsFor(values: ParamValues) = requirementsFn?.invoke(values) ?: requirements
    override fun warnings(values: ParamValues) = warningsFn?.invoke(values) ?: emptyList()
    override fun summary(context: Context, values: ParamValues) = summaryFn?.invoke(context, values) ?: label.resolve(context)
    override fun waitMs(values: ParamValues) = waitFn?.invoke(values) ?: 0L
    override suspend fun run(context: Context, params: ParamValues) = body(context, params)
}
