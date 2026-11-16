paket com.pes2013.imgpatcher

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object PesBackupManager {
    fun createBackup(imgFile: File): File {
        val backupFile = File(imgFile.parent, "${imgFile.name}.bak")
        FileInputStream(imgFile).use { fis ->
            FileOutputStream(backupFile).use { fos ->
                // Menggunakan salinan buffer standar agar aman dari error channel tipe data
                nilai buffer = ByteArray(8192)
                var length: Int
                selama (fis.read(buffer).also { length = it } > 0) {
                    fos.write(buffer, 0, length)
                }
                fos.flush()
            }
        }
        return backupFile
    }

    fungsi restoreBackup(imgFile: File): Boolean {
        val backupFile = File(imgFile.parent, "${imgFile.name}.bak")
        jika (!backupFile.exists()) kembalikan false
        backupFile.copyTo(imgFile, overwrite = true)
        kembalikan true
    }
}
