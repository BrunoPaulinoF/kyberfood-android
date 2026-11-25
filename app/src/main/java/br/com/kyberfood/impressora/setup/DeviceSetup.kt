package br.com.kyberfood.impressora.setup

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * As permissões do celular e o atalho para a TELA CERTA de cada uma. Quem configura é o
 * próprio lojista: cada item tem um botão que já abre o lugar onde ele libera.
 */
object DeviceSetup {

    // ---- avisos (o aviso fixo que mantém o app ligado) ----
    val needsNotificationPermission: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun notificationsAllowed(context: Context): Boolean {
        val enabled = context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
        if (!needsNotificationPermission) return enabled
        return enabled &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    fun openNotificationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        if (!launch(context, intent)) openAppDetails(context)
    }

    // ---- segundo plano (economia de bateria) ----
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Abre a pergunta do próprio Android "deixar o app rodar em segundo plano?". */
    @SuppressLint("BatteryLife") // app instalado direto, fora da loja: o pedido direto é o caminho mais fácil
    fun requestBatteryUnrestricted(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        if (launch(context, direct)) return
        if (launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
        openAppDetails(context)
    }

    // ---- abrir sozinho (marcas que matam app parado) ----
    private val manufacturer: String get() = Build.MANUFACTURER.orEmpty().lowercase()

    /** Só estas marcas têm a trava extra; nas outras o passo nem aparece. */
    val needsAutostartStep: Boolean
        get() = autostartHint() != null

    /** O que a pessoa procura na tela que vai abrir, com o nome que a marca usa. */
    fun autostartHint(): String? = when {
        manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") ->
            "Ative \"Início automático\" para o KyberFood Impressora. Depois, em Economia de bateria, escolha \"Sem restrições\"."
        manufacturer.contains("samsung") ->
            "Toque em Bateria e escolha \"Irrestrito\" (ou \"Não restrito\")."
        manufacturer.contains("motorola") || manufacturer.contains("lenovo") ->
            "Toque em Bateria (ou Uso da bateria) e escolha \"Sem restrições\"."
        manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") ->
            "Ative \"Permitir início automático\" e \"Permitir atividade em segundo plano\"."
        manufacturer.contains("vivo") || manufacturer.contains("iqoo") ->
            "Ative \"Início automático\" e, em Bateria, \"Permitir uso alto em segundo plano\"."
        manufacturer.contains("huawei") || manufacturer.contains("honor") ->
            "Em Início de apps, desligue \"Gerenciar automaticamente\" e deixe os três itens ligados."
        manufacturer.contains("tecno") || manufacturer.contains("infinix") || manufacturer.contains("itel") ->
            "Ative \"Início automático\" e tire o app da lista de economia de bateria."
        else -> null
    }

    fun openAutostartSettings(context: Context) {
        val candidates = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oplus.safecenter" to "com.oplus.safecenter.startupapp.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.transsion.phonemanager" to "com.itel.autobootmanager.activity.AutoBootMgrActivity",
        )
        for ((pkg, cls) in candidates) {
            if (launch(context, Intent().setComponent(ComponentName(pkg, cls)))) return
        }
        openAppDetails(context)
    }

    // ---- Bluetooth ----
    fun openBluetoothSettings(context: Context) {
        if (!launch(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) launch(context, Intent(Settings.ACTION_SETTINGS))
    }

    /** A página do app nas configurações do celular: o plano B de todos os atalhos. */
    fun openAppDetails(context: Context) {
        launch(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    private fun launch(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Exception) {
        false
    }
}
