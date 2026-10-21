package com.anmol.crate

import android.animation.ValueAnimator
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer

/**
 * Plays 30-second previews. Switching albums fades the old one out while the new one
 * starts, and previews are served from the disk cache when prefetched, so changes feel instant.
 */
class PreviewPlayer(private val ctx: Context) {
    private var current: MediaPlayer? = null
    private var currentUrl: String? = null
    private var fadeIn: ValueAnimator? = null
    private var paused = false

    var onStateChanged: ((playing: Boolean) -> Unit)? = null

    val isPlaying get() = current?.isPlaying == true || (current != null && !paused && preparing)
    private var preparing = false

    fun play(url: String) {
        if (url == currentUrl && current != null) {
            if (paused) resume()
            return
        }
        fadeOutAndRelease(current)
        currentUrl = url
        paused = false
        preparing = true
        val mp = MediaPlayer()
        current = mp
        mp.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        mp.isLooping = true
        runCatching {
            val file = Catalog.cached(ctx, url)
            if (file != null) mp.setDataSource(file.path) else mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (current !== mp) return@setOnPreparedListener
                preparing = false
                if (paused) return@setOnPreparedListener
                mp.setVolume(0f, 0f)
                mp.start()
                fadeIn?.cancel()
                fadeIn = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 450
                    addUpdateListener { v -> runCatching { mp.setVolume(v.animatedFraction, v.animatedFraction) } }
                    start()
                }
                onStateChanged?.invoke(true)
            }
            mp.setOnErrorListener { _, _, _ -> preparing = false; onStateChanged?.invoke(false); true }
            mp.prepareAsync()
            if (file == null) Catalog.prefetch(ctx, url)
        }.onFailure { preparing = false }
        onStateChanged?.invoke(true)
    }

    fun toggle() {
        if (current == null) return
        if (paused) resume() else pause()
    }

    fun pause() {
        paused = true
        runCatching { if (current?.isPlaying == true) current?.pause() }
        onStateChanged?.invoke(false)
    }

    fun resume() {
        paused = false
        runCatching { if (!preparing) current?.start() }
        onStateChanged?.invoke(true)
    }

    fun release() {
        fadeIn?.cancel()
        runCatching { current?.release() }
        current = null
        currentUrl = null
    }

    private fun fadeOutAndRelease(mp: MediaPlayer?) {
        mp ?: return
        fadeIn?.cancel()
        if (runCatching { mp.isPlaying }.getOrDefault(false)) {
            ValueAnimator.ofFloat(1f, 0f).apply {
                duration = 220
                addUpdateListener { v -> runCatching { mp.setVolume(v.animatedValue as Float, v.animatedValue as Float) } }
                doOnEnd { runCatching { mp.release() } }
                start()
            }
        } else {
            runCatching { mp.release() }
        }
    }
}

private fun ValueAnimator.doOnEnd(block: () -> Unit) {
    addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = block()
    })
}
