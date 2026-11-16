package com.pes2013.imgpatcher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PesBackupManager {
    fun createBackup(imgFile: File): File {
        val backupFile = File(imgFile.parent, "${imgFile.name}.bak")
        FileInputStream(imgFile).use { fis ->
            FileOutputStream(backupFile).use { fos ->
                val buffer = ByteArray(8192)
                var length: Int
                while (fis.read(buffer).also { length = it } > 0) {
                    fos.write(buffer, 0, length)
                }
                fos.flush()
            }
        }
        return backupFile
    }

    fun restoreBackup(imgFile: File): Boolean {
        val backupFile = File(imgFile.parent, "${imgFile.name}.bak")
        if (!backupFile.exists()) return false
        backupFile.copyTo(imgFile, overwrite = true)
        return true
    }
}
