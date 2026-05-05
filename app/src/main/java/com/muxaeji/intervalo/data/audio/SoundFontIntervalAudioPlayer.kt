package com.muxaeji.intervalo.data.audio

import android.app.Application
import com.muxaeji.intervalo.domain.Note
import dev.kotlinds.fluidsynthkmp.AudioConfig
import dev.kotlinds.fluidsynthkmp.FluidSynthPlayer
import dev.kotlinds.fluidsynthkmp.Interpolation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders intervals via FluidSynth + SF2 (Chorium by default). Copies the SoundFont from assets
 * to app files dir on first use (FluidSynth loads by file path).
 *
 * Implementation note: [FluidSynthPlayer] starts a built-in Oboe audio driver on construction
 * (see fluidsynth-kmp README and bundled liboboe.so). Earlier versions of this file additionally
 * drove output through [FluidSynthPlayer.renderFloat] + [playMono16Pcm], which created a race:
 * both the Oboe driver thread and our render thread pulled audio from the same synth, producing
 * doubled / cracking playback and rapid resource churn. We now drive playback solely via the
 * built-in driver by sending [FluidSynthPlayer.noteOn] / [FluidSynthPlayer.noteOff] on a timeline.
 */
class SoundFontIntervalAudioPlayer(
    private val application: Application,
    private val timing: IntervalPlaybackTiming = IntervalPlaybackTiming(),
    private val assetPath: String = "soundfonts/Chorium.SF2",
    private val channel: Int = 0,
    private val presetProgram: Int = 0
) : IntervalAudioPlayer {

    private val mutex = Mutex()
    @Volatile private var engine: FluidSynthPlayer? = null

    override suspend fun playInterval(root: Note, top: Note) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val player = ensureEngineLocked()
                val tone = timing.toneDurationMs.toLong()
                val pause = timing.chordToArpeggioPauseMs
                val gap = timing.arpeggioGapMs

                try {
                    player.noteOn(channel, root.midi, NOTE_VELOCITY)
                    player.noteOn(channel, top.midi, NOTE_VELOCITY)
                    delay(tone)
                    player.noteOff(channel, root.midi)
                    player.noteOff(channel, top.midi)

                    delay(pause)

                    val note2Start = (tone + gap).coerceAtLeast(0L)
                    val note1End = tone
                    val note2End = note2Start + tone

                    player.noteOn(channel, root.midi, NOTE_VELOCITY)

                    val events = listOf(
                        TimedEvent(note2Start) { player.noteOn(channel, top.midi, NOTE_VELOCITY) },
                        TimedEvent(note1End) { player.noteOff(channel, root.midi) },
                        TimedEvent(note2End) { player.noteOff(channel, top.midi) }
                    ).sortedBy { it.timeMs }

                    var elapsed = 0L
                    for (event in events) {
                        val wait = event.timeMs - elapsed
                        if (wait > 0L) delay(wait)
                        event.action()
                        elapsed = event.timeMs
                    }

                    delay(RELEASE_TAIL_MS)
                } finally {
                    withContext(NonCancellable) {
                        runCatching {
                            player.noteOff(channel, root.midi)
                            player.noteOff(channel, top.midi)
                        }
                    }
                }
            }
        }
    }

    /**
     * Releases the underlying FluidSynth engine (and its Oboe audio driver). Safe to call multiple
     * times. After [close], the next [playInterval] call will lazily re-create the engine.
     */
    override suspend fun close() {
        mutex.withLock {
            val current = engine ?: return
            engine = null
            runCatching {
                for (k in 0..127) current.noteOff(channel, k)
            }
            runCatching { current.close() }
        }
    }

    private fun ensureEngineLocked(): FluidSynthPlayer {
        engine?.let { return it }
        val path = ensureSf2OnDisk()
        val p = FluidSynthPlayer(
            AudioConfig(
                sampleRate = 44100,
                interpolation = Interpolation.HIGH,
                periodSize = 256,
                periods = 2
            )
        )
        val id = p.loadSoundFont(path)
        require(id >= 0) { "loadSoundFont failed for $path" }
        p.programChange(channel, presetProgram)
        p.setGain(0.42f)
        p.setReverb(0.0, 0.0, 0.0, 0.0)
        p.setChorus(0, 0.0, 0.5, 0.0)
        engine = p
        return p
    }

    private fun ensureSf2OnDisk(): String {
        val out = File(application.filesDir, "Chorium.SF2")
        if (!out.exists() || out.length() == 0L) {
            application.assets.open(assetPath).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return out.absolutePath
    }

    private data class TimedEvent(val timeMs: Long, val action: () -> Unit)

    private companion object {
        const val NOTE_VELOCITY = 96
        const val RELEASE_TAIL_MS = 250L
    }
}
