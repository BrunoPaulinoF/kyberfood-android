package br.com.kyberfood.impressora.data

import android.util.Base64
import br.com.kyberfood.impressora.BuildConfig
import br.com.kyberfood.impressora.service.PollEntry
import org.json.JSONObject

sealed class ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>()
    /** A conta não conseguiu entrar agora (ver `SessionKeeper.state`). */
    data object NoSession : ApiResult<Nothing>()
    /** Sem internet ou servidor com problema: tentar de novo depois. */
    data class Failed(val detail: String) : ApiResult<Nothing>()
}

data class PollJob(val id: String, val title: String, val orderId: String?)

data class PollResponse(
    val queueSince: String,
    val cursor: String,
    val truncated: Boolean,
    val pollSeconds: Int,
    val fullSyncSeconds: Int,
    val orders: List<PollEntry>,
    val jobs: List<PollJob>,
)

data class ReceiptResponse(val printable: Boolean, val bytes: ByteArray, val copies: Int)

data class JobClaim(
    val claimed: Boolean,
    val bytes: ByteArray,
    val copies: Int,
    val orderId: String?,
    val countsAsReceipt: Boolean,
    val title: String,
)

/**
 * A conversa do app com o servidor do KyberFood (rotas `android-app` da API). O servidor decide o
 * que fazer com cada pedido e entrega a comanda PRONTA; aqui só se pergunta e se obedece.
 */
class PrinterApi(private val store: AppStore, private val session: SessionKeeper, private val deviceId: String) {

    fun poll(cursor: String?, beat: Boolean, queueSince: String?): ApiResult<PollResponse> {
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("beat", beat)
        if (cursor != null) body.put("cursor", cursor)
        if (queueSince != null) body.put("queue_since", queueSince)

        return call("android-app/poll", body) { json ->
            val orders = json.optJSONArray("orders")
            val jobs = json.optJSONArray("jobs")
            PollResponse(
                queueSince = json.getString("queue_since"),
                cursor = json.getString("cursor"),
                truncated = json.optBoolean("truncated", false),
                pollSeconds = json.optInt("poll_seconds", 5).coerceIn(2, 120),
                fullSyncSeconds = json.optInt("full_sync_seconds", 300).coerceIn(30, 3600),
                orders = (0 until (orders?.length() ?: 0)).map { index ->
                    val row = orders!!.getJSONObject(index)
                    PollEntry(row.getString("id"), row.optString("action", "none"), row.optBoolean("alert", false), row.optString("label", "Pedido"))
                },
                jobs = (0 until (jobs?.length() ?: 0)).map { index ->
                    val row = jobs!!.getJSONObject(index)
                    PollJob(row.getString("id"), row.optString("title", "Impressão do painel"), row.optString("order_id").takeIf { it.isNotEmpty() && it != "null" })
                },
            )
        }
    }

    fun receipt(orderId: String): ApiResult<ReceiptResponse> {
        val body = JSONObject().put("order_id", orderId).put("cols", store.paperCols)
        return call("android-app/receipt", body) { json ->
            val printable = json.optBoolean("printable", false)
            ReceiptResponse(
                printable = printable,
                bytes = if (printable) Base64.decode(json.getString("receipt_b64"), Base64.DEFAULT) else ByteArray(0),
                copies = json.optInt("copies", 1).coerceIn(1, 3),
            )
        }
    }

    fun claimJob(jobId: String, printedLocally: Boolean?, queueSince: String?): ApiResult<JobClaim> {
        val body = JSONObject()
            .put("action", "claim")
            .put("job_id", jobId)
            .put("device_id", deviceId)
            .put("cols", store.paperCols)
        if (printedLocally != null) body.put("printed_locally", printedLocally)
        if (queueSince != null) body.put("queue_since", queueSince)
        return call("android-app/job", body) { json ->
            val claimed = json.optBoolean("claimed", false)
            JobClaim(
                claimed = claimed,
                bytes = if (claimed) Base64.decode(json.getString("payload_b64"), Base64.DEFAULT) else ByteArray(0),
                copies = json.optInt("copies", 1).coerceIn(1, 3),
                orderId = json.optString("order_id").takeIf { it.isNotEmpty() && it != "null" },
                countsAsReceipt = json.optBoolean("counts_as_receipt", false),
                title = json.optString("title", "Impressão do painel"),
            )
        }
    }

    fun finishJob(jobId: String, ok: Boolean, error: String?) {
        val body = JSONObject().put("action", "finish").put("job_id", jobId).put("ok", ok)
        if (error != null) body.put("error", error)
        call("android-app/job", body) { }
    }

    /** Avisa o painel que a comanda saiu. Só registro: falhar aqui não muda nada na impressão. */
    fun markPrinted(orderId: String) {
        call("desktop/receipt-printed", JSONObject().put("order_id", orderId).put("device_id", "android-$deviceId")) { }
    }

    companion object {
        /** O servidor ainda não tem as rotas deste app (app mais novo que o sistema publicado). */
        const val NOT_DEPLOYED = "rota-inexistente"
    }

    private fun <T> call(path: String, body: JSONObject, parse: (JSONObject) -> T): ApiResult<T> {
        val storeId = store.storeId ?: return ApiResult.NoSession
        val url = "${BuildConfig.API_URL}/api/$path?storeId=$storeId"
        var token = session.validToken() ?: return ApiResult.NoSession
        return try {
            var response = Http.request("POST", url, mapOf("Authorization" to "Bearer $token"), body.toString())
            if (response.status == 401) {
                // O servidor recusou o token: entra de novo (com o freio) e tenta UMA vez.
                token = session.onUnauthorized() ?: return ApiResult.NoSession
                response = Http.request("POST", url, mapOf("Authorization" to "Bearer $token"), body.toString())
            }
            val json = response.json()
            if (response.status == 404) ApiResult.Failed(NOT_DEPLOYED)
            else if (!response.ok || json == null) ApiResult.Failed("servidor respondeu ${response.status}")
            else ApiResult.Ok(parse(json))
        } catch (e: Exception) {
            ApiResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
