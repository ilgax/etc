package dev.ilgax.utils.math.normalize

/** Axis-aligned rectangle in window pixels. */
data class PxRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
}

enum class CardSide { Below, Above, Right, Left }

data class CardPlacementResult(val x: Float, val y: Float, val side: CardSide)

data class PxPoint(val x: Float, val y: Float)

/** Where the result card goes relative to the circled area: the first side with room, clamped on screen. */
object CardPlacement {
    fun place(
        anchor: PxRect,
        containerWidth: Float,
        containerHeight: Float,
        cardWidth: Float,
        cardHeight: Float,
        margin: Float,
        gap: Float,
    ): CardPlacementResult {
        val room = mapOf(
            CardSide.Below to containerHeight - anchor.bottom - gap - margin - cardHeight,
            CardSide.Above to anchor.top - gap - margin - cardHeight,
            CardSide.Right to containerWidth - anchor.right - gap - margin - cardWidth,
            CardSide.Left to anchor.left - gap - margin - cardWidth,
        )
        // Below first (the writing hand covers less of it), then above, then the sides.
        val side = listOf(CardSide.Below, CardSide.Above, CardSide.Right, CardSide.Left).firstOrNull { room.getValue(it) >= 0f }
            ?: room.maxByOrNull { it.value }!!.key
        val x: Float
        val y: Float
        when (side) {
            CardSide.Below -> { x = anchor.centerX - cardWidth / 2; y = anchor.bottom + gap }
            CardSide.Above -> { x = anchor.centerX - cardWidth / 2; y = anchor.top - gap - cardHeight }
            CardSide.Right -> { x = anchor.right + gap; y = anchor.centerY - cardHeight / 2 }
            CardSide.Left -> { x = anchor.left - gap - cardWidth; y = anchor.centerY - cardHeight / 2 }
        }
        return CardPlacementResult(clamp(x, margin, containerWidth - cardWidth - margin), clamp(y, margin, containerHeight - cardHeight - margin), side)
    }

    /**
     * [place] for a container that does not start at (0, 0), e.g. the usable part of the screen between the
     * system bars. [anchor] and the result are in the same (screen) coordinates as [bounds].
     */
    fun placeIn(anchor: PxRect, bounds: PxRect, cardWidth: Float, cardHeight: Float, margin: Float, gap: Float): CardPlacementResult {
        val local = PxRect(anchor.left - bounds.left, anchor.top - bounds.top, anchor.right - bounds.left, anchor.bottom - bounds.top)
        val p = place(local, bounds.width, bounds.height, cardWidth, cardHeight, margin, gap)
        return p.copy(x = p.x + bounds.left, y = p.y + bounds.top)
    }

    /** Where a dragged (or keyboard-shifted) card of the given size may sit: fully inside [bounds] minus [margin]. */
    fun clampInto(x: Float, y: Float, cardWidth: Float, cardHeight: Float, bounds: PxRect, margin: Float): PxPoint =
        PxPoint(
            clamp(x, bounds.left + margin, bounds.right - cardWidth - margin),
            clamp(y, bounds.top + margin, bounds.bottom - cardHeight - margin),
        )

    private fun clamp(v: Float, lo: Float, hi: Float) = if (hi < lo) lo else v.coerceIn(lo, hi)
}
