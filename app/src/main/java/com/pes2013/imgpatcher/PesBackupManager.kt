package com.pes2013.imgpatcher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PesBackupManager {
    fun createBackup(imgFile: File): File {
        val backupFile = File(imgFile.parent, "${imgFile.name}.bak")
        FileInputStream(imgFile).use { fis ->
            FileOutputStream(backupFile).use { fos ->
                fis.channel.transferTo(0, fis.channel.size(), fos)
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
