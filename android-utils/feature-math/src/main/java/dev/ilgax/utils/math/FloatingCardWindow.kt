package dev.ilgax.utils.math

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.ilgax.utils.core.a11y.A11y
import dev.ilgax.utils.core.ui.theme.UtilsTheme
import dev.ilgax.utils.math.normalize.CardPlacement
import dev.ilgax.utils.math.normalize.PxRect
import kotlin.math.hypot

/**
 * The result card as a window of its own, above whatever app is underneath (Flexcil), so the PDF stays usable.
 *
 * [type] decides who may show it:
 *  - TYPE_ACCESSIBILITY_OVERLAY from the shared "Utils" accessibility service ([A11y.overlayContext]): no permission needed;
 *  - TYPE_APPLICATION_OVERLAY, [base] = [SolveOverlayService]: needs "Display over other apps".
 * Everything else is identical, which is why this is one class.
 *
 * Touch behaviour: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL, so taps and S Pen strokes outside the card reach the
 * app below. Only while the LaTeX field is edited is NOT_FOCUSABLE cleared (so the keyboard works); an outside tap
 * (FLAG_WATCH_OUTSIDE_TOUCH), the keyboard's Done key or the keyboard closing restores it.
 * Main thread only.
 */
class FloatingCardWindow(
    base: Context,
    private val type: Int,
    private val floating: FloatingState,
    private val actions: OverlayActions,
    /** The window gave up keyboard focus on its own (outside tap, keyboard dismissed). */
    private val onEditingEnded: () -> Unit,
) {
    private val context: Context = if (type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
        // The shared "Utils" service's overlay token: no permission needed.
        A11y.overlayContext() ?: throw IllegalStateException("accessibility service is not connected")
    } else {
        base.createDisplayContext(base.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
            .createWindowContext(type, null)
    }
    private val wm: WindowManager = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density
    private val owner = OverlayOwner()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val margin = 12 * density
    private val gap = 14 * density

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        type,
        FLAGS_PASSIVE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        title = "Solve card"
    }

    private var root: Root? = null
    private var userMoved = false
    private var editing = false
    private var imeWasVisible = false
    private var imeBottomInset = 0
    private var dragZone: Rect? = null
    private var limits by mutableStateOf(FloatingLimits(360.dp, 560.dp))

    val isShown get() = root != null

    fun show() {
        if (root != null) return
        val r = Root()
        r.setViewTreeLifecycleOwner(owner)
        r.setViewTreeSavedStateRegistryOwner(owner)
        r.setViewTreeViewModelStoreOwner(owner)
        val windowActions = actions.copyWith(onDragZone = { b -> dragZone = b?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) } })
        val compose = ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                UtilsTheme {
                    FloatingWindowContent(floating, limits, windowActions)
                }
            }
        }
        r.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        r.visibility = View.INVISIBLE // until the first placement, so it does not flash at 0,0
        r.addOnLayoutChangeListener { _, l, t, rt, b, ol, ot, ort, ob -> if (rt - l != ort - ol || b - t != ob - ot) place() }
        r.setOnApplyWindowInsetsListener { v, insets ->
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            val visible = ime > 0
            if (editing && imeWasVisible && !visible) onEditingEnded()
            imeWasVisible = visible
            if (ime != imeBottomInset) { imeBottomInset = ime; place() }
            v.onApplyWindowInsets(insets)
        }
        root = r
        updateLimits()
        owner.start()
        wm.addView(r, params)
        owner.resume()
    }

    fun remove() {
        val r = root ?: return
        root = null
        try { wm.removeViewImmediate(r) } catch (_: Throwable) {}
        owner.destroy()
    }

    /** Called when the card content or its anchor changed size: put it beside the circle again (unless the user moved it). */
    fun requestPlacement() { root?.post { place() } }

    /** Keyboard focus only while the LaTeX field is being edited. */
    fun setEditing(on: Boolean) {
        if (editing == on) return
        editing = on
        params.flags = if (on) FLAGS_PASSIVE and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() else FLAGS_PASSIVE
        val r = root ?: return
        try { wm.updateViewLayout(r, params) } catch (_: Throwable) {}
        if (!on) r.windowInsetsController?.hide(WindowInsets.Type.ime())
    }

    private fun usableBounds(): PxRect {
        val m = wm.currentWindowMetrics
        val b = m.bounds
        val i = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return PxRect(
            (b.left + i.left).toFloat(), (b.top + i.top).toFloat(),
            (b.right - i.right).toFloat(), (b.bottom - maxOf(i.bottom, imeBottomInset)).toFloat(),
        )
    }

    private fun updateLimits() {
        val b = usableBounds()
        limits = FloatingLimits(
            width = minOf(420f, b.width / density - 24f).dp,
            maxHeight = (b.height / density - 24f).coerceAtLeast(120f).dp,
        )
    }

    private fun place() {
        val r = root ?: return
        val w = r.width
        val h = r.height
        if (w == 0 || h == 0) return
        updateLimits()
        val bounds = usableBounds()
        val anchor = floating.anchor
        val target = if (!userMoved && anchor != null) {
            CardPlacement.placeIn(anchor, bounds, w.toFloat(), h.toFloat(), margin, gap).let { it.x to it.y }
        } else {
            CardPlacement.clampInto(params.x.toFloat(), params.y.toFloat(), w.toFloat(), h.toFloat(), bounds, margin).let { it.x to it.y }
        }
        val nx = target.first.toInt()
        val ny = target.second.toInt()
        if (nx != params.x || ny != params.y) {
            params.x = nx
            params.y = ny
            try { wm.updateViewLayout(r, params) } catch (_: Throwable) {}
        }
        if (r.visibility != View.VISIBLE) r.visibility = View.VISIBLE
    }

    private fun moveTo(x: Float, y: Float) {
        val r = root ?: return
        val p = CardPlacement.clampInto(x, y, r.width.toFloat(), r.height.toFloat(), usableBounds(), 0f)
        params.x = p.x.toInt()
        params.y = p.y.toInt()
        try { wm.updateViewLayout(r, params) } catch (_: Throwable) {}
    }

    /** Window root: drags the card by its header (or the whole pill) and reports outside taps. */
    private inner class Root : FrameLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var candidate = false
        private var dragging = false

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE && editing) onEditingEnded()
            return super.dispatchTouchEvent(ev)
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                    candidate = dragZone?.contains(ev.x.toInt(), ev.y.toInt()) == true
                }
                MotionEvent.ACTION_MOVE ->
                    if (candidate && !dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) { dragging = true; userMoved = true }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> moveTo(startX + (ev.rawX - downX), startY + (ev.rawY - downY))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }

    /** A ComposeView outside an activity needs these three owners on the view tree before it attaches. */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val viewModelStore = ViewModelStore()
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry

        init {
            saved.performAttach()
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.CREATED
        }

        fun start() { registry.currentState = Lifecycle.State.STARTED }
        fun resume() { registry.currentState = Lifecycle.State.RESUMED }
        fun destroy() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private companion object {
        const val FLAGS_PASSIVE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
    }
}
