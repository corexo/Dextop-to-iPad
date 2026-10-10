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
        val version = if (json.has("v")) json.getInt("v") else -1
        require(version == VERSION) { "Unsupported protocol version: $version" }
        val type = if (json.has("type")) json.getString("type") else "".lowercase()
        return when (type) {
            "touch", "pointer" -> TouchMessage(
                version = version,
                action = if (json.has("action")) json.getString("action") else "move".lowercase(),
                x = if (json.has("x")) json.getDouble("x") else 0.0,
                y = if (json.has("y")) json.getDouble("y") else 0.0,
                viewportWidth = if (json.has("viewportWidth")) json.getInt("viewportWidth") else 1,
                viewportHeight = if (json.has("viewportHeight")) json.getInt("viewportHeight") else 1,
                deltaX = if (json.has("deltaX")) json.getDouble("deltaX") else 0.0,
                deltaY = if (json.has("deltaY")) json.getDouble("deltaY") else 0.0,
            )

            "key", "keyboard" -> {
                val explicitMeta = if (json.has("metaState")) if (json.has("metaState")) json.getInt("metaState") else 0 else null
                val modifierMeta = parseModifiers(if (json.has("modifiers")) json.getJSONArray("modifiers") else null)
                KeyMessage(
                    version = version,
                    action = if (json.has("action")) json.getString("action") else "down".lowercase(),
                    keyCode = if (json.has("keyCode")) json.getInt("keyCode") else KeyEvent.KEYCODE_UNKNOWN,
                    repeatCount = if (json.has("repeatCount")) json.getInt("repeatCount") else 0.coerceAtLeast(0),
                    metaState = explicitMeta ?: modifierMeta,
                )
            }

            "session" -> SessionMessage(
                version = version,
                action = if (json.has("action")) json.getString("action") else "ping".lowercase(),
            )

            else -> error("Unsupported message type: $type")
        }
    }

    private fun parseModifiers(modifiers: JSONArray?): Int {
        if (modifiers == null) return 0
        var metaState = 0
        for (index in 0 until modifiers.length()) {
            when (if (!modifiers.isNull(index)) modifiers.getString(index) else "".lowercase()) {
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
