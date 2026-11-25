package br.com.kyberfood.impressora.data

/** Como está a conta agora — é o que a tela mostra. Nenhum destes estados desloga. */
sealed class AccountState {
    data object Connected : AccountState()
    /** A senha do painel mudou: pede a senha nova, mas a conta continua guardada. */
    data object PasswordRefused : AccountState()
    data class Waiting(val reason: String) : AccountState()
}

/**
 * Mantém o login vivo para sempre. Token vencido é renovado; se a renovação for recusada,
 * entra de novo com o e-mail e a senha guardados, respeitando o freio de `Relogin`.
 * NUNCA apaga a credencial — isso é só do botão "Sair" (`AppStore.signOut`).
 */
class SessionKeeper(private val store: AppStore) {

    /** Token válido para chamar o servidor, ou null quando ainda não deu (ver `state`). */
    @Synchronized
    fun validToken(nowMs: Long = System.currentTimeMillis()): String? {
        val session = store.session()
        if (session != null && session.expiresAtMs - nowMs > EXPIRY_MARGIN_MS) {
            state = AccountState.Connected
            return session.accessToken
        }
        if (session != null) {
            when (val refreshed = SupabaseAuth.refresh(session.refreshToken)) {
                is AuthResult.Ok -> return accept(refreshed.session)
                is AuthResult.Unavailable -> {
                    state = AccountState.Waiting("Sem internet no momento")
                    return null
                }
                AuthResult.RateLimited -> {
                    state = AccountState.Waiting("Aguardando para reconectar")
                    return null
                }
                AuthResult.WrongCredentials -> Unit // renovação recusada: cai no login completo
            }
        }
        return relogin(nowMs)
    }

    /** O servidor recusou o token (401): força um login novo, com o mesmo freio. */
    @Synchronized
    fun onUnauthorized(nowMs: Long = System.currentTimeMillis()): String? = relogin(nowMs)

    private fun relogin(nowMs: Long): String? {
        val credentials = store.credentials()
        if (credentials == null) {
            state = AccountState.PasswordRefused
            return null
        }
        if (Relogin.waitMs(nowMs, store.lastReloginAtMs, store.reloginStreak) > 0L) {
            if (state == AccountState.Connected) state = AccountState.Waiting("Aguardando para reconectar")
            return null
        }
        // O contador sobe ANTES da tentativa e vale também quando ela dá certo: um login que
        // "funciona" e volta a ser recusado em segundos é justamente o que precisa de freio.
        val stable = store.lastReloginAtMs > 0L && nowMs - store.lastReloginAtMs >= Relogin.STABLE_MS
        store.reloginStreak = if (stable) 1 else store.reloginStreak + 1
        store.lastReloginAtMs = nowMs

        return when (val result = SupabaseAuth.signIn(credentials.email, credentials.password)) {
            is AuthResult.Ok -> accept(result.session)
            AuthResult.WrongCredentials -> {
                state = AccountState.PasswordRefused
                null
            }
            AuthResult.RateLimited -> {
                state = AccountState.Waiting("Aguardando para reconectar")
                null
            }
            is AuthResult.Unavailable -> {
                state = AccountState.Waiting("Sem internet no momento")
                null
            }
        }
    }

    private fun accept(session: Session): String {
        store.saveSession(session)
        state = AccountState.Connected
        return session.accessToken
    }

    /** Login digitado na tela: zera o freio. */
    fun onManualLogin(credentials: Credentials, session: Session) {
        store.saveCredentials(credentials)
        store.saveSession(session)
        store.reloginStreak = 0
        store.lastReloginAtMs = 0L
        state = AccountState.Connected
    }

    companion object {
        private const val EXPIRY_MARGIN_MS = 60_000L

        @Volatile
        var state: AccountState = AccountState.Connected
            private set
    }
}
