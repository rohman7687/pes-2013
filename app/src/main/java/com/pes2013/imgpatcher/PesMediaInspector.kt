package com.pes2013.imgpatcher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

enum class PesMediaType {
    IMAGE,
    AUDIO_ADX,
    AUDIO_OGG_WAV,
    VIDEO_SFD_MPEG,
    TEXT_DATABASE,
    BINARY_HEX
}

object PesMediaInspector {

    fun detectMediaType(data: ByteArray, hasImage: Boolean): PesMediaType {
        if (hasImage) return PesMediaType.IMAGE
        if (data.size < 16) return PesMediaType.BINARY_HEX

        // Cek Audio CRI ADX (0x80 0x00 di byte 0..1 dan signature "(c)CRI" di offset copyright)
        if ((data[0].toInt() and 0xFF) == 0x80 && (data[1].toInt() and 0xFF) == 0x00) {
            val copyrightOff = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
            if (copyrightOff in 4 until data.size - 6) {
                val tag = String(data, copyrightOff - 2, 6, Charsets.US_ASCII)
                if (tag.contains("CRI")) return PesMediaType.AUDIO_ADX
            }
        }

        // Cek OggS (4F 67 67 53) atau RIFF WAV (52 49 46 46)
        val head4 = String(data, 0, 4, Charsets.US_ASCII)
        if (head4 == "OggS" || head4 == "RIFF") return PesMediaType.AUDIO_OGG_WAV

        // Cek Video CRI Sofdec SFD / MPEG-1/2 (00 00 01 BA) atau USM ("CRID")
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        val b2 = data[2].toInt() and 0xFF
        val b3 = data[3].toInt() and 0xFF
        if ((b0 == 0x00 && b1 == 0x00 && b2 == 0x01 && (b3 == 0xBA || b3 == 0xB3)) || head4 == "CRID") {
            return PesMediaType.VIDEO_SFD_MPEG
        }

        // Cek apakah dominan berisi teks (Database pemain dt04 / teks bahasa dt00/dt05)
        var printable = 0
        val checkLen = minOf(data.size, 4096)
        for (i in 0 until checkLen) {
            val c = data[i].toInt() and 0xFF
            if (c in 32..126 || c == 10 || c == 13) printable++
        }
        if (printable > checkLen * 0.35) return PesMediaType.TEXT_DATABASE

        return PesMediaType.BINARY_HEX
    }

    fun getMediaLabel(type: PesMediaType, sizeBytes: Int): String {
        return when (type) {
            PesMediaType.IMAGE -> "Tekstur Gambar"
            PesMediaType.AUDIO_ADX -> "Audio Musik / SFX CRI ADX ($sizeBytes bytes)"
            PesMediaType.AUDIO_OGG_WAV -> "Audio OGG / WAV ($sizeBytes bytes)"
            PesMediaType.VIDEO_SFD_MPEG -> "Video Cutscene SFD / MPEG ($sizeBytes bytes)"
            PesMediaType.TEXT_DATABASE -> "Data Teks / Pemain / Tim ($sizeBytes bytes)"
            PesMediaType.BINARY_HEX -> "Data Biner / Model 3D ($sizeBytes bytes)"
        }
    }

    fun openUniversalViewer(context: Context, entryName: String, data: ByteArray, type: PesMediaType) {
        when (type) {
            PesMediaType.AUDIO_ADX -> showAdxAudioPlayer(context, entryName, data)
            PesMediaType.AUDIO_OGG_WAV -> showStandardAudioPlayer(context, entryName, data)
            PesMediaType.VIDEO_SFD_MPEG -> showVideoPlayer(context, entryName, data)
            PesMediaType.TEXT_DATABASE, PesMediaType.BINARY_HEX -> showTextAndHexInspector(context, entryName, data)
            else -> {}
        }
    }

    private fun showAdxAudioPlayer(context: Context, entryName: String, adxBytes: ByteArray) {
        var isPlaying = true
        var audioTrack: AudioTrack? = null

        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 32, 40, 24)
            gravity = Gravity.CENTER
        }
        val tvTitle = TextView(context).apply {
            text = "Memutar Audio CRI ADX: $entryName"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        val tvInfo = TextView(context).apply {
            text = "Mendekode aliran suara ADX..."
            textSize = 12f
            setPadding(0, 12, 0, 20)
            gravity = Gravity.CENTER
        }
        layout.addView(tvTitle)
        layout.addView(tvInfo)

        val dialog = AlertDialog.Builder(context)
            .setView(layout)
            .setNegativeButton("Tutup & Hentikan") { _, _ ->
                isPlaying = false
                try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
            }
            .setOnDismissListener {
                isPlaying = false
                try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
            }
            .create()
        dialog.show()

        thread {
            try {
                val decoded = decodeAdxToPcm16(adxBytes)
                if (decoded == null) {
                    tvInfo.post { tvInfo.text = "Format header ADX tidak standar atau terenkripsi." }
                    return@thread
                }
                val (pcmBytes, sampleRate, channels) = decoded
                val channelConfig = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
                val minBuf = AudioTrack.getMinBufferSize(sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT)

                audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(maxOf(minBuf, 8192))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                val durationSec = pcmBytes.size / (sampleRate * channels * 2)
                tvInfo.post {
                    tvInfo.text = "Sample Rate: $sampleRate Hz | Channel: $channels | Durasi: ${durationSec}d"
                }

                audioTrack?.play()
                var offset = 0
                val chunk = 4096
                while (isPlaying && offset < pcmBytes.size) {
                    val len = minOf(chunk, pcmBytes.size - offset)
                    val written = audioTrack?.write(pcmBytes, offset, len) ?: -1
                    if (written <= 0) break
                    offset += written
                }
                if (isPlaying) {
                    tvInfo.post { tvInfo.text = "Pemutaran selesai ($sampleRate Hz, ${durationSec}d)" }
                }
            } catch (e: Exception) {
                tvInfo.post { tvInfo.text = "Gagal memutar audio: ${e.message}" }
            }
        }
    }

    private fun decodeAdxToPcm16(data: ByteArray): Triple<ByteArray, Int, Int>? {
        if (data.size < 32) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val copyrightOff = (buf.getShort(2).toInt() and 0xFFFF) + 4
        val encodingType = data[4].toInt() and 0xFF
        val blockSize = data[5].toInt() and 0xFF
        val bitDepth = data[6].toInt() and 0xFF
        val channels = data[7].toInt() and 0xFF
        val sampleRate = buf.getInt(8)
        val totalSamples = buf.getInt(12)
        val highpassFreq = buf.getShort(16).toInt() and 0xFFFF

        if (encodingType != 3 || bitDepth != 4 || channels !in 1..2 || sampleRate <= 0 || blockSize <= 2) return null

        val sq2 = Math.sqrt(2.0)
        val z = Math.cos(2.0 * Math.PI * highpassFreq / sampleRate)
        val a = sq2 - z
        val b = sq2 - 1.0
        val c = (a - Math.sqrt((a + b) * (a - b))) / b
        val coef1 = (c * 2.0 * 4096.0).toInt()
        val coef2 = (-(c * c) * 4096.0).toInt()

        val samplesPerBlock = (blockSize - 2) * 2
        val out = ByteArrayOutputStream()
        val s1 = IntArray(channels)
        val s2 = IntArray(channels)
        var pos = copyrightOff
        var decodedSamples = 0

        while (pos + blockSize * channels <= data.size && decodedSamples < totalSamples) {
            val chSamples = Array(channels) { ShortArray(samplesPerBlock) }
            for (ch in 0 until channels) {
                val scale = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                pos += 2
                var sIdx = 0
                for (bIdx in 0 until blockSize - 2) {
                    val byteVal = data[pos++].toInt() and 0xFF
                    for (nibbleIdx in 0..1) {
                        var nibble = if (nibbleIdx == 0) (byteVal shr 4) else (byteVal and 0x0F)
                        if (nibble >= 8) nibble -= 16
                        var sample = ((nibble * scale * 4096) + (coef1 * s1[ch]) + (coef2 * s2[ch])) shr 12
                        sample = sample.coerceIn(-32768, 32767)
                        s2[ch] = s1[ch]
                        s1[ch] = sample
                        if (sIdx < samplesPerBlock) chSamples[ch][sIdx++] = sample.toShort()
                    }
                }
            }
            val count = minOf(samplesPerBlock, totalSamples - decodedSamples)
            for (i in 0 until count) {
                for (ch in 0 until channels) {
                    val v = chSamples[ch][i].toInt()
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
            }
            decodedSamples += count
        }
        return Triple(out.toByteArray(), sampleRate, channels)
    }

    private fun showStandardAudioPlayer(context: Context, entryName: String, audioBytes: ByteArray) {
        val ext = if (audioBytes.size >= 4 && String(audioBytes, 0, 4, Charsets.US_ASCII) == "RIFF") "wav" else "ogg"
        val tempFile = File(context.cacheDir, "preview_audio.$ext").apply { writeBytes(audioBytes) }
        val mediaPlayer = android.media.MediaPlayer()
        try {
            mediaPlayer.setDataSource(context, Uri.fromFile(tempFile))
            mediaPlayer.prepare()
            mediaPlayer.start()
        } catch (e: Exception) {
            Toast.makeText(context, "Gagal memutar audio: ${e.message}", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(context)
            .setTitle("Pemutar Audio: $entryName")
            .setMessage("Memutar file audio ($ext) - Durasi: ${mediaPlayer.duration / 1000} detik")
            .setNegativeButton("Hentikan & Tutup") { _, _ ->
                try { mediaPlayer.stop(); mediaPlayer.release() } catch (_: Exception) {}
            }
            .setOnDismissListener {
                try { mediaPlayer.stop(); mediaPlayer.release() } catch (_: Exception) {}
            }
            .show()
    }

    private fun showVideoPlayer(context: Context, entryName: String, videoBytes: ByteArray) {
        val tempFile = File(context.cacheDir, "preview_video.mpg").apply { writeBytes(videoBytes) }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER
        }
        val tvTitle = TextView(context).apply {
            text = "Video Cutscene: $entryName (${videoBytes.size / 1024} KB)"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        val videoView = VideoView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650)
            setVideoURI(Uri.fromFile(tempFile))
        }
        val mediaController = MediaController(context)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        val tvNote = TextView(context).apply {
            text = "Catatan: Jika codec MPEG-1 SFD PS2 tidak didukung hardware HP, gunakan tombol 'Ekspor .BIN' lalu ubah ekstensi menjadi .sfd / .mpg untuk diputar di VLC."
            setTextColor(Color.LTGRAY)
            textSize = 11f
            setPadding(0, 12, 0, 12)
        }
        val btnClose = Button(context).apply { text = "TUTUP PEMUTAR VIDEO" }

        container.addView(tvTitle)
        container.addView(videoView)
        container.addView(tvNote)
        container.addView(btnClose)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(container)
            .create()

        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, "Codec video SFD/USM tidak didukung pemutar bawaan Android. Ekspor file untuk diputar di VLC.", Toast.LENGTH_LONG).show()
            true
        }
        btnClose.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }
        dialog.show()
        videoView.start()
    }

    private fun showTextAndHexInspector(context: Context, entryName: String, data: ByteArray) {
        val sb = StringBuilder()
        sb.append("=== TEKS / NAMA PEMAIN / TIM TERDETEKSI ===\n\n")

        val strings = mutableListOf<String>()
        val cur = StringBuilder()
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) {
                cur.append(c.toChar())
            } else {
                if (cur.length >= 4) strings.add(cur.toString())
                cur.clear()
            }
        }
        if (cur.length >= 4) strings.add(cur.toString())

        if (strings.isEmpty()) {
            sb.append("(Tidak ada teks ASCII terbaca dalam blok ini)\n")
        } else {
            strings.take(400).forEachIndexed { idx, str ->
                sb.append("${idx + 1}. $str\n")
            }
            if (strings.size > 400) sb.append("\n... (${strings.size - 400} baris teks lainnya)\n")
        }

        sb.append("\n=== HEX PREVIEW (512 BYTE PERTAMA) ===\n\n")
        val hexLimit = minOf(data.size, 512)
        for (i in 0 until hexLimit step 16) {
            sb.append(String.format("%04X: ", i))
            for (j in 0 until 16) {
                if (i + j < hexLimit) sb.append(String.format("%02X ", data[i + j].toInt() and 0xFF))
                else sb.append("   ")
            }
            sb.append(" | ")
            for (j in 0 until 16) {
                if (i + j < hexLimit) {
                    val c = data[i + j].toInt() and 0xFF
                    sb.append(if (c in 32..126) c.toChar() else '.')
                }
            }
            sb.append("\n")
        }

        val scrollView = ScrollView(context).apply { setPadding(32, 24, 32, 24) }
        val tvContent = TextView(context).apply {
            text = sb.toString()
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(tvContent)

        AlertDialog.Builder(context)
            .setTitle("Inspektur Data: $entryName")
            .setView(scrollView)
            .setPositiveButton("Tutup", null)
            .show()
    }
}
