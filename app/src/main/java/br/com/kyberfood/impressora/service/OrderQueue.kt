package br.com.kyberfood.impressora.service

/** Um pedido como o servidor o devolve na busca: o id, UMA ação e se é para tocar o alarme. */
data class PollEntry(val id: String, val action: String, val alert: Boolean, val label: String)

/**
 * O que ESTE aparelho já fez — é por aparelho, como no app desktop, e fica gravado no celular.
 * `printed`: comandas que ele já imprimiu ou deu como resolvidas. `alerted`: pedidos pelos
 * quais ele já tocou. `pending`: comandas que ainda devem papel (na ordem de chegada).
 */
class QueueState(
    val printed: MutableMap<String, Long> = mutableMapOf(),
    val alerted: MutableMap<String, Long> = mutableMapOf(),
    val pending: LinkedHashMap<String, String> = LinkedHashMap(),
)

data class PollEffects(
    /** Tocar o alarme (uma vez, mesmo que tenham chegado vários pedidos juntos). */
    val ring: Boolean,
    /** Pedidos que ficaram velhos demais para imprimir: viram aviso. */
    val stale: List<String>,
)

/**
 * A fila da comanda no celular — decisão PURA. A regra de "o que fazer com o pedido" é do
 * servidor; aqui só se garante que cada coisa acontece UMA vez neste aparelho.
 */
object OrderQueue {
    /** Por quanto tempo o aparelho lembra do que já imprimiu (bem acima das 6 h da fila). */
    const val MEMORY_MS = 48 * 60 * 60 * 1000L

    fun apply(entries: List<PollEntry>, state: QueueState, nowMs: Long): PollEffects {
        var ring = false
        val stale = mutableListOf<String>()
        for (entry in entries) {
            if (entry.alert && !state.alerted.containsKey(entry.id)) {
                state.alerted[entry.id] = nowMs
                ring = true
            }
            when (entry.action) {
                "print" -> if (!state.printed.containsKey(entry.id)) state.pending[entry.id] = entry.label
                "resolve" -> {
                    state.printed[entry.id] = nowMs
                    state.pending.remove(entry.id)
                }
                "stale" -> {
                    if (!state.printed.containsKey(entry.id)) {
                        state.printed[entry.id] = nowMs
                        stale += entry.label
                    }
                    state.pending.remove(entry.id)
                }
                // Cancelado ou não é mais para imprimir: sai da fila de tentativas.
                "none" -> state.pending.remove(entry.id)
                // Ação que este app não conhece (servidor mais novo): não mexe em nada.
                else -> Unit
            }
        }
        return PollEffects(ring, stale)
    }

    /** A comanda saiu (ou foi dada como resolvida): nunca mais sai sozinha neste aparelho. */
    fun markPrinted(state: QueueState, orderId: String, nowMs: Long) {
        state.printed[orderId] = nowMs
        state.pending.remove(orderId)
    }

    fun prune(state: QueueState, nowMs: Long) {
        state.printed.entries.removeAll { nowMs - it.value > MEMORY_MS }
        state.alerted.entries.removeAll { nowMs - it.value > MEMORY_MS }
    }
}
