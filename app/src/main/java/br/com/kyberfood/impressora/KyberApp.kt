package br.com.kyberfood.impressora

import android.app.Application
import br.com.kyberfood.impressora.service.PrinterService

class KyberApp : Application() {
    override fun onCreate() {
        super.onCreate()
        PrinterService.createChannel(this)
    }
}
