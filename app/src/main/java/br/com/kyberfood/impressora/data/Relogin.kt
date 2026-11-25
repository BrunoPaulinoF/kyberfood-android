package br.com.kyberfood.impressora.data

/**
 * Freio do relogin — ESPELHO do app desktop (`reloginBackoffMs` em kyberfood-desktop/src/App.tsx).
 *
 * O app nunca desiste de voltar a logar, só ESPAÇA: 60 s dobrando até 15 min, para sempre.
 * A 1ª tentativa nunca espera. Sem o freio, uma sessão recusada vira um login novo a cada
 * consulta e o provedor bloqueia a loja por excesso de tentativas.
 */
object Relogin {
    const val MIN_INTERVAL_MS = 60_000L
    const val MAX_INTERVAL_MS = 15 * 60_000L

    /** Silêncio acima disto = o relogin anterior resolveu, e a espera volta a zero. */
    const val STABLE_MS = 30 * 60_000L

    fun backoffMs(streak: Int): Long {
        if (streak <= 0) return 0L
        val doubled = MIN_INTERVAL_MS * (1L shl minOf(streak - 1, 20))
        return minOf(MAX_INTERVAL_MS, doubled)
    }

    /** Quanto falta esperar antes de tentar de novo (0 = pode tentar agora). */
    fun waitMs(nowMs: Long, lastAttemptMs: Long, streak: Int): Long {
        if (lastAttemptMs <= 0L) return 0L
        val elapsed = nowMs - lastAttemptMs
        if (elapsed < 0L || elapsed >= STABLE_MS) return 0L
        return maxOf(0L, backoffMs(streak) - elapsed)
    }
}
