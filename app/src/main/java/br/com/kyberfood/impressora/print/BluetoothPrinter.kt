package br.com.kyberfood.impressora.print

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.IOException
import java.util.UUID

data class PairedDevice(val name: String, val address: String, val looksLikePrinter: Boolean)

sealed class PrintResult {
    data object Ok : PrintResult()
    data object NoPermission : PrintResult()
    data object BluetoothOff : PrintResult()
    data object NoPrinterChosen : PrintResult()
    /** Não conectou: quase sempre impressora desligada, longe ou sem bateria. */
    data class Unreachable(val detail: String) : PrintResult()
    data class SendFailed(val detail: String) : PrintResult()
}

/** Impressora térmica por Bluetooth clássico (perfil serial), a que essas portáteis usam. */
@SuppressLint("MissingPermission") // toda chamada passa por hasPermission() antes
object BluetoothPrinter {
    private val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val lock = Any()

    fun adapter(context: Context): BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun isOn(context: Context): Boolean = adapter(context)?.isEnabled == true

    /** Aparelhos já pareados no celular, com os que parecem impressora primeiro. */
    fun pairedDevices(context: Context): List<PairedDevice> {
        if (!hasPermission(context)) return emptyList()
        val devices = try {
            adapter(context)?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return devices
            .map { device ->
                val name = runCatching { device.name }.getOrNull().orEmpty().ifBlank { "Aparelho sem nome" }
                PairedDevice(name, device.address, looksLikePrinter(device, name))
            }
            .sortedWith(compareByDescending<PairedDevice> { it.looksLikePrinter }.thenBy { it.name.lowercase() })
    }

    private fun looksLikePrinter(device: BluetoothDevice, name: String): Boolean {
        val major = runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull()
        if (major == BluetoothClass.Device.Major.IMAGING) return true
        val lower = name.lowercase()
        return listOf("print", "impress", "pos", "pt-", "mtp", "mpt", "rpp", "thermal", "58", "80", "genai")
            .any { lower.contains(it) }
    }

    /** Manda os bytes para a impressora. Uma impressão por vez. */
    fun print(context: Context, address: String?, bytes: ByteArray): PrintResult = synchronized(lock) {
        if (address.isNullOrEmpty()) return PrintResult.NoPrinterChosen
        if (!hasPermission(context)) return PrintResult.NoPermission
        val adapter = adapter(context)
        if (adapter == null || !adapter.isEnabled) return PrintResult.BluetoothOff

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (e: IllegalArgumentException) {
            return PrintResult.Unreachable("endereço inválido")
        }

        val socket = try {
            connect(device)
        } catch (e: Exception) {
            return PrintResult.Unreachable(e.message ?: e.javaClass.simpleName)
        }

        try {
            val output = socket.outputStream
            // Impressora barata tem memória pequena: mandar tudo de uma vez perde o final.
            var offset = 0
            while (offset < bytes.size) {
                val size = minOf(CHUNK_BYTES, bytes.size - offset)
                output.write(bytes, offset, size)
                output.flush()
                offset += size
                Thread.sleep(CHUNK_PAUSE_MS)
            }
            // Fechar na hora corta o que ainda está a caminho da impressora.
            Thread.sleep(CLOSE_DELAY_MS + bytes.size / 20L)
            return PrintResult.Ok
        } catch (e: Exception) {
            // O serviço foi desligado no meio: devolve o aviso de parada a quem chamou.
            if (e is InterruptedException) Thread.currentThread().interrupt()
            return PrintResult.SendFailed(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Três jeitos de abrir a conexão, do mais correto ao que salva impressora teimosa. */
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val attempts: List<() -> BluetoothSocket> = listOf(
            { device.createRfcommSocketToServiceRecord(SERIAL_PORT) },
            { device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT) },
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var lastError: Exception = IOException("não conectou")
        for (open in attempts) {
            var socket: BluetoothSocket? = null
            try {
                socket = open()
                socket.connect()
                return socket
            } catch (e: Exception) {
                lastError = e
                runCatching { socket?.close() }
            }
        }
        throw lastError
    }

    private const val CHUNK_BYTES = 256
    private const val CHUNK_PAUSE_MS = 25L
    private const val CLOSE_DELAY_MS = 600L
}
