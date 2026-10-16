package ai.hermes.glasses.net

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class TurnResponse(
    val text: String,
    val sessionId: String,
    val audioPath: String?,
    val timingMs: Map<String, Long>,
)

class HermesBridgeClient(
    baseUrl: String,
    private val apiKey: String,
) {
    private val root = BridgeEndpoint.normalize(baseUrl)

    fun health(): String {
        val connection = open("/health", "GET", authenticated = false)
        return connection.useResponse { status, body ->
            if (status !in 200..299) throw BridgeException(errorMessage(status, body))
            val json = JSONObject(body)
            "Bridge ${json.optString("status", "unknown")}; Hermes ${json.optString("hermes", "unknown")}"
        }
    }

    fun turn(text: String, language: String, sessionId: String?, speak: Boolean = true): TurnResponse {
        require(apiKey.isNotBlank()) { "Enter the bridge API key" }
        val payload = JSONObject()
            .put("text", text)
            .put("language", language)
            .put("speak", speak)
        if (!sessionId.isNullOrBlank()) payload.put("session_id", sessionId)

        val connection = open("/v1/turn", "POST", authenticated = true)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        return connection.useResponse { status, body ->
            if (status !in 200..299) throw BridgeException(errorMessage(status, body))
            val json = JSONObject(body)
            TurnResponse(
                text = json.getString("text"),
                sessionId = json.getString("session_id"),
                audioPath = json.optString("audio_url").takeIf { it.isNotBlank() && it != "null" },
                timingMs = json.optJSONObject("timing_ms").toLongMap(),
            )
        }
    }

    fun synthesize(text: String, language: String): String {
        require(apiKey.isNotBlank()) { "Enter the bridge API key" }
        val payload = JSONObject()
            .put("text", text)
            .put("language", language)
        val connection = open("/v1/synthesize", "POST", authenticated = true)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        return connection.useResponse { status, body ->
            if (status !in 200..299) throw BridgeException(errorMessage(status, body))
            JSONObject(body).getString("audio_url").also {
                require(it.startsWith("/v1/audio/")) { "Bridge returned an unexpected audio URL" }
            }
        }
    }

    fun downloadAudio(path: String, destination: File): File {
        require(path.startsWith("/v1/audio/")) { "Unexpected audio URL" }
        val connection = open(path, "GET", authenticated = true)
        val status = connection.responseCode
        if (status !in 200..299) {
            val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw BridgeException(errorMessage(status, body))
        }
        destination.outputStream().use { output -> connection.inputStream.use { it.copyTo(output) } }
        connection.disconnect()
        return destination
    }

    private fun open(path: String, method: String, authenticated: Boolean): HttpURLConnection =
        (URL(root + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 240_000
            useCaches = false
            if (authenticated) setRequestProperty("Authorization", "Bearer $apiKey")
        }

    private inline fun <T> HttpURLConnection.useResponse(block: (Int, String) -> T): T = try {
        val status = responseCode
        val stream = if (status in 200..299) inputStream else errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        block(status, body)
    } finally {
        disconnect()
    }

    private fun errorMessage(status: Int, body: String): String {
        val detail = try {
            JSONObject(body).optString("error")
        } catch (_: Exception) {
            body.take(160)
        }
        return "Bridge returned HTTP $status${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}"
    }
}

private fun JSONObject?.toLongMap(): Map<String, Long> {
    if (this == null) return emptyMap()
    val values = mutableMapOf<String, Long>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        values[key] = optLong(key)
    }
    return values
}

class BridgeException(message: String) : Exception(message)
