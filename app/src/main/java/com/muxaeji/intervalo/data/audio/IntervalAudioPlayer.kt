package com.muxaeji.intervalo.data.audio

import com.muxaeji.intervalo.domain.Note
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.sin

interface IntervalAudioPlayer {
    suspend fun playInterval(root: Note, top: Note)

    /**
     * Releases any held native / audio resources (e.g. FluidSynth + Oboe driver). Safe to call
     * multiple times; the player may lazily re-acquire resources on the next [playInterval].
     * Default no-op for stateless players.
     */
    suspend fun close() {}
}

class SineWaveIntervalAudioPlayer(
    private val timing: IntervalPlaybackTiming = IntervalPlaybackTiming(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default
) : IntervalAudioPlayer {
    private val sampleRate = 44_100

    override suspend fun playInterval(root: Note, top: Note) = withContext(dispatcher) {
        playChord(root.frequencyHz, top.frequencyHz)
        delay(timing.chordToArpeggioPauseMs)
        val a = generatePcm(timing.toneDurationMs, root.frequencyHz, null)
        val b = generatePcm(timing.toneDurationMs, top.frequencyHz, null)
        val arpeggio = ArpeggioMixer.mergeSequential(a, b, sampleRate, timing.arpeggioGapMs)
        playMono16Pcm(sampleRate, arpeggio)
    }

    private fun playChord(freqA: Double, freqB: Double) {
        val pcm = generatePcm(timing.toneDurationMs, freqA, freqB)
        playMono16Pcm(sampleRate, pcm)
    }

    private fun generatePcm(durationMs: Int, freqA: Double, freqB: Double?): ShortArray {
        val sampleCount = sampleRate * durationMs / 1000
        val rampSize = sampleRate / 100
        val result = ShortArray(sampleCount)
        for (i in 0 until sampleCount) {
            val t = i.toDouble() / sampleRate
            var value = sin(2.0 * PI * freqA * t)
            if (freqB != null) {
                value = (value + sin(2.0 * PI * freqB * t)) * 0.5
            }
            val env = when {
                i < rampSize -> i.toDouble() / rampSize
                i > sampleCount - rampSize -> (sampleCount - i).toDouble() / rampSize
                else -> 1.0
            }
            result[i] = (value * env * Short.MAX_VALUE * 0.25).toInt().toShort()
        }
        return result
    }
}
