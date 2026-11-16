package com.pes2013.imgpatcher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AfsEntryInfo(
    val index: Int,
    val name: String,
    val offset: Long,
    val size: Long,
    var isModified: Boolean = false
) {
    override fun toString(): String {
        val status = if (isModified) " [DIUBAH]" else ""
        val kb = size / 1024
        return "$name  ($kb KB)$status"
    }
}

object AfsPatcherEngine {
    private const val BLOCK_SIZE = 2048L

    private fun align2048(value: Long): Long {
        val remainder = value % BLOCK_SIZE
        return if (remainder == 0L) value else value + (BLOCK_SIZE - remainder)
    }

    private fun readUInt32LE(raf: RandomAccessFile): Long {
        val bytes = ByteArray(4)
        raf.readFully(bytes)
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    private fun writeUInt32LE(fos: FileOutputStream, value: Long) {
        val buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(value.toInt())
        fos.write(buffer.array())
    }

    fun readArchiveDirectory(imgFile: File): List<AfsEntryInfo> {
        val list = mutableListOf<AfsEntryInfo>()
        RandomAccessFile(imgFile, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "AFS\u0000") {
                throw IllegalArgumentException("File bukan format AFS PES 2013 yang valid!")
            }
            val numFiles = readUInt32LE(raf).toInt()
            for (i in 0 until numFiles) {
                val off = readUInt32LE(raf)
                val sz = readUInt32LE(raf)
                list.add(AfsEntryInfo(i, "unnamed_$i.bin", off, sz))
            }
        }
        return list
    }

    fun extractSingleEntry(imgFile: File, entry: AfsEntryInfo): ByteArray {
        if (entry.size <= 0L) return ByteArray(0)
        RandomAccessFile(imgFile, "r").use { raf ->
            raf.seek(entry.offset)
            val data = ByteArray(entry.size.toInt())
            raf.readFully(data)
            return data
        }
    }

    fun patchImg(
        imgFile: File,
        patches: Map<Int, File>,
        onProgress: (percent: Int, message: String) -> Unit
    ) {
        if (!imgFile.exists()) throw IllegalArgumentException("File .img tidak ditemukan!")
        if (patches.isEmpty()) throw IllegalArgumentException("Belum ada perubahan untuk disimpan!")

        val freeSpace = imgFile.parentFile?.usableSpace ?: 0L
        if (freeSpace < imgFile.length() * 1.15) {
            throw IllegalStateException("Ruang penyimpanan HP penuh! Kosongkan minimal ${(imgFile.length() / (1024 * 1024)) + 200} MB.")
        }

        val tempFile = File(imgFile.parent, "${imgFile.name}.tmp")
        val backupFile = File(imgFile.parent, "${imgFile.name}.bak")
        if (tempFile.exists()) tempFile.delete()

        RandomAccessFile(imgFile, "r").use { rafIn ->
            val magic = ByteArray(4)
            rafIn.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "AFS\u0000") {
                throw IllegalArgumentException("Format tidak valid! File ini bukan arsip AFS PES 2013.")
            }

            val numFiles = readUInt32LE(rafIn).toInt()
            onProgress(2, "Membaca $numFiles indeks file...")

            val oldOffsets = LongArray(numFiles)
            val oldSizes = LongArray(numFiles)
            for (i in 0 until numFiles) {
                oldOffsets[i] = readUInt32LE(rafIn)
                oldSizes[i] = readUInt32LE(rafIn)
            }
            val fnDirOffset = readUInt32LE(rafIn)
            val fnDirSize = readUInt32LE(rafIn)

            val headerTotalSize = 8L + (numFiles + 1L) * 8L
            var currentOffset = align2048(headerTotalSize)

            val newOffsets = LongArray(numFiles)
            val newSizes = LongArray(numFiles)

            for (i in 0 until numFiles) {
                val size = patches[i]?.length() ?: oldSizes[i]
                newSizes[i] = size
                if (size == 0L) {
                    newOffsets[i] = 0L
                } else {
                    newOffsets[i] = currentOffset
                    currentOffset = align2048(currentOffset + size)
                }
            }
            val newFnDirOffset = if (fnDirOffset > 0L) currentOffset else 0L

            FileOutputStream(tempFile).use { fosOut ->
                fosOut.write(byteArrayOf(0x41, 0x46, 0x53, 0x00))
                writeUInt32LE(fosOut, numFiles.toLong())

                for (i in 0 until numFiles) {
                    writeUInt32LE(fosOut, newOffsets[i])
                    writeUInt32LE(fosOut, newSizes[i])
                }
                writeUInt32LE(fosOut, newFnDirOffset)
                writeUInt32LE(fosOut, fnDirSize)

                var bytesWritten = headerTotalSize
                val firstDataOffset = newOffsets.firstOrNull { it > 0L } ?: align2048(headerTotalSize)
                if (firstDataOffset > bytesWritten) {
                    val pad = ByteArray((firstDataOffset - bytesWritten).toInt())
                    fosOut.write(pad)
                    bytesWritten += pad.size
                }

                val buffer = ByteArray(1024 * 1024)
                val zeroPad = ByteArray(BLOCK_SIZE.toInt())

                for (i in 0 until numFiles) {
                    val targetOff = newOffsets[i]
                    val targetSz = newSizes[i]
                    if (targetSz == 0L) continue

                    if (bytesWritten < targetOff) {
                        val gap = (targetOff - bytesWritten).toInt()
                        fosOut.write(zeroPad, 0, gap)
                        bytesWritten += gap
                    }

                    val patchFile = patches[i]
                    if (patchFile != null) {
                        FileInputStream(patchFile).use { fisPatch ->
                            var read: Int
                            while (fisPatch.read(buffer).also { read = it } != -1) {
                                fosOut.write(buffer, 0, read)
                                bytesWritten += read
                            }
                        }
                    } else {
                        rafIn.seek(oldOffsets[i])
                        var remaining = oldSizes[i]
                        while (remaining > 0L) {
                            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = rafIn.read(buffer, 0, toRead)
                            if (read <= 0) break
                            fosOut.write(buffer, 0, read)
                            bytesWritten += read
                            remaining -= read
                        }
                    }

                    val alignedEnd = align2048(bytesWritten)
                    val padLen = (alignedEnd - bytesWritten).toInt()
                    if (padLen > 0) {
                        fosOut.write(zeroPad, 0, padLen)
                        bytesWritten += padLen
                    }

                    if (i % 150 == 0 || i == numFiles - 1) {
                        val percent = 5 + ((i.toFloat() / numFiles.toFloat()) * 90).toInt()
                        onProgress(percent, "Menyusun ulang slot $i / $numFiles ($percent%)...")
                    }
                }

                if (fnDirOffset > 0L && fnDirSize > 0L) {
                    onProgress(96, "Menyalin direktori nama file...")
                    rafIn.seek(fnDirOffset)
                    var remaining = fnDirSize
                    while (remaining > 0L) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = rafIn.read(buffer, 0, toRead)
                        if (read <= 0) break
                        fosOut.write(buffer, 0, read)
                        bytesWritten += read
                        remaining -= read
                    }
                    val alignedEnd = align2048(bytesWritten)
                    val padLen = (alignedEnd - bytesWritten).toInt()
                    if (padLen > 0) {
                        fosOut.write(zeroPad, 0, padLen)
                        bytesWritten += padLen
                    }
                }
                fosOut.flush()
            }
        }

        onProgress(99, "Memverifikasi & menerapkan file...")
        if (tempFile.length() == 0L) {
            tempFile.delete()
            throw IllegalStateException("Proses gagal: file hasil berukuran 0 byte.")
        }

        if (backupFile.exists()) backupFile.delete()
        if (!imgFile.renameTo(backupFile)) {
            throw IllegalStateException("Gagal membuat cadangan file asli.")
        }
        if (!tempFile.renameTo(imgFile)) {
            backupFile.renameTo(imgFile)
            throw IllegalStateException("Gagal mengganti file hasil patch.")
        }

        onProgress(100, "Selesai 100%! Cadangan asli tersimpan di ${backupFile.name}")
    }
}
