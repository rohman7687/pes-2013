package com.pes2013.imgpatcher

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FaceModelEntry(
    var modelId: Int,
    var vertexCount: Int,
    var faceCount: Int,
    var hasTextureMap: Boolean
)

object PesFaceModelInspector {
    fun inspectFaceBin(binBytes: ByteArray): FaceModelEntry? {
        if (binBytes.size < 64) return null
        val buffer = ByteBuffer.wrap(binBytes).order(ByteOrder.LITTLE_ENDIAN)

        return try {
            val modelId = buffer.int
            val vCount = buffer.int
            val fCount = buffer.int
            val textureFlag = buffer.int

            FaceModelEntry(
                modelId = modelId,
                vertexCount = vCount,
                faceCount = fCount,
                hasTextureMap = textureFlag > 0
            )
        } catch (e: Exception) {
            null
        }
    }
}
