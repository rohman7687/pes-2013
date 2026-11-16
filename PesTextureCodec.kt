package com.pes2013.imgpatcher

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import java.nio.ByteOrder

object PesTextureCodec {

    fun decodeDdsToBitmap(ddsBytes: ByteArray): Bitmap? {
        if (ddsBytes.size < 128) return null
        val buf = ByteBuffer.wrap(ddsBytes).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.int != 0x20534444) return null

        buf.position(12)
        val height = buf.int
        val width = buf.int
        buf.position(84)
        val fourCC = buf.int

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return null

        val pixels = IntArray(width * height)
        var offset = 128

        val blockW = (width + 3) / 4
        val blockH = (height + 3) / 4

        for (by in 0 until blockH) {
            for (bx in 0 until blockW) {
                if (offset + 8 > ddsBytes.size) break
                val alphaBlock = if (fourCC == 0x33545844 || fourCC == 0x35545844) {
                    val a = buf.getLong(offset)
                    offset += 8
                    a
                } else 0L

                if (offset + 8 > ddsBytes.size) break
                val c0 = buf.getShort(offset).toInt() and 0xFFFF
                val c1 = buf.getShort(offset + 2).toInt() and 0xFFFF
                val colorIndices = buf.getInt(offset + 4)
                offset += 8

                val colors = IntArray(4)
                colors[0] = rgb565To888(c0)
                colors[1] = rgb565To888(c1)
                if (c0 > c1 || fourCC != 0x31545844) {
                    colors[2] = interpolateColor(colors[0], colors[1], 2, 1, 3)
                    colors[3] = interpolateColor(colors[0], colors[1], 1, 2, 3)
                } else {
                    colors[2] = interpolateColor(colors[0], colors[1], 1, 1, 2)
                    colors[3] = Color.TRANSPARENT
                }

                for (py in 0 until 4) {
                    for (px in 0 until 4) {
                        val x = bx * 4 + px
                        val y = by * 4 + py
                        if (x >= width || y >= height) continue

                        val bitIdx = (py * 4 + px) * 2
                        val cIdx = (colorIndices shr bitIdx) and 0x03
                        var argb = colors[cIdx]

                        if (fourCC == 0x33545844) {
                            val a4 = ((alphaBlock shr ((py * 4 + px) * 4)) and 0x0FL).toInt()
                            val alpha = (a4 * 17) shl 24
                            argb = (argb and 0x00FFFFFF) or alpha
                        } else if (fourCC == 0x35545844) {
                            val alpha = decodeDxt5Alpha(alphaBlock, py * 4 + px) shl 24
                            argb = (argb and 0x00FFFFFF) or alpha
                        }
                        pixels[y * width + x] = argb
                    }
                }
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    fun encodeBitmapToDdsDxt5(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val blockW = (width + 3) / 4
        val blockH = (height + 3) / 4
        val totalSize = 128 + blockW * blockH * 16

        val out = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x20534444)
        out.putInt(124)
        out.putInt(0x00081007)
        out.putInt(height)
        out.putInt(width)
        out.putInt(blockW * blockH * 16)
        out.position(76)
        out.putInt(32)
        out.putInt(0x04)
        out.putInt(0x35545844)
        out.position(108)
        out.putInt(0x1000)
        out.position(128)

        for (by in 0 until blockH) {
            for (bx in 0 until blockW) {
                var minA = 255
                var maxA = 0
                val blockPixels = IntArray(16)
                for (i in 0 until 16) {
                    val x = minOf(bx * 4 + (i % 4), width - 1)
                    val y = minOf(by * 4 + (i / 4), height - 1)
                    val p = bitmap.getPixel(x, y)
                    blockPixels[i] = p
                    val a = Color.alpha(p)
                    if (a < minA) minA = a
                    if (a > maxA) maxA = a
                }
                out.put(maxA.toByte())
                out.put(minA.toByte())
                var alphaBits = 0L
                for (i in 0 until 16) {
                    val a = Color.alpha(blockPixels[i])
                    val idx = if (maxA == minA) 0L else (((maxA - a) * 7L) / (maxA - minA)).coerceIn(0L, 7L)
                    alphaBits = alphaBits or (idx shl (i * 3))
                }
                for (b in 0 until 6) out.put(((alphaBits shr (b * 8)) and 0xFFL).toByte())

                var minC = 0xFFFF
                var maxC = 0
                for (p in blockPixels) {
                    val c565 = rgb888To565(p)
                    if (c565 < minC) minC = c565
                    if (c565 > maxC) maxC = c565
                }
                out.putShort(maxC.toShort())
                out.putShort(minC.toShort())
                var colorBits = 0
                for (i in 0 until 16) {
                    val c565 = rgb888To565(blockPixels[i])
                    val idx = when {
                        maxC == minC -> 0
                        c565 >= (maxC + minC) / 2 -> 0
                        else -> 1
                    }
                    colorBits = colorBits or (idx shl (i * 2))
                }
                out.putInt(colorBits)
            }
        }
        return out.array()
    }

    private fun rgb565To888(c: Int): Int {
        val r = ((c shr 11) and 0x1F) * 255 / 31
        val g = ((c shr 5) and 0x3F) * 255 / 63
        val b = (c and 0x1F) * 255 / 31
        return Color.argb(255, r, g, b)
    }

    private fun rgb888To565(argb: Int): Int {
        val r = (Color.red(argb) shr 3) and 0x1F
        val g = (Color.green(argb) shr 2) and 0x3F
        val b = (Color.blue(argb) shr 3) and 0x1F
        return (r shl 11) or (g shl 5) or b
    }

    private fun interpolateColor(c0: Int, c1: Int, w0: Int, w1: Int, div: Int): Int {
        val r = (Color.red(c0) * w0 + Color.red(c1) * w1) / div
        val g = (Color.green(c0) * w0 + Color.green(c1) * w1) / div
        val b = (Color.blue(c0) * w0 + Color.blue(c1) * w1) / div
        return Color.argb(255, r, g, b)
    }

    private fun decodeDxt5Alpha(block: Long, pixelIdx: Int): Int {
        val a0 = (block and 0xFFL).toInt()
        val a1 = ((block shr 8) and 0xFFL).toInt()
        val code = ((block shr (16 + pixelIdx * 3)) and 0x07L).toInt()
        if (code == 0) return a0
        if (code == 1) return a1
        return if (a0 > a1) {
            ((8 - code) * a0 + (code - 1) * a1) / 7
        } else {
            when (code) {
                6 -> 0
                7 -> 255
                else -> ((6 - code) * a0 + (code - 1) * a1) / 5
            }
        }
    }
}
