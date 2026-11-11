package com.mina.legadostudio.verification

/** Screen-local geometry. Half docking uses an in-bounds clipped window, not negative WM coordinates. */
class OverlayGeometry {
    var x = 0
    var y = 0
    var side = 1
        private set
    var hidden = false
        private set
    private var screenW = 1
    private var screenH = 1
    private var fullWidth = 1
    private var height = 1
    val width: Int get() = if (hidden) (fullWidth / 2).coerceAtLeast(1) else fullWidth

    fun resize(w: Int, h: Int, ballWidth: Int, ballHeight: Int) {
        screenW = w.coerceAtLeast(1); screenH = h.coerceAtLeast(1)
        fullWidth = ballWidth.coerceIn(1, screenW); height = ballHeight.coerceIn(1, screenH)
        anchor()
    }
    private fun anchor() {
        x = when (side) { -1 -> 0; 1 -> screenW - width; else -> x.coerceIn(0, screenW - width) }
        y = y.coerceIn(0, (screenH - height).coerceAtLeast(0))
    }
    fun move(newX: Int, newY: Int) {
        hidden = false; side = 0; x = newX; y = newY; anchor()
    }
    fun release() {
        side = when { x < fullWidth / 2 -> -1; screenW - x - fullWidth < fullWidth / 2 -> 1; else -> 0 }
        anchor()
    }
    fun collapse() { if (side != 0) hidden = true; anchor() }
    fun expand() { hidden = false; anchor() }
    /** false consumes the first tap exclusively to reveal the ball. */
    fun tap(): Boolean { if (hidden) { expand(); return false }; return true }
}
