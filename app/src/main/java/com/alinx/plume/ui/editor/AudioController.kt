package com.alinx.plume.ui.editor

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.alinx.plume.data.Recording
import com.alinx.plume.render.Bitmaps
import com.alinx.plume.render.PageRenderer
import java.io.File
import java.util.UUID

/** Enregistrement et relecture de l'audio d'une note, synchronisés avec l'encre. */
class AudioController(private val context: Context, private val state: EditorState) {

    var recording by mutableStateOf(false)
        private set
    var recordElapsed by mutableLongStateOf(0L)
        private set
    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recId: String? = null
    private var recStart = 0L

    private var player: MediaPlayer? = null
    var playing by mutableStateOf<Recording?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var position by mutableLongStateOf(0L)
        private set
    var duration by mutableLongStateOf(0L)
        private set

    fun startRecording(): Boolean {
        closePlayer()
        val id = UUID.randomUUID().toString()
        val f = Bitmaps.file("$id.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(96_000)
            r.setAudioSamplingRate(44_100)
            r.setOutputFile(f.path)
            r.prepare()
            r.start()
            recorder = r
            recFile = f
            recId = id
            recStart = SystemClock.elapsedRealtime()
            recordElapsed = 0L
            state.activeRecording = id to recStart
            recording = true
            true
        } catch (e: Exception) {
            r.release()
            f.delete()
            false
        }
    }

    fun updateElapsed() {
        if (recording) recordElapsed = SystemClock.elapsedRealtime() - recStart
    }

    fun stopRecording() {
        val r = recorder ?: return
        val dur = SystemClock.elapsedRealtime() - recStart
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        recording = false
        state.activeRecording = null
        val id = recId ?: return
        val f = recFile ?: return
        if (ok) state.addRecording(Recording(id, f.name, System.currentTimeMillis(), dur)) else f.delete()
    }

    fun open(rec: Recording) {
        closePlayer()
        val mp = MediaPlayer()
        try {
            mp.setDataSource(Bitmaps.file(rec.asset).path)
            mp.prepare()
        } catch (e: Exception) {
            mp.release()
            return
        }
        mp.setOnCompletionListener {
            isPlaying = false
            position = duration
            state.replay = PageRenderer.Replay(rec.id, Long.MAX_VALUE)
        }
        player = mp
        duration = mp.duration.toLong()
        position = 0L
        playing = rec
        state.replay = PageRenderer.Replay(rec.id, 0L)
        play()
    }

    fun play() {
        val mp = player ?: return
        if (position >= duration) seek(0L)
        mp.start()
        isPlaying = true
    }

    fun pause() {
        player?.pause()
        isPlaying = false
    }

    fun seek(ms: Long) {
        val mp = player ?: return
        val rec = playing ?: return
        val target = ms.coerceIn(0L, duration)
        mp.seekTo(target, MediaPlayer.SEEK_CLOSEST)
        position = target
        state.replay = PageRenderer.Replay(rec.id, target)
    }

    fun tick() {
        val mp = player ?: return
        val rec = playing ?: return
        if (isPlaying) {
            position = mp.currentPosition.toLong()
            state.replay = PageRenderer.Replay(rec.id, position)
        }
    }

    fun closePlayer() {
        player?.release()
        player = null
        playing = null
        isPlaying = false
        state.replay = null
    }

    fun deleteRecording(rec: Recording) {
        if (playing?.id == rec.id) closePlayer()
        state.removeRecording(rec.id)
        Bitmaps.file(rec.asset).delete()
    }

    fun release() {
        stopRecording()
        closePlayer()
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
