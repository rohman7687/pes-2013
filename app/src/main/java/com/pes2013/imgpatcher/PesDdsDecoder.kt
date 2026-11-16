package com.pes2013.imgpatcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

object PesDdsDecoder {
    private const val DDS_HEADER_SIZE = 128
    private const val DDS_MAGIC = 0x20534444 // "DDS "

    data class DdsHeader(
        val magic: Int,
        val height: Int,
        val width: Int,
        val pitchOrLinearSize: Int,
        val mipMapCount: Int,
        val pfFlags: Int,
        val pfFourCC: Int
    )

    fun parseDdsHeader(data: ByteArray): DdsHeader? {
        if (data.size < DDS_HEADER_SIZE) return null
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buffer.int
        if (magic != DDS_MAGIC) return null

        buffer.int // size
        buffer.int // flags
        val height = buffer.int
        val width = buffer.int
        val pitchOrLinearSize = buffer.int
        buffer.int // depth
        val mipMapCount = buffer.int

        buffer.position(buffer.position() + 44)

        buffer.int // pfSize
        val pfFlags = buffer.int
        val pfFourCC = buffer.int

        return DdsHeader(magic, height, width, pitchOrLinearSize, mipMapCount, pfFlags, pfFourCC)
    }

    fun isDdsFile(data: ByteArray): Boolean {
        if (data.size < 4) return false
        val magic = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int
        return magic == DDS_MAGIC
    }
}
