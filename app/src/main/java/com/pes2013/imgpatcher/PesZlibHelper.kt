package com.pes2013.imgpatcher

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import java.util.zip.DeflaterOutputStream

object PesZlibHelper {
    fun isZlibCompressed(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val b0 = data[0].toInt() and 0xFF
        val b1 = data[1].toInt() and 0xFF
        return b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)
    }

    fun decompress(compressedData: ByteArray): ByteArray {
        return InflaterInputStream(ByteArrayInputStream(compressedData)).use { it.readBytes() }
    }

    fun compress(rawData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out).use { it.write(rawData) }
        return out.toByteArray()
    }
}
