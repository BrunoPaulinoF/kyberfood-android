package br.com.kyberfood.impressora.sound

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * O alarme de pedido novo — o MESMO toque do app desktop (`playNotificationSound`):
 * Sol, Si e Mi agudo, três vezes, em ~1,8 s.
 */
object AlarmTone {
    const val SAMPLE_RATE = 44_100
    private const val CYCLES = 3
    private const val CYCLE_SECONDS = 0.6

    private data class Note(val freq: Double, val start: Double, val duration: Double, val sine: Boolean, val peak: Double)

    private val notes: List<Note> = (0 until CYCLES).flatMap { cycle ->
        val base = cycle * CYCLE_SECONDS
        listOf(
            Note(784.0, base + 0.0, 0.15, sine = false, peak = 0.85),
            Note(988.0, base + 0.17, 0.15, sine = false, peak = 0.85),
            Note(1319.0, base + 0.34, 0.22, sine = true, peak = 0.95),
        )
    }

    val durationMs: Long = (CYCLES * CYCLE_SECONDS * 1000).toLong()

    fun samples(): ShortArray {
        val total = (CYCLES * CYCLE_SECONDS * SAMPLE_RATE).roundToInt()
        val out = ShortArray(total)
        for (note in notes) {
            val first = (note.start * SAMPLE_RATE).toInt()
            val count = (note.duration * SAMPLE_RATE).toInt()
            for (i in 0 until count) {
                val index = first + i
                if (index >= total) break
                val t = i.toDouble() / SAMPLE_RATE
                val phase = (t * note.freq) % 1.0
                val wave = if (note.sine) sin(2 * PI * phase) else 1.0 - 4.0 * abs(phase - 0.5)
                // Ataque rápido e cauda curta: soa "urgente" e não estala.
                val attack = minOf(1.0, t / 0.015)
                val release = minOf(1.0, (note.duration - t) / 0.04)
                out[index] = (wave * note.peak * attack * release * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }
}

/**
 * Toca o alarme pelo canal de ALARME do celular, com o volume desse canal no MÁXIMO.
 * O canal de alarme não obedece ao modo silencioso nem ao volume de toque/mídia — é o
 * mesmo que o despertador usa. (Só o "Não perturbe: silêncio total" consegue calar.)
 */
object OrderSound {
    @Volatile
    private var playing = false

    fun play(context: Context) {
        if (playing) return
        playing = true
        val appContext = context.applicationContext
        thread(name = "kyberfood-alarme") {
            try {
                val audio = appContext.getSystemService(AudioManager::class.java)
                runCatching {
                    audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                }
                val pcm = AlarmTone.samples()
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AlarmTone.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep(AlarmTone.durationMs + 250L)
                } finally {
                    runCatching { track.stop() }
                    track.release()
                }
            } catch (_: Exception) {
                // Som que falhou nunca derruba o app.
            } finally {
                playing = false
            }
        }
    }
}
