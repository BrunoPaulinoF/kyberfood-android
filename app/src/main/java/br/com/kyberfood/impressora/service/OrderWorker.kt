package br.com.kyberfood.impressora.service

import android.content.Context
import br.com.kyberfood.impressora.data.AccountState
import br.com.kyberfood.impressora.data.ApiResult
import br.com.kyberfood.impressora.data.AppStore
import br.com.kyberfood.impressora.data.PollJob
import br.com.kyberfood.impressora.data.PrinterApi
import br.com.kyberfood.impressora.data.QueueStore
import br.com.kyberfood.impressora.data.SessionKeeper
import br.com.kyberfood.impressora.data.UpdateCheck
import br.com.kyberfood.impressora.data.Updater
import br.com.kyberfood.impressora.print.BluetoothPrinter
import br.com.kyberfood.impressora.print.PrintResult
import br.com.kyberfood.impressora.sound.OrderSound
import kotlin.concurrent.thread

/** O que a tela inicial e o aviso fixo mostram sobre a busca de pedidos. */
object WorkerStatus {
    @Volatile var lastPollOkAtMs: Long = 0L
    @Volatile var problem: String? = null
    @Volatile var pendingCount: Int = 0
    @Volatile var printerProblem: String? = null
}

/**
 * O LAÇO DO APP: pergunta ao servidor o que mudou, toca o alarme, imprime o que deve papel e
 * atende a reimpressão do painel. Roda dentro do `PrinterService`, com a tela apagada.
 *
 * - NUNCA para de buscar: o ritmo vem do servidor a cada resposta (rápido com a loja aberta
 *   ou pedido em andamento, espaçado fora disso).
 * - A comanda só conta como impressa DEPOIS que a impressora aceitou os bytes. Falhou, fica
 *   na fila e tenta de novo; o lojista é avisado e o alarme repete enquanto houver comanda
 *   parada.
 * - O que este aparelho já imprimiu fica gravado no celular: fechar o app, reiniciar ou
 *   atualizar não faz comanda sair repetida nem se perder.
 */
class OrderWorker(context: Context) {
    private val context = context.applicationContext
    private val store = AppStore(context)
    private val queue = QueueStore(context)
    private val api = PrinterApi(store, SessionKeeper(store), queue.deviceId)

    @Volatile private var running = false
    private var thread: Thread? = null

    private var state = QueueState()
    private var stateStoreId: String? = null
    private var cursor: String? = null
    private var lastFullAtMs = 0L
    private var fullEveryMs = 300_000L
    private var lastBeatAtMs = 0L
    private var nextPrintAttemptAtMs = 0L
    private var lastReminderAtMs = 0L
    private val failureNotified = mutableSetOf<String>()

    fun start() {
        if (running) return
        running = true
        val previous = lastThread
        thread = thread(name = "kyberfood-pedidos") {
            // UM laço por vez. Se o serviço foi religado enquanto o laço anterior ainda terminava
            // uma impressão, este espera: dois laços juntos imprimiriam a mesma comanda.
            runCatching { previous?.join(PREVIOUS_LOOP_WAIT_MS) }
            loop()
        }.also { lastThread = it }
    }

    /** Para o laço. `waitMs` > 0 espera ele terminar a volta em andamento. */
    fun stop(waitMs: Long = 0L) {
        running = false
        val stopping = thread
        stopping?.interrupt()
        thread = null
        if (waitMs > 0L) runCatching { stopping?.join(waitMs) }
    }

    private fun loop() {
        while (running) {
            val waitMs = try {
                tick()
            } catch (_: InterruptedException) {
                return
            } catch (e: Exception) {
                // Erro inesperado nunca mata o laço: é ele que garante a comanda.
                WorkerStatus.problem = "Erro inesperado: ${e.message ?: e.javaClass.simpleName}"
                RETRY_MS
            }
            publishStatus()
            try {
                Thread.sleep(waitMs)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    /** Uma volta do laço. Devolve quanto esperar até a próxima. */
    private fun tick(): Long {
        val storeId = store.storeId
        if (storeId == null || !store.isLoggedIn) {
            WorkerStatus.problem = "Sem conta conectada"
            return IDLE_MS
        }
        if (stateStoreId != storeId) {
            state = queue.load(storeId)
            stateStoreId = storeId
            cursor = null
            failureNotified.clear()
        }

        val now = System.currentTimeMillis()
        val full = cursor == null || now - lastFullAtMs >= fullEveryMs
        val beat = now - lastBeatAtMs >= BEAT_MS
        val queueSince = queue.queueSince(storeId)

        val response = when (val result = api.poll(if (full) null else cursor, beat, queueSince)) {
            is ApiResult.Ok -> result.value
            ApiResult.NoSession -> {
                WorkerStatus.problem = when (SessionKeeper.state) {
                    AccountState.PasswordRefused -> "A senha do painel mudou. Abra o app e digite a senha nova."
                    else -> "Reconectando a conta"
                }
                return RETRY_MS
            }
            is ApiResult.Failed -> {
                WorkerStatus.problem =
                    if (result.detail == PrinterApi.NOT_DEPLOYED) "O KyberFood ainda não foi atualizado para esta versão do app. Fale com o suporte."
                    else "Sem conexão com o KyberFood. Tentando de novo."
                return RETRY_MS
            }
        }

        WorkerStatus.problem = null
        WorkerStatus.lastPollOkAtMs = now
        if (beat) lastBeatAtMs = now
        if (full) lastFullAtMs = now
        fullEveryMs = response.fullSyncSeconds * 1000L
        queue.saveQueueSince(storeId, response.queueSince)
        // Bateu o teto da resposta: a próxima volta pede a lista inteira.
        cursor = if (response.truncated) null else response.cursor

        val effects = OrderQueue.apply(response.orders, state, now)
        OrderQueue.prune(state, now)
        queue.save(storeId, state)
        if (effects.ring) OrderSound.play(context)
        effects.stale.forEach { label -> PrinterService.notifyProblem(context, label.hashCode(), "Comanda antiga não impressa", "$label ficou tempo demais sem imprimir. Confira o pedido no painel.") }

        printPending(storeId, now)
        response.jobs.forEach { job -> handleJob(storeId, job, response.queueSince) }
        announceUpdateIfDue(now)

        return response.pollSeconds * 1000L
    }

    private fun printPending(storeId: String, now: Long) {
        if (state.pending.isEmpty()) {
            WorkerStatus.printerProblem = null
            return
        }
        // A impressora não respondeu há pouco: espera um instante antes de insistir, senão cada
        // volta ficaria presa tentando conectar.
        if (now < nextPrintAttemptAtMs) return

        for ((orderId, label) in state.pending.toList()) {
            if (!running) return
            val receipt = when (val result = api.receipt(orderId)) {
                is ApiResult.Ok -> result.value
                // Sem a comanda em mãos não há o que imprimir: fica na fila para a próxima volta.
                else -> return
            }
            if (!receipt.printable) {
                // Cancelado entre a busca e a impressão: sai da fila sem papel.
                state.pending.remove(orderId)
                continue
            }
            val failure = printCopies(receipt.bytes, receipt.copies)
            if (failure == null) {
                OrderQueue.markPrinted(state, orderId, System.currentTimeMillis())
                queue.save(storeId, state)
                store.lastPrintedLabel = label
                store.lastPrintedAtMs = System.currentTimeMillis()
                failureNotified.remove(orderId)
                PrinterService.clearProblem(context, orderId.hashCode())
                api.markPrinted(orderId)
            } else {
                // O serviço parou no meio da impressão (Sair da conta): não é falha a avisar.
                if (!running) return
                onPrintFailure(orderId, label, failure)
                return // a impressora está com problema: não adianta tentar os outros agora
            }
        }
        WorkerStatus.printerProblem = null
    }

    /** Imprime as vias. `null` = todas saíram; senão, o motivo da falha. */
    private fun printCopies(bytes: ByteArray, copies: Int): String? {
        repeat(copies) {
            val result = BluetoothPrinter.print(context, store.printerAddress, bytes)
            if (result != PrintResult.Ok) return describeFailure(result)
        }
        return null
    }

    private fun onPrintFailure(orderId: String, label: String, reason: String) {
        val now = System.currentTimeMillis()
        nextPrintAttemptAtMs = now + PRINTER_RETRY_MS
        WorkerStatus.printerProblem = reason
        PrinterService.notifyProblem(context, orderId.hashCode(), "Comanda não saiu: $label", "$reason O app tenta de novo sozinho.")
        // Avisa na primeira falha de cada pedido e repete o alarme enquanto houver comanda
        // parada: ninguém fica olhando para este celular.
        if (failureNotified.add(orderId) || now - lastReminderAtMs >= REMINDER_MS) {
            lastReminderAtMs = now
            OrderSound.play(context)
        }
    }

    /** Impressão pedida pelo painel ("Reimprimir", Fechamento do Dia). */
    private fun handleJob(storeId: String, job: PollJob, queueSince: String) {
        val printedLocally = job.orderId?.let { state.printed.containsKey(it) }
        val claim = when (val result = api.claimJob(job.id, printedLocally, queueSince)) {
            is ApiResult.Ok -> result.value
            else -> return
        }
        // Outro aparelho pegou, ou o painel já desistiu e imprimiu pelo navegador.
        if (!claim.claimed) return

        val failure = printCopies(claim.bytes, claim.copies)
        api.finishJob(job.id, failure == null, failure)
        if (failure != null) {
            PrinterService.notifyProblem(context, job.id.hashCode(), "Não imprimiu: ${claim.title}", failure)
            return
        }
        val orderId = claim.orderId
        if (claim.countsAsReceipt && orderId != null) {
            // Esta reimpressão É a comanda que o aparelho ainda devia: não sai de novo sozinha.
            OrderQueue.markPrinted(state, orderId, System.currentTimeMillis())
            queue.save(storeId, state)
            api.markPrinted(orderId)
        }
        store.lastPrintedLabel = claim.title
        store.lastPrintedAtMs = System.currentTimeMillis()
    }

    /**
     * Ninguém abre este app no dia a dia, então é o serviço que avisa quando sai versão nova.
     * Confere de tempos em tempos e deixa UM aviso; atualizar continua sendo um toque da pessoa.
     */
    private fun announceUpdateIfDue(now: Long) {
        if (now - store.lastUpdateCheckAtMs < UPDATE_CHECK_MS) return
        store.lastUpdateCheckAtMs = now
        val available = Updater.check() as? UpdateCheck.Available ?: return
        PrinterService.notifyProblem(
            context,
            UPDATE_NOTIFICATION_ID,
            "Atualização do KyberFood Impressora",
            "Saiu a versão ${available.info.version}. Abra o app e toque em \"Atualizar agora\".",
        )
    }

    private fun describeFailure(result: PrintResult): String = when (result) {
        PrintResult.Ok -> ""
        PrintResult.NoPermission -> "Falta permitir o Bluetooth para o app."
        PrintResult.BluetoothOff -> "O Bluetooth do celular está desligado."
        PrintResult.NoPrinterChosen -> "Nenhuma impressora escolhida no app."
        is PrintResult.Unreachable -> "A impressora não respondeu (desligada, sem bateria ou longe)."
        is PrintResult.SendFailed -> "A conexão com a impressora caiu no meio da impressão."
    }

    private fun publishStatus() {
        WorkerStatus.pendingCount = state.pending.size
        val text = when {
            WorkerStatus.pendingCount > 0 && WorkerStatus.printerProblem != null ->
                "${WorkerStatus.pendingCount} comanda(s) esperando a impressora"
            WorkerStatus.problem != null -> WorkerStatus.problem!!
            else -> "Buscando pedidos. Não feche este aviso."
        }
        PrinterService.updateStatus(context, text)
    }

    private companion object {
        @Volatile
        private var lastThread: Thread? = null

        const val PREVIOUS_LOOP_WAIT_MS = 60_000L
        const val IDLE_MS = 15_000L
        const val RETRY_MS = 10_000L
        const val BEAT_MS = 30_000L
        const val PRINTER_RETRY_MS = 15_000L
        const val REMINDER_MS = 3 * 60_000L
        const val UPDATE_CHECK_MS = 12 * 60 * 60_000L
        const val UPDATE_NOTIFICATION_ID = 7001
    }
}
