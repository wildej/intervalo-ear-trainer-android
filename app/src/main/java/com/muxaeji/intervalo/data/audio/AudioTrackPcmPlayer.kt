package com.muxaeji.intervalo.data.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack

private const val FADE_MS = 8

internal fun playMono16Pcm(sampleRate: Int, pcm: ShortArray) {
    if (pcm.isEmpty()) return

    val faded = applyEdgeFades(pcm, sampleRate)

    val minSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    val bufferSizeBytes = maxOf(minSize, faded.size * 2)
    val track = AudioTrack(
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build(),
        AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build(),
        bufferSizeBytes,
        AudioTrack.MODE_STATIC,
        AudioManager.AUDIO_SESSION_ID_GENERATE
    )
    try {
        track.write(faded, 0, faded.size, AudioTrack.WRITE_BLOCKING)
        track.play()
        while (track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            track.playbackHeadPosition < faded.size
        ) {
            Thread.sleep(10)
        }
        track.stop()
    } finally {
        track.release()
    }
}

/**
 * Apply a short linear fade-in and fade-out at the edges of [pcm] to avoid clicks when [AudioTrack]
 * starts/stops. Most of our renderers already produce enveloped audio for individual notes, but the
 * merged buffer (chord, arpeggio) can still end mid-sustain — that abrupt edge is what users hear
 * as a "crack". We work on a copy so callers can safely cache their PCM.
 */
private fun applyEdgeFades(pcm: ShortArray, sampleRate: Int): ShortArray {
    val maxFade = pcm.size / 4
    val fadeFrames = (sampleRate * FADE_MS / 1000).coerceAtMost(maxFade)
    if (fadeFrames <= 0) return pcm

    val out = pcm.copyOf()
    for (i in 0 until fadeFrames) {
        val gain = (i + 1).toFloat() / fadeFrames
        out[i] = (out[i] * gain).toInt().toShort()
        val tail = out.size - 1 - i
        out[tail] = (out[tail] * gain).toInt().toShort()
    }
    return out
}
