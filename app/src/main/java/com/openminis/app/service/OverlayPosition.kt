package com.openminis.app.service

internal object OverlayPosition {

    data class Point(val x: Int, val y: Int)

    enum class Orientation {
        PORTRAIT, LANDSCAPE;

        companion object {
            fun of(screenW: Int, screenH: Int): Orientation = if (screenW > screenH) LANDSCAPE else PORTRAIT
        }
    }

    data class Avoid(val left: Int = 0, val right: Int = 0, val bottom: Int = 0, val top: Int = 0) {
        companion object {
            val NONE = Avoid()
        }
    }

    interface Store {
        fun get(orientation: Orientation): Point?
        fun set(orientation: Orientation, point: Point)
    }

    fun avoidFor(orientation: Orientation, left: Int, right: Int, bottom: Int, top: Int = 0): Avoid =
        if (orientation == Orientation.LANDSCAPE) {
            Avoid(left.coerceAtLeast(0), right.coerceAtLeast(0), bottom.coerceAtLeast(0), top.coerceAtLeast(0))
        } else {
            Avoid(top = top.coerceAtLeast(0))
        }

    fun displayed(store: Store, default: Point, screenW: Int, screenH: Int, capsuleW: Int, capsuleH: Int, avoid: Avoid): Point =
        restore(store.get(Orientation.of(screenW, screenH)), default, screenW, screenH, capsuleW, capsuleH, avoid)

    fun dragEnded(store: Store, screenW: Int, screenH: Int, point: Point) =
        store.set(Orientation.of(screenW, screenH), point)

    fun restore(
        saved: Point?,
        default: Point,
        screenW: Int,
        screenH: Int,
        capsuleW: Int,
        capsuleH: Int,
        avoid: Avoid = Avoid.NONE,
    ): Point {
        val at = saved ?: default
        if (saved == null && avoid == Avoid.NONE) return default
        return clamp(at.x, at.y, screenW, screenH, capsuleW, capsuleH, avoid)
    }

    fun clamp(x: Int, y: Int, screenW: Int, screenH: Int, capsuleW: Int, capsuleH: Int, avoid: Avoid = Avoid.NONE): Point =
        Point(
            clampAxis(x, screenW, capsuleW, avoidStart = avoid.left, avoidEnd = avoid.right),
            clampAxis(y, screenH, capsuleH, avoidStart = avoid.top, avoidEnd = avoid.bottom, wholeAtStart = true),
        )

    private fun clampAxis(pos: Int, screen: Int, size: Int, avoidStart: Int, avoidEnd: Int, wholeAtStart: Boolean = false): Int {
        val min = if (avoidStart > 0 || wholeAtStart) avoidStart.coerceAtLeast(0) else -(size / 2)
        val max = (if (avoidEnd > 0) screen - avoidEnd - size else screen - (size - size / 2)).coerceAtLeast(min)
        return pos.coerceIn(min, max)
    }
}
