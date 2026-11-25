package br.com.kyberfood.impressora.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val email: String, val password: String)

data class Session(
    val accessToken: String,
    val refreshToken: String,
    /** Quando o token vence, em milissegundos do relógio do celular. */
    val expiresAtMs: Long,
    val userId: String,
)

/**
 * Tudo que o app guarda no celular.
 *
 * A CONTA SÓ SAI PELO BOTÃO "SAIR" (`signOut`). Nenhum outro caminho apaga a credencial:
 * token vencido, senha recusada, falta de internet e erro do servidor viram AVISO na tela
 * e nova tentativa — nunca logout. A senha fica cifrada pela Keystore do Android.
 */
class AppStore(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("kyberfood", Context.MODE_PRIVATE)

    // ---- conta ----
    fun credentials(): Credentials? {
        val email = sp.getString(K_EMAIL, null) ?: return null
        val password = Crypto.decrypt(sp.getString(K_PASSWORD, null)) ?: return null
        return Credentials(email, password)
    }

    fun saveCredentials(credentials: Credentials) {
        sp.edit()
            .putString(K_EMAIL, credentials.email)
            .putString(K_PASSWORD, Crypto.encrypt(credentials.password))
            .putString(K_LAST_EMAIL, credentials.email)
            .apply()
    }

    /** E-mail do último login: só preenche o formulário, e SOBREVIVE ao Sair. */
    val lastEmail: String get() = sp.getString(K_LAST_EMAIL, "") ?: ""

    fun session(): Session? {
        val access = Crypto.decrypt(sp.getString(K_ACCESS, null)) ?: return null
        val refresh = Crypto.decrypt(sp.getString(K_REFRESH, null)) ?: return null
        val userId = sp.getString(K_USER_ID, null) ?: return null
        return Session(access, refresh, sp.getLong(K_EXPIRES, 0L), userId)
    }

    fun saveSession(session: Session) {
        sp.edit()
            .putString(K_ACCESS, Crypto.encrypt(session.accessToken))
            .putString(K_REFRESH, Crypto.encrypt(session.refreshToken))
            .putLong(K_EXPIRES, session.expiresAtMs)
            .putString(K_USER_ID, session.userId)
            .apply()
    }

    var storeId: String?
        get() = sp.getString(K_STORE_ID, null)
        set(value) = sp.edit().putString(K_STORE_ID, value).apply()

    var storeName: String
        get() = sp.getString(K_STORE_NAME, "") ?: ""
        set(value) = sp.edit().putString(K_STORE_NAME, value).apply()

    val isLoggedIn: Boolean get() = credentials() != null && storeId != null

    /** O ÚNICO lugar que apaga a conta. Chamado só pelo botão "Sair da conta". */
    fun signOut() {
        sp.edit()
            .remove(K_EMAIL).remove(K_PASSWORD)
            .remove(K_ACCESS).remove(K_REFRESH).remove(K_EXPIRES).remove(K_USER_ID)
            .remove(K_STORE_ID).remove(K_STORE_NAME)
            .remove(K_RELOGIN_AT).remove(K_RELOGIN_STREAK)
            .apply()
    }

    // ---- freio do relogin (ver SessionKeeper) ----
    var lastReloginAtMs: Long
        get() = sp.getLong(K_RELOGIN_AT, 0L)
        set(value) = sp.edit().putLong(K_RELOGIN_AT, value).apply()

    var reloginStreak: Int
        get() = sp.getInt(K_RELOGIN_STREAK, 0)
        set(value) = sp.edit().putInt(K_RELOGIN_STREAK, value).apply()

    // ---- impressora ----
    var printerAddress: String?
        get() = sp.getString(K_PRINTER_ADDRESS, null)
        set(value) = sp.edit().putString(K_PRINTER_ADDRESS, value).apply()

    var printerName: String
        get() = sp.getString(K_PRINTER_NAME, "") ?: ""
        set(value) = sp.edit().putString(K_PRINTER_NAME, value).apply()

    /** Colunas do papel: 32 = 58 mm, 48 = 80 mm (os mesmos números do painel). */
    var paperCols: Int
        get() = sp.getInt(K_PAPER_COLS, 32)
        set(value) = sp.edit().putInt(K_PAPER_COLS, if (value == 48) 48 else 32).apply()

    // ---- configuração inicial ----
    var setupDone: Boolean
        get() = sp.getBoolean(K_SETUP_DONE, false)
        set(value) = sp.edit().putBoolean(K_SETUP_DONE, value).apply()

    /** O passo "abrir sozinho" não tem como ser conferido pelo app: vale a palavra da pessoa. */
    var autostartConfirmed: Boolean
        get() = sp.getBoolean(K_AUTOSTART_OK, false)
        set(value) = sp.edit().putBoolean(K_AUTOSTART_OK, value).apply()

    var serviceStartedAtMs: Long
        get() = sp.getLong(K_SERVICE_STARTED, 0L)
        set(value) = sp.edit().putLong(K_SERVICE_STARTED, value).apply()

    /** Última coisa que este aparelho imprimiu — só para a tela inicial mostrar. */
    var lastPrintedLabel: String?
        get() = sp.getString(K_LAST_PRINTED, null)
        set(value) = sp.edit().putString(K_LAST_PRINTED, value).apply()

    var lastPrintedAtMs: Long
        get() = sp.getLong(K_LAST_PRINTED_AT, 0L)
        set(value) = sp.edit().putLong(K_LAST_PRINTED_AT, value).apply()

    /** Quando o serviço conferiu a versão publicada pela última vez. */
    var lastUpdateCheckAtMs: Long
        get() = sp.getLong(K_UPDATE_CHECK_AT, 0L)
        set(value) = sp.edit().putLong(K_UPDATE_CHECK_AT, value).apply()

    private companion object {
        const val K_EMAIL = "email"
        const val K_PASSWORD = "password"
        const val K_LAST_EMAIL = "last_email"
        const val K_ACCESS = "access_token"
        const val K_REFRESH = "refresh_token"
        const val K_EXPIRES = "expires_at"
        const val K_USER_ID = "user_id"
        const val K_STORE_ID = "store_id"
        const val K_STORE_NAME = "store_name"
        const val K_RELOGIN_AT = "relogin_at"
        const val K_RELOGIN_STREAK = "relogin_streak"
        const val K_PRINTER_ADDRESS = "printer_address"
        const val K_PRINTER_NAME = "printer_name"
        const val K_PAPER_COLS = "paper_cols"
        const val K_SETUP_DONE = "setup_done"
        const val K_AUTOSTART_OK = "autostart_ok"
        const val K_SERVICE_STARTED = "service_started"
        const val K_LAST_PRINTED = "last_printed"
        const val K_LAST_PRINTED_AT = "last_printed_at"
        const val K_UPDATE_CHECK_AT = "update_check_at"
    }
}

/** Cifra com uma chave que nunca sai do chip do celular (Android Keystore, AES-GCM). */
private object Crypto {
    private const val ALIAS = "kyberfood_impressora"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    // Uma chave só: duas threads criando ao mesmo tempo deixariam a segunda por cima da
    // primeira, e o que foi cifrado com a primeira (a senha guardada) não abriria mais.
    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return try {
            val (iv, data) = stored.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
