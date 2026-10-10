package moe.n4tsu.dextop

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject

internal object RemoteIpadProtocol {
    const val VERSION = 1

    sealed interface Message {
        val version: Int
    }

    data class TouchMessage(
        override val version: Int,
        val action: String,
        val x: Double,
        val y: Double,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val deltaX: Double,
        val deltaY: Double,
    ) : Message

    data class KeyMessage(
        override val version: Int,
        val action: String,
        val keyCode: Int,
        val repeatCount: Int,
        val metaState: Int,
    ) : Message

    data class SessionMessage(
        override val version: Int,
        val action: String,
    ) : Message

    fun encode(message: Message): String = when (message) {
        is TouchMessage -> JSONObject().apply {
            put("v", message.version)
            put("type", "touch")
            put("action", message.action)
            put("x", message.x)
            put("y", message.y)
            put("viewportWidth", message.viewportWidth)
            put("viewportHeight", message.viewportHeight)
            put("deltaX", message.deltaX)
            put("deltaY", message.deltaY)
        }.toString()

        is KeyMessage -> JSONObject().apply {
            put("v", message.version)
            put("type", "key")
            put("action", message.action)
            put("keyCode", message.keyCode)
            put("repeatCount", message.repeatCount)
            put("metaState", message.metaState)
        }.toString()

        is SessionMessage -> JSONObject().apply {
            put("v", message.version)
            put("type", "session")
            put("action", message.action)
        }.toString()
    }

    fun parse(raw: String): Message {
        val json = JSONObject(raw)
        val version = json.optInt("v", -1)
        require(version == VERSION) { "Unsupported protocol version: $version" }
        val type = json.optString("type").lowercase()
        return when (type) {
            "touch", "pointer" -> TouchMessage(
                version = version,
                action = json.optString("action", "move").lowercase(),
                x = json.optDouble("x", 0.0),
                y = json.optDouble("y", 0.0),
                viewportWidth = json.optInt("viewportWidth", 1),
                viewportHeight = json.optInt("viewportHeight", 1),
                deltaX = json.optDouble("deltaX", 0.0),
                deltaY = json.optDouble("deltaY", 0.0),
            )

            "key", "keyboard" -> {
                val explicitMeta = if (json.has("metaState")) json.optInt("metaState", 0) else null
                val modifierMeta = parseModifiers(json.optJSONArray("modifiers"))
                KeyMessage(
                    version = version,
                    action = json.optString("action", "down").lowercase(),
                    keyCode = json.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN),
                    repeatCount = json.optInt("repeatCount", 0).coerceAtLeast(0),
                    metaState = explicitMeta ?: modifierMeta,
                )
            }

            "session" -> SessionMessage(
                version = version,
                action = json.optString("action", "ping").lowercase(),
            )

            else -> error("Unsupported message type: $type")
        }
    }

    private fun parseModifiers(modifiers: JSONArray?): Int {
        if (modifiers == null) return 0
        var metaState = 0
        for (index in 0 until modifiers.length()) {
            when (modifiers.optString(index, "").lowercase()) {
                "shift" -> metaState = metaState or KeyEvent.META_SHIFT_ON
                "ctrl", "control" -> metaState = metaState or KeyEvent.META_CTRL_ON
                "alt", "option" -> metaState = metaState or KeyEvent.META_ALT_ON
                "meta", "command", "cmd" -> metaState = metaState or KeyEvent.META_META_ON
                "caps", "capslock" -> metaState = metaState or KeyEvent.META_CAPS_LOCK_ON
                "num", "numlock" -> metaState = metaState or KeyEvent.META_NUM_LOCK_ON
                "sym" -> metaState = metaState or KeyEvent.META_SYM_ON
                "function", "fn" -> metaState = metaState or KeyEvent.META_FUNCTION_ON
            }
        }
        return metaState
    }
}
