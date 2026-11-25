package br.com.kyberfood.impressora.data

import android.content.Context
import br.com.kyberfood.impressora.service.QueueState
import org.json.JSONObject
import java.util.UUID

/**
 * A fila da comanda gravada NO CELULAR, por loja. Sobrevive ao app ser fechado, ao celular
 * reiniciar e a uma atualização — é o que impede a comanda de sair duas vezes e o que faz a
 * que ficou pendente sair quando o app volta.
 */
class QueueStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("kyberfood_fila", Context.MODE_PRIVATE)

    /** Identidade deste aparelho para o servidor (presença e fila de impressão). */
    val deviceId: String
        get() {
            sp.getString(K_DEVICE, null)?.let { return it }
            val created = UUID.randomUUID().toString()
            sp.edit().putString(K_DEVICE, created).apply()
            return created
        }

    /** Desde quando este aparelho acompanha a loja (instante do SERVIDOR). `null` = primeira busca. */
    fun queueSince(storeId: String): String? = sp.getString("since_$storeId", null)

    fun saveQueueSince(storeId: String, iso: String) {
        if (queueSince(storeId) == null) sp.edit().putString("since_$storeId", iso).apply()
    }

    fun load(storeId: String): QueueState {
        val state = QueueState()
        readMap("printed_$storeId", state.printed)
        readMap("alerted_$storeId", state.alerted)
        return state
    }

    /** Só o que precisa sobreviver: a fila de tentativas é refeita pela lista inteira do servidor. */
    fun save(storeId: String, state: QueueState) {
        sp.edit()
            .putString("printed_$storeId", JSONObject(state.printed as Map<*, *>).toString())
            .putString("alerted_$storeId", JSONObject(state.alerted as Map<*, *>).toString())
            .apply()
    }

    private fun readMap(key: String, into: MutableMap<String, Long>) {
        // Disco ilegível = lista vazia; o "desde quando" continua valendo e segura o histórico.
        val json = runCatching { JSONObject(sp.getString(key, null) ?: return) }.getOrNull() ?: return
        for (id in json.keys()) into[id] = json.optLong(id, 0L)
    }

    private companion object {
        const val K_DEVICE = "device_id"
    }
}
