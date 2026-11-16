package com.pes2013.imgpatcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater
import java.util.zip.Inflater

data class PesSubFile(
    val index: Int,
    var rawData: ByteArray,
    val isCompressed: Boolean,
    val headerMagic: ByteArray
) {
    fun getDisplayType(): String {
        if (rawData.size >= 4) {
            val sig = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).int
            if (sig == 0x20534444) return "Tekstur DDS (PES Graphic)"
            if (sig == 0x474E5089) return "Gambar PNG"
        }
        return "Data Biner / Model 3D (${rawData.size} bytes)"
    }

    fun toBitmap(): Bitmap? {
        if (rawData.size < 4) return null
        val sig = ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).int
        return when (sig) {
            0x20534444 -> PesTextureCodec.decodeDdsToBitmap(rawData)
            0x474E5089 -> BitmapFactory.decodeByteArray(rawData, 0, rawData.size)
            else -> null
        }
    }

    fun replaceWithBitmap(newBitmap: Bitmap) {
        val sig = if (rawData.size >= 4) ByteBuffer.wrap(rawData).order(ByteOrder.LITTLE_ENDIAN).int else 0
        rawData = if (sig == 0x474E5089) {
            val out = ByteArrayOutputStream()
            newBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } else {
            PesTextureCodec.encodeBitmapToDdsDxt5(newBitmap)
        }
    }
}

object PesBinContainer {

    fun unpackBin(binBytes: ByteArray): MutableList<PesSubFile> {
        val result = mutableListOf<PesSubFile>()
        if (binBytes.size < 16) {
            result.add(PesSubFile(0, binBytes, false, ByteArray(0)))
            return result
        }

        val buf = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)
        val b0 = binBytes[0].toInt() and 0xFF
        val b1 = binBytes[1].toInt() and 0xFF

        if (b0 == 0x00 && b1 == 0x01 && binBytes.size > 16) {
            val magic = binBytes.copyOfRange(0, 8)
            val unzlib = inflateRaw(binBytes, 16, binBytes.size - 16)
            result.add(PesSubFile(0, unzlib, true, magic))
            return result
        }

        if (b0 == 0x00 && b1 == 0x06) {
            val count = buf.getInt(4)
            if (count in 1..256 && 8 + count * 4 <= binBytes.size) {
                val offsets = IntArray(count) { buf.getInt(8 + it * 4) }
                for (i in 0 until count) {
                    val start = offsets[i]
                    val end = if (i + 1 < count) offsets[i + 1] else binBytes.size
                    if (start in 0 until end && end <= binBytes.size) {
                        val slice = binBytes.copyOfRange(start, end)
                        if (slice.size > 16 && slice[0] == 0x00.toByte() && slice[1] == 0x01.toByte()) {
                            val unzlib = inflateRaw(slice, 16, slice.size - 16)
                            result.add(PesSubFile(i, unzlib, true, slice.copyOfRange(0, 8)))
                        } else {
                            result.add(PesSubFile(i, slice, false, ByteArray(0)))
                        }
                    }
                }
                if (result.isNotEmpty()) return result
            }
        }

        result.add(PesSubFile(0, binBytes, false, ByteArray(0)))
        return result
    }

    fun packBin(subFiles: List<PesSubFile>, originalBinBytes: ByteArray): ByteArray {
        if (subFiles.size == 1) {
            val sub = subFiles[0]
            return if (sub.isCompressed) compressPesZlib(sub.rawData, sub.headerMagic) else sub.rawData
        }

        val encodedChunks = subFiles.map { sub ->
            if (sub.isCompressed) compressPesZlib(sub.rawData, sub.headerMagic) else sub.rawData
        }

        val headerSize = align16(8 + encodedChunks.size * 4)
        var currentOffset = headerSize
        val offsets = IntArray(encodedChunks.size)
        for (i in encodedChunks.indices) {
            offsets[i] = currentOffset
            currentOffset += align16(encodedChunks[i].size)
        }

        val out = ByteBuffer.allocate(currentOffset).order(ByteOrder.LITTLE_ENDIAN)
        out.put(originalBinBytes.copyOfRange(0, 4))
        out.putInt(encodedChunks.size)
        for (off in offsets) out.putInt(off)

        for (i in encodedChunks.indices) {
            out.position(offsets[i])
            out.put(encodedChunks[i])
        }
        return out.array()
    }

    private fun align16(v: Int): Int = if (v % 16 == 0) v else v + (16 - (v % 16))

    private fun inflateRaw(data: ByteArray, offset: Int, length: Int): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data, offset, length)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val count = inflater.inflate(buf)
            if (count == 0 && inflater.needsInput()) break
            out.write(buf, 0, count)
        }
        inflater.end()
        return out.toByteArray()
    }

    private fun compressPesZlib(raw: ByteArray, magic: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(raw)
        deflater.finish()
        val compOut = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!deflater.finished()) {
            val count = deflater.deflate(buf)
            compOut.write(buf, 0, count)
        }
        deflater.end()
        val compBytes = compOut.toByteArray()

        val header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        if (magic.size == 8) header.put(magic) else header.put(byteArrayOf(0, 1, 1, 0, 0x57, 0x45, 0x53, 0x59))
        header.putInt(compBytes.size)
        header.putInt(raw.size)

        val res = ByteArrayOutputStream()
        res.write(header.array())
        res.write(compBytes)
        return res.toByteArray()
    }
}
