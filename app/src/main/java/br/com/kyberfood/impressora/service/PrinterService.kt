package br.com.kyberfood.impressora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import br.com.kyberfood.impressora.MainActivity
import br.com.kyberfood.impressora.R
import br.com.kyberfood.impressora.data.AppStore

/**
 * O serviço que mantém o app VIVO com a tela apagada (o aviso fixo na barra do celular é a
 * condição do Android para não matar o app) e roda o laço dos pedidos (`OrderWorker`).
 *
 * Segura o processador e o Wi-Fi acordados enquanto está ligado: com a tela apagada o Android
 * poria os dois para dormir e a busca pararia. Por isso a dica de deixar o celular na tomada.
 */
class PrinterService : Service() {
    private var worker: OrderWorker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // O `startForeground` fica no onStartCommand, e não no onCreate: o Android exige a chamada
    // a CADA `startForegroundService`, inclusive quando o serviço já estava ligado.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(this))
            }
        } catch (_: Exception) {
            // O Android recusou (falta permissão ou o app está em segundo plano): a tela
            // inicial mostra "segundo plano desligado" e oferece o botão para ligar.
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            AppStore(this).serviceStartedAtMs = System.currentTimeMillis()
            running = true
        }
        holdAwake()
        if (worker == null) worker = OrderWorker(this).also { it.start() }
        return START_STICKY
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF é o modo que vale com a tela apagada
    private fun holdAwake() {
        if (wakeLock == null) {
            wakeLock = runCatching {
                getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kyberfood:pedidos")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
        if (wifiLock == null) {
            wifiLock = runCatching {
                applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kyberfood:pedidos")
                    .apply { setReferenceCounted(false); acquire() }
            }.getOrNull()
        }
    }

    override fun onDestroy() {
        running = false
        worker?.stop()
        worker = null
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
        wakeLock = null
        wifiLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "kyberfood_ligado"
        private const val ALERT_CHANNEL_ID = "kyberfood_avisos"
        private const val NOTIFICATION_ID = 1001
        private const val DEFAULT_STATUS = "Buscando pedidos. Não feche este aviso."

        @Volatile
        var running: Boolean = false
            private set

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, "Impressora ligada", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Aviso fixo que mantém o KyberFood Impressora funcionando com a tela apagada."
                setShowBadge(false)
            }
            val alerts = NotificationChannel(ALERT_CHANNEL_ID, "Problemas na impressão", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Avisa quando uma comanda não conseguiu sair."
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(channel, alerts))
        }

        /** Troca o texto do aviso fixo (o que o app está fazendo agora). */
        fun updateStatus(context: Context, text: String) {
            if (!running || text == lastStatus) return
            lastStatus = text
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(context, text))
            }
        }

        /** Aviso de problema (comanda que não saiu). Um por pedido: repetir só atualiza o mesmo aviso. */
        fun notifyProblem(context: Context, id: Int, title: String, text: String) {
            runCatching {
                val notification = Notification.Builder(context, ALERT_CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_stat_printer)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setContentIntent(openAppIntent(context))
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(PROBLEM_TAG, id, notification)
            }
        }

        fun clearProblem(context: Context, id: Int) {
            runCatching { context.getSystemService(NotificationManager::class.java).cancel(PROBLEM_TAG, id) }
        }

        private const val PROBLEM_TAG = "problema"

        @Volatile
        private var lastStatus: String = DEFAULT_STATUS

        private fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun buildNotification(context: Context, text: String = lastStatus): Notification {
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_printer)
                .setContentTitle("KyberFood Impressora ligada")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context))
                .build()
        }

        /** Liga o serviço se a conta está logada e a configuração foi concluída. */
        fun startIfReady(context: Context) {
            val store = AppStore(context)
            if (running || !store.isLoggedIn || !store.setupDone) return
            try {
                context.startForegroundService(Intent(context, PrinterService::class.java))
            } catch (_: Exception) {
                // Sem permissão para subir agora: a tela inicial acusa e oferece o botão.
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrinterService::class.java))
        }
    }
}
