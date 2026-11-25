package br.com.kyberfood.impressora.print

import java.io.ByteArrayOutputStream

/**
 * Comandos que a impressora térmica entende (ESC/POS).
 *
 * Aqui mora só a PÁGINA DE TESTE do aparelho. A comanda de pedido NÃO é montada no celular:
 * ela chega pronta do servidor, para não nascer mais uma cópia da montagem da comanda.
 */
object EscPos {
    /** O corpo vai em ASCII, como no app desktop (`to_ascii_bytes`): acento vira letra simples. */
    fun ascii(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length)
        for (ch in text) {
            val mapped = when (ch) {
                'á', 'à', 'â', 'ã', 'ä' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'í', 'ì', 'î', 'ï' -> 'i'
                'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                'ú', 'ù', 'û', 'ü' -> 'u'
                'ç' -> 'c'
                'Á', 'À', 'Â', 'Ã', 'Ä' -> 'A'
                'É', 'È', 'Ê', 'Ë' -> 'E'
                'Í', 'Ì', 'Î', 'Ï' -> 'I'
                'Ó', 'Ò', 'Ô', 'Õ', 'Ö' -> 'O'
                'Ú', 'Ù', 'Û', 'Ü' -> 'U'
                'Ç' -> 'C'
                else -> ch
            }
            // Só ASCII imprimível: byte de controle vindo de um texto viraria COMANDO.
            out.write(if (mapped.code in 0x20..0x7E) mapped.code else ' '.code)
        }
        return out.toByteArray()
    }

    /** Régua "1234567890…" com exatamente `cols` caracteres: mostra se o papel escolhido está certo. */
    fun ruler(cols: Int): String = buildString { for (i in 1..cols) append(i % 10) }

    fun testPage(cols: Int, storeName: String, dateTime: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun raw(vararg bytes: Int) = bytes.forEach { out.write(it) }
        fun line(text: String = "") {
            out.write(ascii(text.take(cols)))
            out.write('\n'.code)
        }
        fun strong(text: String) {
            raw(0x1B, 0x45, 0x01) // ESC E 1: negrito
            raw(0x1D, 0x21, 0x01) // GS ! 1: altura dupla (a largura não muda)
            line(text)
            raw(0x1B, 0x45, 0x00)
            raw(0x1D, 0x21, 0x00)
        }

        raw(0x1B, 0x40) // ESC @: inicia a impressora
        raw(0x1B, 0x61, 0x01) // ESC a 1: centralizado
        strong("KYBERFOOD")
        line("TESTE DE IMPRESSAO")
        if (storeName.isNotBlank()) line(storeName)
        line(dateTime)
        raw(0x1B, 0x61, 0x00) // ESC a 0: esquerda
        line("-".repeat(cols))
        line(ruler(cols))
        line("-".repeat(cols))
        line("A regua acima tem que sair em")
        line("UMA linha so. Se quebrou em")
        line("duas, troque o papel no app.")
        line()
        raw(0x1B, 0x61, 0x01)
        strong("*** IMPRESSORA OK ***")
        raw(0x1B, 0x61, 0x00)
        // Mesmo fim da comanda do desktop: avança o papel e pede o corte (quem não corta, ignora).
        out.write("\n\n\n\n".toByteArray())
        raw(0x1D, 0x56, 0x42, 0x00)
        return out.toByteArray()
    }
}
