package moe.n4tsu.dextop

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal class RemoteIpadSession(
    private val context: Context,
    private val privilegedAccess: PrivilegedAccess,
    private val targetDisplayId: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val density: Int,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "Dextop-remote-ipad").apply { isDaemon = true }
    }
    private val token = randomToken()
    private val expiresAtMs = System.currentTimeMillis() + TOKEN_TTL_MS
    private val inputDispatcher = InputDispatcher(privilegedAccess)
    private var touchDownTime = 0L

    private var streamer: CastCompatibilityStreamer? = null
    private var controlServer: ServerSocket? = null
    private var videoUrl: String? = null
    private var controlUrl: String? = null

    @Volatile
    private var clientConnected = false

    @Volatile
    private var lastClientSeenAt = 0L

    fun start(): Map<String, Any> {
        check(running.compareAndSet(false, true)) { "Remote iPad session is already running" }
        val castStreamer = CastCompatibilityStreamer(
            context = context,
            privilegedAccess = privilegedAccess,
            sourceDisplayId = targetDisplayId,
            sourceWidth = targetWidth,
            sourceHeight = targetHeight,
            density = density,
        )
        streamer = castStreamer
        val streamUrl = castStreamer.start()
        val host = Uri.parse(streamUrl).host ?: error("Unable to resolve local stream host")
        val server = ServerSocket(0)
        controlServer = server
        controlUrl = "http://$host:${server.localPort}/v1/input"
        videoUrl = streamUrl
        executor.execute { acceptLoop(server) }
        OperationLog.i(
            context,
            "RemoteIpad",
            "remote iPad session ready display=$targetDisplayId video=$streamUrl control=$controlUrl",
        )
        return status()
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { controlServer?.close() }
        runCatching { streamer?.stop() }
        controlServer = null
        streamer = null
        clientConnected = false
        executor.shutdownNow()
        OperationLog.i(context, "RemoteIpad", "remote iPad session stopped")
    }

    fun status(): Map<String, Any> {
        val stream = videoUrl.orEmpty()
        val control = controlUrl.orEmpty()
        val pairingPayload = mapOf(
            "v" to RemoteIpadProtocol.VERSION,
            "videoUrl" to stream,
            "controlUrl" to control,
            "token" to token,
        )
        return mapOf(
            "running" to running.get(),
            "protocolVersion" to RemoteIpadProtocol.VERSION,
            "displayId" to targetDisplayId,
            "displayWidth" to targetWidth,
            "displayHeight" to targetHeight,
            "videoUrl" to stream,
            "controlUrl" to control,
            "token" to token,
            "tokenExpiresAt" to expiresAtMs,
            "clientConnected" to clientConnected,
            "lastClientSeenAt" to lastClientSeenAt,
            "pairingPayload" to pairingPayload,
            "pairingUri" to Uri.Builder()
                .scheme("dextop")
                .authority("remote")
                .appendQueryParameter("video", stream)
                .appendQueryParameter("control", control)
                .appendQueryParameter("token", token)
                .build()
                .toString(),
        )
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get()) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            executor.execute { serveClient(client) }
        }
    }

    private fun serveClient(socket: Socket) = socket.use { client ->
        runCatching {
            client.tcpNoDelay = true
            if (!client.inetAddress.isSiteLocalAddress) {
                writeJson(client, 403, mapOf("error" to "local_network_only"))
                return@use
            }
            val input = client.getInputStream().bufferedReader()
            val requestLine = input.readLine().orEmpty()
            if (requestLine.isBlank()) return@use
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                writeJson(client, 400, mapOf("error" to "bad_request"))
                return@use
            }
            val method = parts[0].uppercase(Locale.US)
            val target = Uri.parse("http://localhost${parts[1]}")
            val path = target.path ?: "/"
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isBlank()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                        line.substring(separator + 1).trim()
                }
            }
            if (method == "OPTIONS") {
                writeJson(client, 204, null)
                return@use
            }
            val resolvedToken = headers["x-dextop-token"] ?: target.getQueryParameter("token")
            if (resolvedToken != token || System.currentTimeMillis() > expiresAtMs) {
                writeJson(client, 401, mapOf("error" to "unauthorized"))
                return@use
            }
            clientConnected = true
            lastClientSeenAt = System.currentTimeMillis()
            when {
                method == "GET" && path == "/v1/session" -> {
                    writeJson(client, 200, status())
                }

                method == "POST" && path == "/v1/input" -> {
                    val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
                    val body = CharArray(contentLength)
                    var offset = 0
                    while (offset < contentLength) {
                        val read = input.read(body, offset, contentLength - offset)
                        if (read <= 0) break
                        offset += read
                    }
                    val result = handleInput(String(body, 0, offset))
                    writeJson(client, if (result.accepted) 200 else 409, result.toMap())
                }

                else -> writeJson(client, 404, mapOf("error" to "not_found"))
            }
        }.onFailure { error ->
            if (running.get()) {
                OperationLog.w(context, "RemoteIpad", "remote iPad client disconnected", error)
            }
        }
    }

    private fun handleInput(raw: String): InputResult = runCatching {
        when (val message = RemoteIpadProtocol.parse(raw)) {
            is RemoteIpadProtocol.TouchMessage -> handleTouch(message)
            is RemoteIpadProtocol.KeyMessage -> handleKey(message)
            is RemoteIpadProtocol.SessionMessage -> InputResult(true, "session_${message.action}")
        }
    }.getOrElse { error ->
        InputResult(false, "malformed_message", error.message)
    }

    private fun handleTouch(message: RemoteIpadProtocol.TouchMessage): InputResult {
        if (!privilegedAccess.isAvailable()) {
            return InputResult(false, "input_unavailable")
        }
        val now = SystemClock.uptimeMillis()
        val mapped = RemoteCoordinateMapper.mapViewportToDisplay(
            x = message.x,
            y = message.y,
            viewportWidth = message.viewportWidth,
            viewportHeight = message.viewportHeight,
            displayWidth = targetWidth,
            displayHeight = targetHeight,
        )
        val action = when (message.action) {
            "down", "tap" -> MotionEvent.ACTION_DOWN
            "move", "drag" -> MotionEvent.ACTION_MOVE
            "up", "end" -> MotionEvent.ACTION_UP
            "cancel" -> MotionEvent.ACTION_CANCEL
            "scroll" -> MotionEvent.ACTION_SCROLL
            else -> return InputResult(false, "unsupported_touch_action")
        }
        val accepted = if (action == MotionEvent.ACTION_SCROLL) {
            val pointer = MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_MOUSE
            }
            val coords = MotionEvent.PointerCoords().apply {
                x = mapped.x
                y = mapped.y
                pressure = 0f
                size = 1f
                setAxisValue(MotionEvent.AXIS_HSCROLL, message.deltaX.toFloat())
                setAxisValue(MotionEvent.AXIS_VSCROLL, message.deltaY.toFloat())
            }
            val event = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_SCROLL,
                1,
                arrayOf(pointer),
                arrayOf(coords),
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_MOUSE,
                0,
            )
            try {
                inputDispatcher.send(event, targetDisplayId)
            } finally {
                event.recycle()
            }
        } else {
            if (action == MotionEvent.ACTION_DOWN || touchDownTime == 0L) {
                touchDownTime = now
            }
            val event = MotionEvent.obtain(
                touchDownTime,
                now,
                action,
                mapped.x,
                mapped.y,
                0,
            )
            try {
                inputDispatcher.send(event, targetDisplayId)
            } finally {
                event.recycle()
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchDownTime = 0L
        }
        return if (accepted) InputResult(true, "ok") else InputResult(false, "injection_rejected")
    }

    private fun handleKey(message: RemoteIpadProtocol.KeyMessage): InputResult {
        if (!privilegedAccess.isAvailable()) {
            return InputResult(false, "input_unavailable")
        }
        val action = when (message.action) {
            "down" -> KeyEvent.ACTION_DOWN
            "up" -> KeyEvent.ACTION_UP
            else -> return InputResult(false, "unsupported_key_action")
        }
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,
            now,
            action,
            message.keyCode,
            message.repeatCount,
            message.metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            0,
            InputDevice.SOURCE_KEYBOARD,
        )
        return if (inputDispatcher.send(event, targetDisplayId)) {
            InputResult(true, "ok")
        } else {
            InputResult(false, "injection_rejected")
        }
    }

    private fun writeJson(socket: Socket, status: Int, body: Map<String, Any?>?) {
        val output = socket.getOutputStream().buffered()
        val statusText = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            else -> "Error"
        }
        val payload = body?.let { org.json.JSONObject(it).toString() }.orEmpty()
        val headers = StringBuilder().apply {
            append("HTTP/1.1 $status $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Dextop-Token\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n")
            append("Content-Length: ${payload.toByteArray(Charsets.UTF_8).size}\r\n\r\n")
        }.toString()
        output.write(headers.toByteArray())
        if (payload.isNotEmpty()) {
            output.write(payload.toByteArray(Charsets.UTF_8))
        }
        output.flush()
    }

    private fun randomToken(): String {
        val bytes = ByteArray(12)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class InputResult(
        val accepted: Boolean,
        val status: String,
        val detail: String? = null,
    ) {
        fun toMap(): Map<String, Any?> = mapOf(
            "accepted" to accepted,
            "status" to status,
            "detail" to detail,
        )
    }

    companion object {
        private const val TOKEN_TTL_MS = 10 * 60 * 1000L
    }
}
