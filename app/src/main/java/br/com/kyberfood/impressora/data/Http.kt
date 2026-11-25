package br.com.kyberfood.impressora.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class HttpResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in 200..299
    fun json(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()
}

/** Chamada HTTP simples (JSON). Lança exceção quando não há rede — quem chama decide o que mostrar. */
object Http {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }
}
