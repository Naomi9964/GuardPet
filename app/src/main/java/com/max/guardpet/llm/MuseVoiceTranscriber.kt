package com.max.guardpet.llm

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

// File-based Muse Voice transcription (muse-voice-transcribe-1.0).
// Same key and model as the realtime endpoint, but a plain multipart POST,
// so it fits the zero-dependency HttpURLConnection pattern. Verified live
// 2026-10-28: request part (JSON) + audio part (WAV), Bearer auth,
// response carries "transcript" and "turns".
class MuseVoiceTranscriber(private val apiKey: String) {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val ENDPOINT = "https://api.meta.ai/v1/asr/transcribe"
    }

    // Records mic audio until stop() is called. No audio file is kept after
    // transcription: the WAV lives in cache and is deleted by the caller.
    class Recorder(private val outFile: File) {
        @Volatile
        private var recording = false
        private var rec: AudioRecord? = null
        private var thread: Thread? = null
        private val pcm = ByteArrayOutputStream()

        fun start(): Boolean {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) return false
            val r = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
            } catch (_: Exception) {
                return false
            }
            if (r.state != AudioRecord.STATE_INITIALIZED) return false
            rec = r
            recording = true
            thread = Thread {
                val buf = ByteArray(minBuf)
                r.startRecording()
                while (recording) {
                    val n = r.read(buf, 0, buf.size)
                    if (n > 0) synchronized(pcm) { pcm.write(buf, 0, n) } else if (n < 0) break
                }
            }
            thread?.start()
            return true
        }

        // Returns the WAV file, or null when nothing was recorded.
        fun stop(): File? {
            recording = false
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            try {
                thread?.join(3000)
            } catch (_: Exception) {
            }
            try {
                rec?.release()
            } catch (_: Exception) {
            }
            rec = null
            val bytes = synchronized(pcm) { pcm.toByteArray() }
            if (bytes.isEmpty()) return null
            return try {
                FileOutputStream(outFile).use {
                    it.write(wavHeader(bytes.size))
                    it.write(bytes)
                }
                outFile
            } catch (_: Exception) {
                null
            }
        }

        private fun wavHeader(dataSize: Int): ByteArray {
            val out = ByteArrayOutputStream()
            fun str(s: String) = out.write(s.toByteArray(StandardCharsets.US_ASCII))
            fun le32(v: Int) {
                out.write(v and 0xFF)
                out.write(v shr 8 and 0xFF)
                out.write(v shr 16 and 0xFF)
                out.write(v shr 24 and 0xFF)
            }
            fun le16(v: Int) {
                out.write(v and 0xFF)
                out.write(v shr 8 and 0xFF)
            }
            str("RIFF")
            le32(36 + dataSize)
            str("WAVE")
            str("fmt ")
            le32(16)
            le16(1)
            le16(1)
            le32(SAMPLE_RATE)
            le32(SAMPLE_RATE * 2)
            le16(2)
            le16(16)
            str("data")
            le32(dataSize)
            return out.toByteArray()
        }
    }

    // Blocking network call, call off the main thread. Null on any failure,
    // so the caller can fall back to the system recognizer silently.
    fun transcribe(wav: File): String? {
        val boundary = "----guardpet" + System.currentTimeMillis()
        val body = ByteArrayOutputStream()
        fun ascii(s: String) = body.write(s.toByteArray(StandardCharsets.US_ASCII))
        ascii("--$boundary\r\n")
        ascii("Content-Disposition: form-data; name=\"request\"\r\n")
        ascii("Content-Type: application/json\r\n\r\n")
        ascii(
            JSONObject()
                .put("model", "muse-voice-transcribe-1.0")
                .put("mode", "PUSH_TO_TALK")
                .put("audioEncoding", "WAV")
                .toString()
        )
        ascii("\r\n")
        ascii("--$boundary\r\n")
        ascii("Content-Disposition: form-data; name=\"audio\"; filename=\"voice.wav\"\r\n")
        ascii("Content-Type: audio/wav\r\n\r\n")
        body.write(wav.readBytes())
        ascii("\r\n")
        ascii("--$boundary--\r\n")
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
            if (code !in 200..299) return null
            return JSONObject(text).optString("transcript", "").trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            return null
        } finally {
            conn.disconnect()
        }
    }
}
