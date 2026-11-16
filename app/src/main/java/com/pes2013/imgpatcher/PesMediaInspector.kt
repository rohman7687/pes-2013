package com.pes2013.imgpatcher

import android.content.Context
import java.io.File

enum class PesMediaType {
    IMAGE, AUDIO_ADX, AUDIO_OGG_WAV, VIDEO_SFD_MPEG, VIDEO_USM_CRID, TEXT_DATABASE, BINARY_HEX
}

object PesMediaInspector {

    fun detectMediaType(headerOrData: ByteArray, hasImage: Boolean, totalSize: Long = headerOrData.size.toLong()): PesMediaType {
        if (hasImage) return PesMediaType.IMAGE
        if (headerOrData.size < 16) return PesMediaType.BINARY_HEX

        val head4 = String(headerOrData, 0, 4, Charsets.US_ASCII)
        if (head4 == "CRID" || containsAsciiTag(headerOrData, "CRID", 4096) || containsAsciiTag(headerOrData, "@SFV", 8192)) {
            return PesMediaType.VIDEO_USM_CRID
        }

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

        if ((headerOrData[0].toInt() and 0xFF) == 0x80 && (headerOrData[1].toInt() and 0xFF) == 0x00) {
            val copyrightOff = ((headerOrData[2].toInt() and 0xFF) shl 8) or (headerOrData[3].toInt() and 0xFF)
            if (copyrightOff in 4 until headerOrData.size - 6) {
                val tag = String(headerOrData, copyrightOff - 2, 6, Charsets.US_ASCII)
                if (tag.contains("CRI")) return PesMediaType.AUDIO_ADX
            }
        }

        if (head4 == "OggS" || head4 == "RIFF") return PesMediaType.AUDIO_OGG_WAV

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

    fun openUniversalViewerFromArchive(
        context: Context,
        imgFile: File,
        entry: Any,
        stagedFile: File?,
        inMemoryBytes: ByteArray?,
        type: PesMediaType
    ) {
        // Universal viewer entry point
    }
}
