package moe.n4tsu.dextop

internal object RemoteCoordinateMapper {
    data class Point(val x: Float, val y: Float)

    fun mapViewportToDisplay(
        x: Double,
        y: Double,
        viewportWidth: Int,
        viewportHeight: Int,
        displayWidth: Int,
        displayHeight: Int,
    ): Point {
        val safeViewportWidth = viewportWidth.coerceAtLeast(1)
        val safeViewportHeight = viewportHeight.coerceAtLeast(1)
        val safeDisplayWidth = displayWidth.coerceAtLeast(1)
        val safeDisplayHeight = displayHeight.coerceAtLeast(1)
        return Point(
            ((x.toFloat() / safeViewportWidth) * safeDisplayWidth).coerceIn(0f, safeDisplayWidth - 1f),
            ((y.toFloat() / safeViewportHeight) * safeDisplayHeight).coerceIn(0f, safeDisplayHeight - 1f),
        )
    }
}
