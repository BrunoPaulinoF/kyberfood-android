package br.com.kyberfood.impressora.ui

import android.content.Context
import br.com.kyberfood.impressora.data.AppStore
import br.com.kyberfood.impressora.print.BluetoothPrinter
import br.com.kyberfood.impressora.print.EscPos
import br.com.kyberfood.impressora.print.PrintResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Imprime a página de teste na impressora escolhida. Chamar fora da tela (Dispatchers.IO). */
fun printTestPage(context: Context, store: AppStore): PrintResult {
    val now = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date())
    return BluetoothPrinter.print(context, store.printerAddress, EscPos.testPage(store.paperCols, store.storeName, now))
}

/** O que a pessoa lê depois de mandar imprimir — sempre dizendo o que fazer. */
fun describePrintResult(result: PrintResult): String = when (result) {
    PrintResult.Ok -> "Enviado para a impressora."
    PrintResult.NoPermission -> "Falta permitir o Bluetooth para o app."
    PrintResult.BluetoothOff -> "O Bluetooth do celular está desligado. Ligue e tente de novo."
    PrintResult.NoPrinterChosen -> "Escolha a impressora primeiro."
    is PrintResult.Unreachable ->
        "Não consegui falar com a impressora. Veja se ela está ligada, com bateria e perto do celular."
    is PrintResult.SendFailed -> "A conexão caiu no meio da impressão. Tente de novo."
}
