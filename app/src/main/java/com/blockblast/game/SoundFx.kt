package com.blockblast.game

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Tiny synthesized sound effects - no audio files needed. */
class SoundFx {
    @Volatile
    var enabled = true

    private val sr = 22050
    private val scale = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.0, 1046.5, 1174.7, 1318.5)

    private fun synth(notes: List<Pair<Double, Int>>, vol: Double): ShortArray {
        var total = 0
        for (n in notes) total += n.second * sr / 1000
        val out = ShortArray(total)
        var pos = 0
        for ((freq, ms) in notes) {
            val n = ms * sr / 1000
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val attack = min(1.0, i / 120.0)
                val decay = (1.0 - i.toDouble() / n).pow(1.6)
                val v = (sin(2 * PI * freq * t) + 0.35 * sin(4 * PI * freq * t)) * attack * decay * vol
                out[pos + i] = (v * 20000).toInt().coerceIn(-32768, 32767).toShort()
            }
            pos += n
        }
        return out
    }

    private val pickBuf = synth(listOf(880.0 to 30), 0.35)
    private val placeBuf = synth(listOf(300.0 to 40, 220.0 to 70), 0.55)
    private val perfectBuf = synth(
        listOf(523.25 to 90, 659.25 to 90, 783.99 to 90, 1046.5 to 90, 1318.5 to 280), 0.5
    )
    private val overBuf = synth(listOf(392.0 to 180, 330.0 to 180, 262.0 to 180, 196.0 to 420), 0.5)
    private val clearBufs = HashMap<Int, ShortArray>()

    fun pick() = play(pickBuf)
    fun place() = play(placeBuf)
    fun perfect() = play(perfectBuf)
    fun over() = play(overBuf)

    fun clear(level: Int) {
        val lvl = level.coerceIn(1, 7)
        val buf = clearBufs.getOrPut(lvl) {
            val count = (2 + lvl).coerceAtMost(scale.size)
            val notes = ArrayList<Pair<Double, Int>>()
            for (i in 0 until count) notes.add(scale[i] to (if (i == count - 1) 160 else 55))
            synth(notes, 0.5)
        }
        play(buf)
    }

    private fun play(buf: ShortArray) {
        if (!enabled) return
        Thread {
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_GAME)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(buf.size * 1000L / sr + 80)
                track.release()
            } catch (e: Exception) {
                // sound is optional - ignore any audio errors
            }
        }.start()
    }
}
