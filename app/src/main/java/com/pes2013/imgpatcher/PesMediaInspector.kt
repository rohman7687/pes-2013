package com.pes2013.imgpatcher

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

enum class PesMediaType {
    IMAGE,
    AUDIO_ADX,
    AUDIO_OGG_WAV,
    VIDEO_SFD_MPEG,
    VIDEO_USM_CRID,
    TEXT_DATABASE,
    BINARY_HEX
}

object PesMediaInspector {

    /**
     * Mendeteksi tipe media langsung dari header awal file (cukup 64 KB pertama)
     * sehingga file video puluhan/ratusan MB di dt03.img langsung terdeteksi instan tanpa OutOfMemory!
     */
    fun detectMediaType(headerOrData: ByteArray, hasImage: Boolean, totalSize: Long = headerOrData.size.toLong()): PesMediaType {
        if (hasImage) return PesMediaType.IMAGE
        if (headerOrData.size < 16) return PesMediaType.BINARY_HEX

        // 1. Cek Video CRI USM ("CRID" di awal atau di dalam header)
        val head4 = String(headerOrData, 0, 4, Charsets.US_ASCII)
        if (head4 == "CRID" || containsAsciiTag(headerOrData, "CRID", 4096) || containsAsciiTag(headerOrData, "@SFV", 8192)) {
            return PesMediaType.VIDEO_USM_CRID
        }

        // 2. Cek Video CRI Sofdec SFD / MPEG-1/2 Program Stream (00 00 01 BA) atau Video Stream (00 00 01 B3)
        val scanLimit = minOf(headerOrData.size - 4, 8192)
        for (i in 0 until scanLimit) {
            val b0 = headerOrData[i].toInt() and 0xFF
            val b1 = headerOrData[i + 1].toInt() and 0xFF
            val b2 = headerOrData[i + 2].toInt() and 0xFF
            val b3 = headerOrData[i + 3].toInt() and 0xFF
            if (b0 == 0x00 && b1 == 0x00 && b2 == 0x01 && (b3 == 0xBA || b3 == 0xB3)) {
                return PesMediaType.VIDEO_SFD_MPEG
            }
        }
        if (containsAsciiTag(headerOrData, "Sofdec", 4096)) {
            return PesMediaType.VIDEO_SFD_MPEG
        }

        // 3. Cek Audio CRI ADX (0x80 0x00 di byte 0..1 dan signature "(c)CRI")
        if ((headerOrData[0].toInt() and 0xFF) == 0x80 && (headerOrData[1].toInt() and 0xFF) == 0x00) {
            val copyrightOff = ((headerOrData[2].toInt() and 0xFF) shl 8) or (headerOrData[3].toInt() and 0xFF)
            if (copyrightOff in 4 until headerOrData.size - 6) {
                val tag = String(headerOrData, copyrightOff - 2, 6, Charsets.US_ASCII)
                if (tag.contains("CRI")) return PesMediaType.AUDIO_ADX
            }
        }

        // 4. Cek OggS (4F 67 67 53) atau RIFF WAV (52 49 46 46)
        if (head4 == "OggS" || head4 == "RIFF") return PesMediaType.AUDIO_OGG_WAV

        // 5. Cek apakah dominan berisi teks (Database pemain dt04 / teks bahasa dt00/dt05)
        var printable = 0
        val checkLen = minOf(headerOrData.size, 4096)
        for (i in 0 until checkLen) {
            val c = headerOrData[i].toInt() and 0xFF
            if (c in 32..126 || c == 10 || c == 13) printable++
        }
        if (printable > checkLen * 0.35) return PesMediaType.TEXT_DATABASE

        return PesMediaType.BINARY_HEX
    }

    private fun containsAsciiTag(data: ByteArray, tag: String, maxSearch: Int): Boolean {
        val limit = minOf(data.size, maxSearch)
        if (limit < tag.length) return false
        val slice = String(data, 0, limit, Charsets.ISO_8859_1)
        return slice.contains(tag)
    }

    fun getMediaLabel(type: PesMediaType, totalSize: Long): String {
        val kb = totalSize / 1024
        val mb = String.format("%.1f MB", totalSize / (1024.0 * 1024.0))
        val sizeStr = if (totalSize >= 1024 * 1024) "$mb ($kb KB)" else "$kb KB"
        return when (type) {
            PesMediaType.IMAGE -> "Tekstur Gambar ($sizeStr)"
            PesMediaType.AUDIO_ADX -> "Audio Musik / SFX CRI ADX ($sizeStr)"
            PesMediaType.AUDIO_OGG_WAV -> "Audio OGG / WAV ($sizeStr)"
            PesMediaType.VIDEO_SFD_MPEG -> "Video Cutscene Sofdec SFD / MPEG ($sizeStr)"
            PesMediaType.VIDEO_USM_CRID -> "Video Cutscene CRI USM ($sizeStr)"
            PesMediaType.TEXT_DATABASE -> "Data Teks / Pemain / Tim ($sizeStr)"
            PesMediaType.BINARY_HEX -> "Data Biner / Animasi / Model 3D ($sizeStr)"
        }
    }

    /**
     * Membuka & memutar isi slot langsung dari arsip .img (streaming untuk file besar)
     */
    fun openUniversalViewerFromArchive(
        context: Context,
        imgFile: File,
        entry: AfsEntryInfo,
        stagedFile: File?,
        inMemoryBytes: ByteArray?,
        type: PesMediaType
    ) {
        when (type) {
            PesMediaType.AUDIO_ADX -> {
                val bytes = inMemoryBytes ?: readBytesSafe(imgFile, entry, stagedFile)
                showAdxAudioPlayer(context, entry.name, bytes)
            }
            PesMediaType.AUDIO_OGG_WAV -> {
                val bytes = inMemoryBytes ?: readBytesSafe(imgFile, entry, stagedFile)
                showStandardAudioPlayer(context, entry.name, bytes)
            }
            PesMediaType.VIDEO_SFD_MPEG, PesMediaType.VIDEO_USM_CRID -> {
                extractAndPlayVideoStream(context, imgFile, entry, stagedFile, type)
            }
            PesMediaType.TEXT_DATABASE, PesMediaType.BINARY_HEX -> {
                val bytes = inMemoryBytes ?: readBytesSafe(imgFile, entry, stagedFile, maxBytes = 256 * 1024)
                showTextAndHexInspector(context, entry.name, bytes, entry.size)
            }
            else -> {}
        }
    }

    private fun readBytesSafe(imgFile: File, entry: AfsEntryInfo, stagedFile: File?, maxBytes: Int = Int.MAX_VALUE): ByteArray {
        if (stagedFile != null && stagedFile.exists()) {
            val len = minOf(stagedFile.length(), maxBytes.toLong()).toInt()
            val out = ByteArray(len)
            stagedFile.inputStream().use { it.read(out) }
            return out
        }
        if (entry.size <= 0L) return ByteArray(0)
        val len = minOf(entry.size, maxBytes.toLong()).toInt()
        val out = ByteArray(len)
        RandomAccessFile(imgFile, "r").use { raf ->
            raf.seek(entry.offset)
            raf.readFully(out)
        }
        return out
    }

    private fun extractAndPlayVideoStream(
        context: Context,
        imgFile: File,
        entry: AfsEntryInfo,
        stagedFile: File?,
        type: PesMediaType
    ) {
        val progressDialog = AlertDialog.Builder(context)
            .setTitle("Menyiapkan Video Cutscene")
            .setMessage("Mengekstrak aliran video ${entry.name} (${entry.size / (1024 * 1024)} MB)...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        thread {
            try {
                val exportDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "PES2013_Export"
                ).apply { mkdirs() }

                val ext = if (type == PesMediaType.VIDEO_USM_CRID) "usm" else "sfd"
                val rawOutFile = File(exportDir, "unnamed_${entry.index}.$ext")

                // Streaming copy agar file ratusan MB tidak membebani RAM HP
                FileOutputStream(rawOutFile).use { fos ->
                    val buffer = ByteArray(1024 * 1024)
                    if (stagedFile != null && stagedFile.exists()) {
                        stagedFile.inputStream().use { fis ->
                            var r: Int
                            while (fis.read(buffer).also { r = it } != -1) fos.write(buffer, 0, r)
                        }
                    } else {
                        RandomAccessFile(imgFile, "r").use { raf ->
                            raf.seek(entry.offset)
                            var remaining = entry.size
                            while (remaining > 0L) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val r = raf.read(buffer, 0, toRead)
                                if (r <= 0) break
                                fos.write(buffer, 0, r)
                                remaining -= r
                            }
                        }
                    }
                }

                // Jika kontainer USM (CRID), ekstrak juga aliran video bersih (@SFV) ke file .m2v agar langsung bisa diputar VLC
                var playableFile = rawOutFile
                if (type == PesMediaType.VIDEO_USM_CRID) {
                    val demuxedM2v = File(exportDir, "unnamed_${entry.index}_video.m2v")
                    if (demuxUsmVideoTrack(rawOutFile, demuxedM2v) && demuxedM2v.length() > 1024) {
                        playableFile = demuxedM2v
                    }
                }

                progressDialog.dismiss()
                android.os.Handler(context.mainLooper).post {
                    showVideoPlayerChoiceDialog(context, entry.name, playableFile, rawOutFile)
                }
            } catch (e: Exception) {
                progressDialog.dismiss()
                android.os.Handler(context.mainLooper).post {
                    Toast.makeText(context, "Gagal mengekstrak video: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Memisahkan paket video @SFV dari kontainer CRI USM (CRID) menjadi aliran video murni
     */
    private fun demuxUsmVideoTrack(usmFile: File, outVideoFile: File): Boolean {
        return try {
            RandomAccessFile(usmFile, "r").use { raf ->
                FileOutputStream(outVideoFile).use { fos ->
                    val header = ByteArray(32)
                    val fileLen = raf.length()
                    var pos = 0L
                    while (pos + 32 <= fileLen) {
                        raf.seek(pos)
                        raf.readFully(header)
                        val chunkTag = String(header, 0, 4, Charsets.US_ASCII)
                        val chunkSize = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        if (chunkSize < 24 || pos + 8 + chunkSize > fileLen) break

                        if (chunkTag == "@SFV") {
                            val dataOffset = ((header[8].toInt() and 0xFF) shl 8) or (header[9].toInt() and 0xFF)
                            val paddingSize = ((header[10].toInt() and 0xFF) shl 8) or (header[11].toInt() and 0xFF)
                            val dataType = header[12].toInt() and 0xFF
                            // dataType 0 = payload aliran video utama
                            if (dataType == 0) {
                                val payloadStart = pos + 8 + dataOffset
                                val payloadLen = (chunkSize - dataOffset - paddingSize).toInt()
                                if (payloadLen > 0 && payloadStart + payloadLen <= fileLen) {
                                    raf.seek(payloadStart)
                                    val buf = ByteArray(payloadLen)
                                    raf.readFully(buf)
                                    fos.write(buf)
                                }
                            }
                        }
                        pos += 8 + chunkSize
                    }
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun showVideoPlayerChoiceDialog(
        context: Context,
        entryName: String,
        playableFile: File,
        rawExportedFile: File
    ) {
        val options = arrayOf(
            "Putar di Pemutar Video HP (VLC / MX Player / Video)",
            "Putar di Pemutar Bawaan Aplikasi",
            "Selesai (File sudah tersimpan di Download/PES2013_Export)"
        )
        AlertDialog.Builder(context)
            .setTitle("Video Cutscene Siap: $entryName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> launchExternalVideoIntent(context, playableFile)
                    1 -> showInternalVideoDialog(context, entryName, playableFile)
                }
            }
            .show()
    }

    private fun launchExternalVideoIntent(context: Context, videoFile: File) {
        try {
            val builder = StrictMode.VmPolicy.Builder()
            StrictMode.setVmPolicy(builder.build())

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(videoFile), "video/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Pilih Pemutar Video (Disarankan VLC)"))
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "Tidak ada pemutar video eksternal ditemukan. File tersimpan di: ${videoFile.absolutePath}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showInternalVideoDialog(context: Context, entryName: String, videoFile: File) {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(16, 16, 16, 16)
            gravity = Gravity.CENTER
        }
        val tvTitle = TextView(context).apply {
            text = "Video Cutscene: $entryName"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 0, 0, 12)
        }
        val videoView = VideoView(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 650)
            setVideoURI(Uri.fromFile(videoFile))
        }
        val mediaController = MediaController(context)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)

        val btnOpenVlc = Button(context).apply { text = "BUKA DI VLC / PEMUTAR EKSTERNAL" }
        val btnClose = Button(context).apply { text = "TUTUP" }

        container.addView(tvTitle)
        container.addView(videoView)
        container.addView(btnOpenVlc)
        container.addView(btnClose)

        val dialog = AlertDialog.Builder(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(container)
            .create()

        videoView.setOnErrorListener { _, _, _ ->
            Toast.makeText(context, "Codec MPEG/SFD tidak didukung pemutar bawaan HP. Ketuk tombol 'BUKA DI VLC'.", Toast.LENGTH_LONG).show()
            true
        }
        btnOpenVlc.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
            launchExternalVideoIntent(context, videoFile)
        }
        btnClose.setOnClickListener {
            videoView.stopPlayback()
            dialog.dismiss()
        }
        dialog.show()
        videoView.start()
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

    private fun showTextAndHexInspector(context: Context, entryName: String, data: ByteArray, totalSize: Long) {
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
            strings.take(500).forEachIndexed { idx, str ->
                sb.append("${idx + 1}. $str\n")
            }
            if (strings.size > 500) sb.append("\n... (${strings.size - 500} baris teks lainnya)\n")
        }

        sb.append("\n=== HEX PREVIEW (512 BYTE PERTAMA DARI $totalSize BYTES) ===\n\n")
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
