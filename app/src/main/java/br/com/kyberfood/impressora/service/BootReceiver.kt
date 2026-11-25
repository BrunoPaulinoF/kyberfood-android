package br.com.kyberfood.impressora.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Liga o app sozinho quando o celular reinicia ou quando o app é atualizado. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> PrinterService.startIfReady(context)
        }
    }
}
