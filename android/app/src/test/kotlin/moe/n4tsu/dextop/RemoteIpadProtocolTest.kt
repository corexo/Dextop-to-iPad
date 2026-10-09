package moe.n4tsu.dextop

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIpadProtocolTest {
    @Test
    fun parsesTouchMessage() {
        val message = RemoteIpadProtocol.parse(
            """{"v":1,"type":"touch","action":"move","x":120.5,"y":88.25,"viewportWidth":300,"viewportHeight":200}"""
        )

        assertTrue(message is RemoteIpadProtocol.TouchMessage)
        message as RemoteIpadProtocol.TouchMessage
        assertEquals("move", message.action)
        assertEquals(120.5, message.x, 0.0)
        assertEquals(88.25, message.y, 0.0)
        assertEquals(300, message.viewportWidth)
        assertEquals(200, message.viewportHeight)
    }

    @Test
    fun encodesAndParsesSessionMessage() {
        val encoded = RemoteIpadProtocol.encode(
            RemoteIpadProtocol.SessionMessage(
                version = RemoteIpadProtocol.VERSION,
                action = "ping",
            )
        )

        val parsed = RemoteIpadProtocol.parse(encoded)
        assertTrue(parsed is RemoteIpadProtocol.SessionMessage)
        parsed as RemoteIpadProtocol.SessionMessage
        assertEquals("ping", parsed.action)
    }

    @Test
    fun parsesKeyModifiersIntoMetaState() {
        val message = RemoteIpadProtocol.parse(
            """{"v":1,"type":"key","action":"down","keyCode":29,"modifiers":["shift","ctrl"]}"""
        )

        assertTrue(message is RemoteIpadProtocol.KeyMessage)
        message as RemoteIpadProtocol.KeyMessage
        assertEquals(KeyEvent.KEYCODE_A, message.keyCode)
        assertTrue(message.metaState and KeyEvent.META_SHIFT_ON != 0)
        assertTrue(message.metaState and KeyEvent.META_CTRL_ON != 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedVersion() {
        RemoteIpadProtocol.parse("""{"v":2,"type":"session"}""")
    }
}
