package br.com.kyberfood.impressora.data

import br.com.kyberfood.impressora.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class StoreInfo(val id: String, val name: String)

sealed class AuthResult {
    data class Ok(val session: Session) : AuthResult()
    /** E-mail ou senha recusados pelo servidor. */
    data object WrongCredentials : AuthResult()
    /** Tentativas demais: é ESPERA, nunca recusa de credencial. */
    data object RateLimited : AuthResult()
    /** Sem internet ou servidor fora: tentar de novo depois. */
    data class Unavailable(val detail: String) : AuthResult()
}

/** Conversa com o login do KyberFood (Supabase Auth) usando só a chave PÚBLICA. */
object SupabaseAuth {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotEmpty() && BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()

    fun signIn(email: String, password: String): AuthResult =
        token("password", JSONObject().put("email", email).put("password", password))

    fun refresh(refreshToken: String): AuthResult =
        token("refresh_token", JSONObject().put("refresh_token", refreshToken))

    private fun token(grantType: String, body: JSONObject): AuthResult {
        val response = try {
            request("POST", "${BuildConfig.SUPABASE_URL}/auth/v1/token?grant_type=$grantType", null, body.toString())
        } catch (e: Exception) {
            return AuthResult.Unavailable(e.message ?: e.javaClass.simpleName)
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()

        if (response.status in 200..299 && json != null) {
            val access = json.optString("access_token")
            val refresh = json.optString("refresh_token")
            val userId = json.optJSONObject("user")?.optString("id").orEmpty()
            if (access.isEmpty() || refresh.isEmpty() || userId.isEmpty()) {
                return AuthResult.Unavailable("resposta de login incompleta")
            }
            val expiresInSec = json.optLong("expires_in", 3600L)
            return AuthResult.Ok(Session(access, refresh, System.currentTimeMillis() + expiresInSec * 1000L, userId))
        }

        // 429 chega de três formas (status, código e mensagem). Nenhuma delas é senha errada.
        val code = json?.optString("error_code").orEmpty()
        val message = (json?.optString("msg").orEmpty() + " " + json?.optString("error_description").orEmpty()).lowercase()
        if (response.status == 429 || code == "over_request_rate_limit" || message.contains("rate limit")) {
            return AuthResult.RateLimited
        }
        if (response.status in 400..499) return AuthResult.WrongCredentials
        return AuthResult.Unavailable("servidor respondeu ${response.status}")
    }

    /** Lojas ativas desta conta. Lança exceção se a leitura falhar (falha nunca vira "sem loja"). */
    fun fetchStores(session: Session): List<StoreInfo> {
        val userId = URLEncoder.encode(session.userId, "UTF-8")
        val url = "${BuildConfig.SUPABASE_URL}/rest/v1/stores?select=id,name&user_id=eq.$userId&is_active=eq.true&order=name.asc"
        val response = request("GET", url, session.accessToken, null)
        if (response.status !in 200..299) error("lojas: servidor respondeu ${response.status}")
        val array = JSONArray(response.body)
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            StoreInfo(row.getString("id"), row.optString("name", "Minha loja"))
        }
    }

    private fun request(method: String, url: String, bearer: String?, body: String?): HttpResponse {
        val headers = buildMap {
            put("apikey", BuildConfig.SUPABASE_ANON_KEY)
            if (bearer != null) put("Authorization", "Bearer $bearer")
        }
        return Http.request(method, url, headers, body)
    }
}
