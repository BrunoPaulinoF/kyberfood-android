package br.com.kyberfood.impressora

import br.com.kyberfood.impressora.data.Relogin
import br.com.kyberfood.impressora.print.EscPos
import br.com.kyberfood.impressora.sound.AlarmTone
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {
    @Test
    fun `a primeira tentativa de relogin nunca espera`() {
        assertEquals(0L, Relogin.backoffMs(0))
        assertEquals(0L, Relogin.waitMs(nowMs = 1_000_000L, lastAttemptMs = 0L, streak = 0))
    }

    @Test
    fun `a espera dobra de 60 s ate o teto de 15 min e nunca passa dele`() {
        assertEquals(60_000L, Relogin.backoffMs(1))
        assertEquals(120_000L, Relogin.backoffMs(2))
        assertEquals(240_000L, Relogin.backoffMs(3))
        assertEquals(Relogin.MAX_INTERVAL_MS, Relogin.backoffMs(5))
        assertEquals(Relogin.MAX_INTERVAL_MS, Relogin.backoffMs(500))
    }

    @Test
    fun `o silencio que zera o freio e maior que o teto da espera`() {
        // Senão o próprio adiamento zeraria o contador e o freio nunca chegaria ao topo.
        assertTrue(Relogin.STABLE_MS > Relogin.MAX_INTERVAL_MS)
        assertEquals(0L, Relogin.waitMs(nowMs = Relogin.STABLE_MS + 1, lastAttemptMs = 1L, streak = 9))
    }

    @Test
    fun `tentativa barrada espera so o que falta`() {
        assertEquals(40_000L, Relogin.waitMs(nowMs = 120_000L, lastAttemptMs = 100_000L, streak = 1))
        assertEquals(0L, Relogin.waitMs(nowMs = 200_000L, lastAttemptMs = 100_000L, streak = 1))
    }

    @Test
    fun `acento vira letra simples e byte de controle vira espaco`() {
        assertArrayEquals("Acai com acucar".toByteArray(), EscPos.ascii("Açaí com açúcar"))
        // ESC dentro de um texto viraria comando para a impressora.
        assertArrayEquals("a b".toByteArray(), EscPos.ascii("a\u001Bb"))
    }

    @Test
    fun `a regua tem exatamente a largura do papel`() {
        assertEquals(32, EscPos.ruler(32).length)
        assertEquals(48, EscPos.ruler(48).length)
        assertTrue(EscPos.ruler(32).startsWith("1234567890"))
    }

    @Test
    fun `a pagina de teste comeca iniciando a impressora e nenhuma linha passa do papel`() {
        for (cols in listOf(32, 48)) {
            val bytes = EscPos.testPage(cols, "Loja com um nome bem comprido para estourar a largura do papel", "10/11/2026 12:00")
            assertEquals(0x1B, bytes[0].toInt())
            assertEquals(0x40, bytes[1].toInt())
            val text = String(bytes.filter { it in 0x20..0x7E || it == '\n'.code.toByte() }.toByteArray())
            // Comandos deixam até 3 caracteres imprimíveis colados na linha (ex.: "E", "!", "a").
            assertTrue(text.lines().all { it.length <= cols + 6 })
            assertTrue(text.contains("IMPRESSORA OK"))
        }
    }

    @Test
    fun `o alarme dura cerca de 1,8 s e toca alto`() {
        val samples = AlarmTone.samples()
        assertEquals(AlarmTone.SAMPLE_RATE * 18 / 10, samples.size)
        assertTrue(samples.maxOf { kotlin.math.abs(it.toInt()) } > Short.MAX_VALUE * 0.8)
    }
}

class OrderQueueTest {
    private fun entry(id: String, action: String, alert: Boolean = true) =
        br.com.kyberfood.impressora.service.PollEntry(id, action, alert, "Pedido $id")

    private val queue = br.com.kyberfood.impressora.service.OrderQueue
    private fun state() = br.com.kyberfood.impressora.service.QueueState()

    @Test
    fun `pedido para imprimir entra na fila e toca uma vez so`() {
        val state = state()
        val first = queue.apply(listOf(entry("a", "print")), state, 1_000L)
        assertTrue(first.ring)
        assertEquals(listOf("a"), state.pending.keys.toList())
        // O mesmo pedido volta na busca seguinte (a folga do cursor): nada se repete.
        val again = queue.apply(listOf(entry("a", "print")), state, 2_000L)
        assertEquals(false, again.ring)
        assertEquals(1, state.pending.size)
    }

    @Test
    fun `comanda impressa nunca volta para a fila`() {
        val state = state()
        queue.apply(listOf(entry("a", "print")), state, 1_000L)
        queue.markPrinted(state, "a", 2_000L)
        queue.apply(listOf(entry("a", "print")), state, 3_000L)
        assertTrue(state.pending.isEmpty())
        assertTrue(state.printed.containsKey("a"))
    }

    @Test
    fun `pedido cancelado sai da fila de tentativas sem ser dado como impresso`() {
        val state = state()
        queue.apply(listOf(entry("a", "print")), state, 1_000L)
        queue.apply(listOf(entry("a", "none", alert = false)), state, 2_000L)
        assertTrue(state.pending.isEmpty())
        assertEquals(false, state.printed.containsKey("a"))
    }

    @Test
    fun `resolver da o pedido como resolvido sem papel`() {
        val state = state()
        queue.apply(listOf(entry("a", "resolve")), state, 1_000L)
        assertTrue(state.pending.isEmpty())
        assertTrue(state.printed.containsKey("a"))
    }

    @Test
    fun `pedido velho avisa uma vez e nao imprime`() {
        val state = state()
        val first = queue.apply(listOf(entry("a", "stale", alert = false)), state, 1_000L)
        assertEquals(listOf("Pedido a"), first.stale)
        assertEquals(false, first.ring)
        val again = queue.apply(listOf(entry("a", "stale", alert = false)), state, 2_000L)
        assertTrue(again.stale.isEmpty())
        assertTrue(state.pending.isEmpty())
    }

    @Test
    fun `acao que o app nao conhece nao mexe em nada`() {
        val state = state()
        queue.apply(listOf(entry("a", "print")), state, 1_000L)
        queue.apply(listOf(entry("a", "acao_do_futuro", alert = false)), state, 2_000L)
        assertEquals(1, state.pending.size)
    }

    @Test
    fun `a ordem da fila e a de chegada`() {
        val state = state()
        queue.apply(listOf(entry("a", "print"), entry("b", "print"), entry("c", "print")), state, 1_000L)
        assertEquals(listOf("a", "b", "c"), state.pending.keys.toList())
    }

    @Test
    fun `o que foi impresso ha mais de dois dias e esquecido`() {
        val state = state()
        queue.markPrinted(state, "velho", 0L)
        queue.markPrinted(state, "novo", queue.MEMORY_MS)
        queue.prune(state, queue.MEMORY_MS + 1)
        assertEquals(setOf("novo"), state.printed.keys)
    }
}

class UpdaterTest {
    @Test
    fun `so e atualizacao o que e mais novo que o instalado`() {
        val updater = br.com.kyberfood.impressora.data.Updater
        assertTrue(updater.isNewer(publishedCode = 105, installedCode = 104))
        assertEquals(false, updater.isNewer(publishedCode = 104, installedCode = 104))
        // Versão publicada mais ANTIGA que a instalada nunca é oferecida (não se desatualiza).
        assertEquals(false, updater.isNewer(publishedCode = 103, installedCode = 104))
    }
}
