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
        val texOffset = PesTextureCodec.findTextureOffset(rawData)
        if (texOffset >= 0) {
            val sig = ByteBuffer.wrap(rawData, texOffset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (sig == 0x20534444) return "Tekstur DDS (PES Graphic) [offset $texOffset]"
            if (sig == 0x474E5089) return "Gambar PNG [offset $texOffset]"
        }
        return "Data Biner / Model 3D (${rawData.size} bytes)"
    }

    fun toBitmap(): Bitmap? {
        val texOffset = PesTextureCodec.findTextureOffset(rawData)
        if (texOffset < 0 || texOffset + 4 > rawData.size) return null
        val slice = if (texOffset == 0) rawData else rawData.copyOfRange(texOffset, rawData.size)
        val sig = ByteBuffer.wrap(slice).order(ByteOrder.LITTLE_ENDIAN).int
        return when (sig) {
            0x20534444 -> PesTextureCodec.decodeDdsToBitmap(slice)
            0x474E5089 -> BitmapFactory.decodeByteArray(slice, 0, slice.size)
            else -> null
        }
    }

    fun replaceWithBitmap(newBitmap: Bitmap) {
        val texOffset = PesTextureCodec.findTextureOffset(rawData)
        val startPos = if (texOffset >= 0) texOffset else 0
        val sig = if (startPos + 4 <= rawData.size) {
            ByteBuffer.wrap(rawData, startPos, 4).order(ByteOrder.LITTLE_ENDIAN).int
        } else 0

        val encodedTexture = if (sig == 0x474E5089) {
            val out = ByteArrayOutputStream()
            newBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        } else {
            PesTextureCodec.encodeBitmapToDdsDxt5(newBitmap)
        }

        rawData = if (startPos > 0) {
            val prefix = rawData.copyOfRange(0, startPos)
            prefix + encodedTexture
        } else {
            encodedTexture
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

        // 1. Cek jika seluruh file .bin adalah blok kompresi Zlib PES (0x00 0x01 0x01 / WESYS)
        if (isPesZlibHeader(binBytes)) {
            val magic = binBytes.copyOfRange(0, 8)
            val unzlib = inflateRaw(binBytes, 16, binBytes.size - 16)
            if (unzlib.isNotEmpty()) {
                // Jika hasil dekompresi ternyata kontainer multi-file, pecah lagi isinya
                val innerSubs = tryUnpackMultiContainer(unzlib)
                if (innerSubs != null) return innerSubs
                result.add(PesSubFile(0, unzlib, true, magic))
                return result
            }
        }

        // 2. Cek jika file .bin adalah kontainer multi sub-file (0x00 0x06 atau 0x00 0x00 0x00 0x03)
        val multiSubs = tryUnpackMultiContainer(binBytes)
        if (multiSubs != null) return multiSubs

        // 3. Fallback: data mentah tunggal
        result.add(PesSubFile(0, binBytes, false, ByteArray(0)))
        return result
    }

    private fun tryUnpackMultiContainer(data: ByteArray): MutableList<PesSubFile>? {
        if (data.size < 16) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        val magicInt = buf.getInt(0)

        // Mendukung header kontainer PES: 0x00 0x06 ... atau 0x00 0x00 0x00 0x03 / 0x00 0x00 0x00 0x02
        val isMultiHeader = (b0 == 0x00 && b1 == 0x06) || magicInt == 0x00000003 || magicInt == 0x00000002
        if (!isMultiHeader) return null

        val count = buf.getInt(4)
        if (count !in 1..512 || 8 + count * 4 > data.size) return null

        val offsets = IntArray(count) { buf.getInt(8 + it * 4) }
        if (offsets[0] < 8 + count * 4 || offsets[0] >= data.size) return null

        val list = mutableListOf<PesSubFile>()
        for (i in 0 until count) {
            val start = offsets[i]
            val end = if (i + 1 < count && offsets[i + 1] > start) offsets[i + 1] else data.size
            if (start in 0 until end && end <= data.size) {
                val slice = data.copyOfRange(start, end)
                if (isPesZlibHeader(slice)) {
                    val unzlib = inflateRaw(slice, 16, slice.size - 16)
                    if (unzlib.isNotEmpty()) {
                        list.add(PesSubFile(i, unzlib, true, slice.copyOfRange(0, 8)))
                    } else {
                        list.add(PesSubFile(i, slice, false, ByteArray(0)))
                    }
                } else {
                    list.add(PesSubFile(i, slice, false, ByteArray(0)))
                }
            }
        }
        return if (list.isNotEmpty()) list else null
    }

    private fun isPesZlibHeader(data: ByteArray): Boolean {
        if (data.size <= 16) return false
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        val b2 = data[2].toInt() and 0xFF
        // Header Zlib PES standar: 00 01 01 / 00 06 01 / 00 10 01 atau signature Zlib 78 9C / 78 DA di offset 16
        val validMagic = (b0 == 0x00 && (b1 == 0x01 || b1 == 0x06 || b1 == 0x10) && b2 == 0x01)
        val z0 = data[16].toInt() and 0xFF
        val validZlibStart = (z0 == 0x78)
        return validMagic || validZlibStart
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
        if (originalBinBytes.size >= 4) {
            out.put(originalBinBytes.copyOfRange(0, 4))
        } else {
            out.putInt(0x00000003)
        }
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
        return try {
            val inflater = Inflater()
            inflater.setInput(data, offset, length)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val count = inflater.inflate(buf)
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                }
                out.write(buf, 0, count)
            }
            inflater.end()
            out.toByteArray()
        } catch (e: Exception) {
            ByteArray(0)
        }
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
