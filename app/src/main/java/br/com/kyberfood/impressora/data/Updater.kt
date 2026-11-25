package br.com.kyberfood.impressora.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import br.com.kyberfood.impressora.BuildConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class UpdateInfo(val version: String, val versionCode: Int, val sha256: String?)

sealed class UpdateCheck {
    data class Available(val info: UpdateInfo) : UpdateCheck()
    data object UpToDate : UpdateCheck()
    /** Não deu para saber (sem internet, nada publicado): nunca é tratado como "há atualização". */
    data object Unknown : UpdateCheck()
}

/**
 * "Procurar atualização". O app pergunta ao KyberFood qual é a versão publicada, baixa o
 * instalador pelo mesmo link de Integrações e entrega ao Android, que pede a confirmação da
 * pessoa. O login e a configuração são mantidos: é instalação por cima.
 *
 * Quem decide se há atualização é o NÚMERO inteiro da versão (`versionCode`), que só cresce.
 */
object Updater {
    /** Só é atualização o que é MAIS NOVO que o instalado; igual ou mais antigo, nunca. */
    fun isNewer(publishedCode: Int, installedCode: Int): Boolean = publishedCode > installedCode

    fun check(): UpdateCheck {
        return try {
            val response = Http.request("GET", "${BuildConfig.API_URL}/api/android-app/latest-version", emptyMap(), null)
            val json = response.json()
            if (!response.ok || json == null) return UpdateCheck.Unknown
            val version = json.optString("version").takeIf { it.isNotEmpty() && it != "null" }
            val code = json.optInt("version_code", 0)
            if (version == null || code <= 0) return UpdateCheck.Unknown
            if (!isNewer(code, BuildConfig.VERSION_CODE)) return UpdateCheck.UpToDate
            val sha = json.optString("sha256").takeIf { it.length == 64 }
            UpdateCheck.Available(UpdateInfo(version, code, sha))
        } catch (_: Exception) {
            UpdateCheck.Unknown
        }
    }

    /** O Android só deixa um app instalar outro (ou a si mesmo) se a pessoa liberar "desta fonte". */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    /** Baixa o instalador. Lança exceção se a rede cair ou se o arquivo não for o publicado. */
    fun download(context: Context, info: UpdateInfo, onProgress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "atualizacao").apply { mkdirs() }
        val file = File(dir, "KyberFood-Impressora.apk")
        val connection = URL("${BuildConfig.API_URL}/api/android-app/download").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 60_000
            if (connection.responseCode !in 200..299) error("servidor respondeu ${connection.responseCode}")
            val total = connection.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) onProgress((done * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
            // Arquivo cortado ou trocado no caminho não chega ao instalador.
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256 != null && !sha.equals(info.sha256, ignoreCase = true)) {
                file.delete()
                error("o arquivo baixado não confere com o publicado")
            }
            return file
        } finally {
            connection.disconnect()
        }
    }

    /** Abre a tela do Android que instala a atualização (a pessoa confirma com um toque). */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.arquivos", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
