package moe.n4tsu.dextop

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteCoordinateMapperTest {
    @Test
    fun mapsViewportPointToDisplaySpace() {
        val mapped = RemoteCoordinateMapper.mapViewportToDisplay(
            x = 512.0,
            y = 384.0,
            viewportWidth = 1024,
            viewportHeight = 768,
            displayWidth = 1920,
            displayHeight = 1080,
        )

        assertEquals(960f, mapped.x, 0.01f)
        assertEquals(540f, mapped.y, 0.01f)
    }

    @Test
    fun clampsCoordinatesToDisplayBounds() {
        val mapped = RemoteCoordinateMapper.mapViewportToDisplay(
            x = -200.0,
            y = 5000.0,
            viewportWidth = 1024,
            viewportHeight = 768,
            displayWidth = 1920,
            displayHeight = 1080,
        )

        assertEquals(0f, mapped.x, 0.01f)
        assertEquals(1079f, mapped.y, 0.01f)
    }
}
