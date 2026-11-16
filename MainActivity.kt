package com.pes2013.imgpatcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var currentImgFile: File? = null
    private var allEntries = listOf<AfsEntryInfo>()
    private var filteredEntries = mutableListOf<AfsEntryInfo>()
    private lateinit var listAdapter: ArrayAdapter<AfsEntryInfo>

    private var selectedEntry: AfsEntryInfo? = null
    private var currentRawBinBytes: ByteArray? = null
    private var currentUnpackedSubs = mutableListOf<PesSubFile>()
    private var activeTextureSubIndex = -1
    private var activePreviewBitmap: Bitmap? = null

    private val stagedPatches = mutableMapOf<Int, File>()

    private lateinit var tvCurrentImg: TextView
    private lateinit var etSearchIndex: EditText
    private lateinit var lvArchiveEntries: ListView
    private lateinit var tvSelectedBinInfo: TextView
    private lateinit var ivTexturePreview: ImageView
    private lateinit var btnReplacePng: Button
    private lateinit var btnExportPng: Button
    private lateinit var btnExportRawBin: Button
    private lateinit var btnSaveRebuildImg: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView

    private val pickImgLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val file = resolveFileFromUri(it)
            if (file != null && file.exists()) {
                loadImgArchive(file)
            } else {
                Toast.makeText(this, "Pilih file .img langsung dari Penyimpanan Internal!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val batchImportBinLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (currentImgFile == null) {
            Toast.makeText(this, "Buka file .img terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (uris.isNotEmpty()) {
            val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
            var count = 0
            for (uri in uris) {
                val name = getFileNameFromUri(uri) ?: continue
                val idx = Regex("(\d+)\.bin$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (idx != null && idx in allEntries.indices) {
                    val outFile = File(stageDir, "unnamed_$idx.bin")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                    stagedPatches[idx] = outFile
                    allEntries[idx].isModified = true
                    count++
                }
            }
            listAdapter.notifyDataSetChanged()
            updateSaveButtonText()
            Toast.makeText(this, "$count file .bin masuk antrean patch!", Toast.LENGTH_SHORT).show()
        }
    }

    private val pickReplacementPngLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyReplacementImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentImg = findViewById(R.id.tvCurrentImg)
        etSearchIndex = findViewById(R.id.etSearchIndex)
        lvArchiveEntries = findViewById(R.id.lvArchiveEntries)
        tvSelectedBinInfo = findViewById(R.id.tvSelectedBinInfo)
        ivTexturePreview = findViewById(R.id.ivTexturePreview)
        btnReplacePng = findViewById(R.id.btnReplacePng)
        btnExportPng = findViewById(R.id.btnExportPng)
        btnExportRawBin = findViewById(R.id.btnExportRawBin)
        btnSaveRebuildImg = findViewById(R.id.btnSaveRebuildImg)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        checkStoragePermission()

        listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, filteredEntries)
        lvArchiveEntries.adapter = listAdapter

        findViewById<Button>(R.id.btnOpenImg).setOnClickListener {
            pickImgLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnBatchImportBin).setOnClickListener {
            batchImportBinLauncher.launch(arrayOf("*/*"))
        }

        etSearchIndex.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filterArchiveList(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        lvArchiveEntries.setOnItemClickListener { _, _, position, _ ->
            val entry = filteredEntries[position]
            inspectBinSlot(entry)
        }

        btnReplacePng.setOnClickListener {
            pickReplacementPngLauncher.launch(arrayOf("image/*"))
        }

        btnExportPng.setOnClickListener {
            exportPreviewToPng()
        }

        btnExportRawBin.setOnClickListener {
            exportCurrentBinToDownload()
        }

        btnSaveRebuildImg.setOnClickListener {
            executeRebuildImg()
        }
    }

    private fun loadImgArchive(file: File) {
        tvStatus.text = "Status: Membaca daftar isi ${file.name}..."
        thread {
            try {
                val entries = AfsPatcherEngine.readArchiveDirectory(file)
                runOnUiThread {
                    currentImgFile = file
                    allEntries = entries
                    stagedPatches.clear()
                    File(cacheDir, "staged_bins").deleteRecursively()
                    tvCurrentImg.text = "File: ${file.name} (${entries.size} slot)"
                    filterArchiveList(etSearchIndex.text.toString())
                    updateSaveButtonText()
                    tvStatus.text = "Status: Berhasil memuat ${entries.size} slot file."
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal membuka .img: ${e.message}", Toast.LENGTH_LONG).show()
                    tvStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun filterArchiveList(query: String) {
        filteredEntries.clear()
        val clean = query.trim().lowercase()
        if (clean.isEmpty()) {
            filteredEntries.addAll(allEntries)
        } else {
            filteredEntries.addAll(allEntries.filter {
                it.name.contains(clean) || it.index.toString() == clean
            })
        }
        listAdapter.notifyDataSetChanged()
    }

    private fun inspectBinSlot(entry: AfsEntryInfo) {
        val img = currentImgFile ?: return
        selectedEntry = entry
        tvSelectedBinInfo.text = "Memuat ${entry.name}..."

        thread {
            try {
                val binBytes = stagedPatches[entry.index]?.readBytes()
                    ?: AfsPatcherEngine.extractSingleEntry(img, entry)

                val subs = PesBinContainer.unpackBin(binBytes)
                var foundBitmap: Bitmap? = null
                var foundSubIdx = -1

                for (i in subs.indices) {
                    val bmp = subs[i].toBitmap()
                    if (bmp != null) {
                        foundBitmap = bmp
                        foundSubIdx = i
                        break
                    }
                }

                runOnUiThread {
                    currentRawBinBytes = binBytes
                    currentUnpackedSubs = subs
                    activeTextureSubIndex = foundSubIdx
                    activePreviewBitmap = foundBitmap

                    btnExportRawBin.isEnabled = binBytes.isNotEmpty()
                    if (foundBitmap != null) {
                        ivTexturePreview.setImageBitmap(foundBitmap)
                        btnReplacePng.isEnabled = true
                        btnExportPng.isEnabled = true
                        tvSelectedBinInfo.text = "${entry.name} | Tekstur: ${foundBitmap.width}x${foundBitmap.height} px (${subs.size} sub-file)"
                    } else {
                        ivTexturePreview.setImageDrawable(null)
                        btnReplacePng.isEnabled = false
                        btnExportPng.isEnabled = false
                        val typeDesc = if (subs.isNotEmpty()) subs[0].getDisplayType() else "Kosong"
                        tvSelectedBinInfo.text = "${entry.name} | $typeDesc"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvSelectedBinInfo.text = "Gagal membongkar ${entry.name}: ${e.message}"
                }
            }
        }
    }

    private fun applyReplacementImage(imageUri: Uri) {
        val entry = selectedEntry ?: return
        val origBin = currentRawBinBytes ?: return
        if (activeTextureSubIndex < 0) return

        tvStatus.text = "Status: Mengonversi PNG ke format DDS/Zlib PES 2013..."
        thread {
            try {
                val inputStream = contentResolver.openInputStream(imageUri)
                val newBmp = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (newBmp == null) throw IllegalArgumentException("Gagal membaca gambar yang dipilih.")

                currentUnpackedSubs[activeTextureSubIndex].replaceWithBitmap(newBmp)
                val rebuiltBinBytes = PesBinContainer.packBin(currentUnpackedSubs, origBin)

                val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
                val stagedFile = File(stageDir, "unnamed_${entry.index}.bin")
                stagedFile.writeBytes(rebuiltBinBytes)

                stagedPatches[entry.index] = stagedFile
                entry.isModified = true
                activePreviewBitmap = newBmp

                runOnUiThread {
                    ivTexturePreview.setImageBitmap(newBmp)
                    listAdapter.notifyDataSetChanged()
                    updateSaveButtonText()
                    tvStatus.text = "Status: Tekstur ${entry.name} diperbarui! Klik Simpan untuk menerapkan ke .img."
                    Toast.makeText(this, "Gambar berhasil dimasukkan ke slot ${entry.name}!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Gagal mengganti tekstur: ${e.message}", Toast.LENGTH_LONG).show()
                    tvStatus.text = "Error: ${e.message}"
                }
            }
        }
    }

    private fun exportPreviewToPng() {
        val entry = selectedEntry ?: return
        val bmp = activePreviewBitmap ?: return
        val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PES2013_Export").apply { mkdirs() }
        val outFile = File(exportDir, "unnamed_${entry.index}.png")
        FileOutputStream(outFile).use { fos ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }
        Toast.makeText(this, "Tersimpan di: Download/PES2013_Export/${outFile.name}", Toast.LENGTH_LONG).show()
    }

    private fun exportCurrentBinToDownload() {
        val entry = selectedEntry ?: return
        val bytes = currentRawBinBytes ?: return
        val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PES2013_Export").apply { mkdirs() }
        val outFile = File(exportDir, "unnamed_${entry.index}.bin")
        outFile.writeBytes(bytes)
        Toast.makeText(this, "Tersimpan di: Download/PES2013_Export/${outFile.name}", Toast.LENGTH_LONG).show()
    }

    private fun executeRebuildImg() {
        val img = currentImgFile ?: return
        if (stagedPatches.isEmpty()) {
            Toast.makeText(this, "Belum ada file .bin atau gambar yang diubah!", Toast.LENGTH_SHORT).show()
            return
        }

        btnSaveRebuildImg.isEnabled = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        thread {
            try {
                AfsPatcherEngine.patchImg(img, stagedPatches) { percent, msg ->
                    runOnUiThread {
                        progressBar.progress = percent
                        tvStatus.text = msg
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, "Rebuild selesai 100%! File .img siap dimainkan.", Toast.LENGTH_LONG).show()
                    loadImgArchive(img)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Error: ${e.message}"
                    AlertDialog.Builder(this)
                        .setTitle("Proses Gagal")
                        .setMessage(e.message)
                        .setPositiveButton("OK", null)
                        .show()
                }
            } finally {
                runOnUiThread {
                    btnSaveRebuildImg.isEnabled = true
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun updateSaveButtonText() {
        btnSaveRebuildImg.text = "SIMPAN & REBUILD .IMG (${stagedPatches.size} Perubahan)"
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    private fun resolveFileFromUri(uri: Uri): File? {
        val docId = uri.lastPathSegment ?: return null
        if (docId.startsWith("primary:")) {
            return File(Environment.getExternalStorageDirectory(), docId.substringAfter("primary:"))
        }
        return null
    }
}
