package com.example.service

import android.media.MediaPlayer
import android.util.Log
import java.io.File

class AudioPlayer {
    private var mediaPlayer: MediaPlayer? = null
    var currentFile: File? = null
        private set

    fun play(file: File, onComplete: () -> Unit) {
        try {
            stop()
            currentFile = file
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    onComplete()
                    stop()
                }
                start()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Playback failed", e)
        }
    }

    fun pause() {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Pause failed", e)
        }
    }

    fun resume() {
        try {
            if (mediaPlayer != null && !mediaPlayer!!.isPlaying) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Resume failed", e)
        }
    }

    fun stop() {
        try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Release failed", e)
        } finally {
            mediaPlayer = null
            currentFile = null
        }
    }

    fun isPlaying(): Boolean {
        return try {
            mediaPlayer?.isPlaying ?: false
        } catch (e: Exception) {
            false
        }
    }

    val currentProgress: Float
        get() = try {
            val mp = mediaPlayer
            if (mp != null && mp.duration > 0) {
                mp.currentPosition.toFloat() / mp.duration
            } else {
                0f
            }
        } catch (e: Exception) {
            0f
        }

    val currentPosition: Int
        get() = try {
            mediaPlayer?.currentPosition ?: 0
        } catch (e: Exception) {
            0
        }

    val duration: Int
        get() = try {
            mediaPlayer?.duration ?: 0
        } catch (e: Exception) {
            0
        }

    fun seekTo(progress: Float) {
        try {
            val mp = mediaPlayer
            if (mp != null) {
                val position = (progress * mp.duration).toInt()
                mp.seekTo(position)
            }
        } catch (e: Exception) {
            Log.e("AudioPlayer", "Seek failed", e)
        }
    }
}
