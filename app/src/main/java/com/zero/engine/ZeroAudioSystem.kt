package com.zero.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.SoundPool
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

class ZeroAudioSystem(
    private val projectDir: File,
    private val onConsoleLog: (String) -> Unit
) {
    private val audioLock = Any()
    private var activeBgm: MediaPlayer? = null
    private var fadingBgm: MediaPlayer? = null
    private var activeBgmFile: String = ""

    private var masterVolume = 1.0f
    private var bgmBusVolume = 0.85f
    private var sfxBusVolume = 1.0f

    private var activeTrackVol = 0.8f
    private var activeTargetVol = 0.8f
    private var fadingStartVol = 0.8f
    private var fadeElapsed = 0f
    private var fadeDuration = 0f

    private val soundPool: SoundPool = SoundPool.Builder()
        .setMaxStreams(12)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val sfxCache = ConcurrentHashMap<String, Int>()

    fun setVolume(bus: String, volume: Float) = synchronized(audioLock) {
        val v = volume.coerceIn(0f, 1f)
        when (bus.lowercase()) {
            "master" -> masterVolume = v
            "bgm" -> bgmBusVolume = v
            "sfx" -> sfxBusVolume = v
        }
        applyBgmVolumesLocked()
    }

    fun getVolume(bus: String): Float = synchronized(audioLock) {
        when (bus.lowercase()) {
            "master" -> masterVolume
            "bgm" -> bgmBusVolume
            "sfx" -> sfxBusVolume
            else -> masterVolume
        }
    }

    fun playBGM(fileName: String, loop: Boolean, volume: Float) {
        crossfadeBGM(fileName, 0f, loop, volume)
    }

    fun crossfadeBGM(fileName: String, durationSec: Float, loop: Boolean, volume: Float) = synchronized(audioLock) {
        try {
            val file = File(File(projectDir, "audio"), fileName)
            if (!file.exists()) {
                onConsoleLog("[AUDIO 警告] 找不到 BGM 文件: $fileName")
                return@synchronized
            }
            val targetVol = volume.coerceIn(0f, 1f)
            if (activeBgm != null && activeBgmFile == fileName && durationSec > 0.01f) {
                activeTargetVol = targetVol
                fadeDuration = durationSec
                fadeElapsed = 0f
                return@synchronized
            }

            releasePlayerSafe(fadingBgm)
            fadingBgm = null

            val dur = max(0f, durationSec)
            if (dur > 0.02f && activeBgm != null) {
                fadingBgm = activeBgm
                fadingStartVol = activeTrackVol
                activeBgm = null
            } else {
                releasePlayerSafe(activeBgm)
                activeBgm = null
            }

            val startVol = if (dur > 0.02f) 0f else targetVol
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                isLooping = loop
                val finalV = (startVol * bgmBusVolume * masterVolume).coerceIn(0f, 1f)
                setVolume(finalV, finalV)
                prepare()
                start()
            }
            activeBgm = mp
            activeBgmFile = fileName
            activeTrackVol = startVol
            activeTargetVol = targetVol
            fadeDuration = if (dur > 0.02f) dur else 0f
            fadeElapsed = 0f
        } catch (e: Exception) {
            onConsoleLog("[AUDIO BGM 错误] ${e.message}")
        }
    }

    fun update(dt: Float) = synchronized(audioLock) {
        if (fadeDuration <= 0f) return@synchronized
        fadeElapsed += dt
        val t = (fadeElapsed / fadeDuration).coerceIn(0f, 1f)
        activeTrackVol = activeTargetVol * t
        val fadeOutVol = fadingStartVol * (1f - t)
        applyBgmVolumesLocked(fadeOutOverride = fadeOutVol)
        if (t >= 1f) {
            releasePlayerSafe(fadingBgm)
            fadingBgm = null
            fadeDuration = 0f
            fadeElapsed = 0f
            activeTrackVol = activeTargetVol
            applyBgmVolumesLocked()
        }
    }

    private fun applyBgmVolumesLocked(fadeOutOverride: Float? = null) {
        val mainV = (activeTrackVol * bgmBusVolume * masterVolume).coerceIn(0f, 1f)
        try { activeBgm?.setVolume(mainV, mainV) } catch (_: Exception) {}
        if (fadingBgm != null && fadeOutOverride != null) {
            val outV = (fadeOutOverride * bgmBusVolume * masterVolume).coerceIn(0f, 1f)
            try { fadingBgm?.setVolume(outV, outV) } catch (_: Exception) {}
        }
    }

    fun stopBGM(fadeOutSec: Float = 0f) = synchronized(audioLock) {
        if (fadeOutSec > 0.02f && activeBgm != null) {
            releasePlayerSafe(fadingBgm)
            fadingBgm = activeBgm
            fadingStartVol = activeTrackVol
            activeBgm = null
            activeBgmFile = ""
            activeTrackVol = 0f
            activeTargetVol = 0f
            fadeDuration = fadeOutSec
            fadeElapsed = 0f
            return@synchronized
        }
        releasePlayerSafe(activeBgm)
        releasePlayerSafe(fadingBgm)
        activeBgm = null
        fadingBgm = null
        activeBgmFile = ""
        fadeDuration = 0f
    }

    fun playSFX(fileName: String, volume: Float, pitchMin: Float = 1.0f, pitchMax: Float = 1.0f) {
        try {
            val finalVol = (volume.coerceIn(0f, 1f) * sfxBusVolume * masterVolume).coerceIn(0f, 1f)
            val pMin = pitchMin.coerceIn(0.5f, 2.0f)
            val pMax = pitchMax.coerceIn(0.5f, 2.0f)
            val rate = if (pMax > pMin) {
                (pMin + Math.random().toFloat() * (pMax - pMin)).coerceIn(0.5f, 2.0f)
            } else pMin

            val cachedId = sfxCache[fileName]
            if (cachedId != null) {
                soundPool.play(cachedId, finalVol, finalVol, 1, 0, rate)
                return
            }
            val file = File(File(projectDir, "audio"), fileName)
            if (!file.exists()) {
                onConsoleLog("[AUDIO 警告] 找不到音效文件: $fileName")
                return
            }
            val sid = soundPool.load(file.absolutePath, 1)
            sfxCache[fileName] = sid
            soundPool.setOnLoadCompleteListener { sp, sampleId, status ->
                if (status == 0 && sampleId == sid) {
                    sp.play(sampleId, finalVol, finalVol, 1, 0, rate)
                }
            }
        } catch (e: Exception) {
            onConsoleLog("[AUDIO SFX 错误] ${e.message}")
        }
    }

    fun playToneAsync(hz: Int, ms: Int) {
        val volScale = (sfxBusVolume * masterVolume).coerceIn(0f, 1f)
        if (volScale <= 0.001f) return
        Thread {
            try {
                val sampleRate = 22050
                val count = (sampleRate * (ms / 1000.0)).toInt()
                val buf = ShortArray(count)
                for (i in 0 until count) {
                    val env = 1.0 - (i.toDouble() / count)
                    buf[i] = (sin(2.0 * PI * i * hz / sampleRate) * 12000 * env * volScale).toInt().toShort()
                }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buf.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(buf, 0, buf.size)
                track.play()
                Thread.sleep(ms.toLong() + 30)
                track.release()
            } catch (_: Exception) {}
        }.start()
    }

    private fun releasePlayerSafe(mp: MediaPlayer?) {
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
    }

    fun release() {
        stopBGM(0f)
        try { soundPool.release() } catch (_: Exception) {}
    }
}
