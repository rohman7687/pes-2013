package com.pes2013.imgpatcher

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private var currentImgFile: File? = null
    private var allEntries = mutableListOf<AfsEntryInfo>()
    private var filteredEntries = mutableListOf<AfsEntryInfo>()
    private lateinit var listAdapter: ArrayAdapter<AfsEntryInfo>

    private var selectedEntry: AfsEntryInfo? = null
    private var currentRawBinBytes: ByteArray? = null
    private var currentUnpackedSubs = mutableListOf<PesSubFile>()
    private var activeTextureSubIndex = -1
    private var activePreviewBitmap: Bitmap? = null
    private var activeDetectedMediaType = PesMediaType.BINARY_HEX

    private val stagedPatches = mutableMapOf<Int, File>()

    // Mode Multi-Select & Clipboard ala ZArchiver
    private var isMultiSelectMode = false
    private val selectedIndices = linkedSetOf<Int>()
    private val internalClipboard = mutableListOf<ByteArray>()
    private var pendingPhonePasteTargetIndex: Int = -1

    private lateinit var tvCurrentImg: TextView
    private lateinit var tvToggleGuide: TextView
    private lateinit var etSearchIndex: EditText
    private lateinit var layoutMultiSelectBar: LinearLayout
    private lateinit var tvSelectedCount: TextView
    private lateinit var svImgGuide: ScrollView
    private lateinit var tvImgGuideContent: TextView
    private lateinit var lvArchiveEntries: ListView

    private lateinit var tvSelectedBinInfo: TextView
    private lateinit var ivTexturePreview: ImageView
    private lateinit var tvPreviewHint: TextView
    private lateinit var btnOpenMediaViewer: Button
    private lateinit var btnReplacePng: Button
    private lateinit var btnExportPng: Button
    private lateinit var btnExportRawBin: Button
    private lateinit var btnDeleteSingleBin: Button
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
                val idx = Regex("(\\d+)\\.bin$", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
                if (idx != null && idx in allEntries.indices) {
                    val outFile = File(stageDir, "unnamed_$idx.bin")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(outFile).use { output -> input.copyTo(output) }
                    }
                    stagedPatches[idx] = outFile
                    allEntries[idx].size = outFile.length()
                    allEntries[idx].isModified = true
                    allEntries[idx].isDeleted = false
                    count++
                }
            }
            listAdapter.notifyDataSetChanged()
            updateSaveButtonText()
            Toast.makeText(this, "$count file .bin masuk antrean patch!", Toast.LENGTH_SHORT).show()
        }
    }

    private val addNewBinLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (currentImgFile == null) {
            Toast.makeText(this, "Buka file .img terlebih dahulu!", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        if (uris.isNotEmpty()) {
            val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
            var addedCount = 0
            for (uri in uris) {
                val newIdx = allEntries.size
                val outFile = File(stageDir, "unnamed_$newIdx.bin")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output -> input.copyTo(output) }
                }
                stagedPatches[newIdx] = outFile
                allEntries.add(
                    AfsEntryInfo(
                        index = newIdx,
                        name = "unnamed_$newIdx.bin",
                        offset = 0L,
                        size = outFile.length(),
                        isModified = true,
                        isAdded = true
                    )
                )
                addedCount++
            }
            tvCurrentImg.text = "File: ${currentImgFile?.name} (${allEntries.size} slot)"
            filterArchiveList(etSearchIndex.text.toString())
            updateSaveButtonText()
            Toast.makeText(this, "$addedCount slot .bin baru ditambahkan di bagian akhir!", Toast.LENGTH_LONG).show()
        }
    }

    private val pasteFromPhoneSequentialLauncher = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (currentImgFile == null || uris.isEmpty()) return@registerForActivityResult
        val startIdx = if (pendingPhonePasteTargetIndex >= 0) pendingPhonePasteTargetIndex else 0
        val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
        var pasted = 0

        for ((offsetIdx, uri) in uris.withIndex()) {
            val targetIdx = startIdx + offsetIdx
            val outFile = File(stageDir, "unnamed_$targetIdx.bin")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            stagedPatches[targetIdx] = outFile
            if (targetIdx < allEntries.size) {
                allEntries[targetIdx].size = outFile.length()
                allEntries[targetIdx].isModified = true
                allEntries[targetIdx].isDeleted = false
            } else {
                allEntries.add(
                    AfsEntryInfo(
                        index = targetIdx,
                        name = "unnamed_$targetIdx.bin",
                        offset = 0L,
                        size = outFile.length(),
                        isModified = true,
                        isAdded = true
                    )
                )
            }
            pasted++
        }
        tvCurrentImg.text = "File: ${currentImgFile?.name} (${allEntries.size} slot)"
        filterArchiveList(etSearchIndex.text.toString())
        updateSaveButtonText()
        Toast.makeText(this, "$pasted file dari penyimpanan HP ditempel berurutan mulai slot $startIdx!", Toast.LENGTH_LONG).show()
    }

    private val pickReplacementPngLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { applyReplacementImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvCurrentImg = findViewById(R.id.tvCurrentImg)
        tvToggleGuide = findViewById(R.id.tvToggleGuide)
        etSearchIndex = findViewById(R.id.etSearchIndex)
        layoutMultiSelectBar = findViewById(R.id.layoutMultiSelectBar)
        tvSelectedCount = findViewById(R.id.tvSelectedCount)
        svImgGuide = findViewById(R.id.svImgGuide)
        tvImgGuideContent = findViewById(R.id.tvImgGuideContent)
        lvArchiveEntries = findViewById(R.id.lvArchiveEntries)

        tvSelectedBinInfo = findViewById(R.id.tvSelectedBinInfo)
        ivTexturePreview = findViewById(R.id.ivTexturePreview)
        tvPreviewHint = findViewById(R.id.tvPreviewHint)
        btnOpenMediaViewer = findViewById(R.id.btnOpenMediaViewer)
        btnReplacePng = findViewById(R.id.btnReplacePng)
        btnExportPng = findViewById(R.id.btnExportPng)
        btnExportRawBin = findViewById(R.id.btnExportRawBin)
        btnDeleteSingleBin = findViewById(R.id.btnDeleteSingleBin)
        btnSaveRebuildImg = findViewById(R.id.btnSaveRebuildImg)
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)

        checkStoragePermission()
        populateImgGuideText()

        listAdapter = object : ArrayAdapter<AfsEntryInfo>(this, android.R.layout.simple_list_item_1, filteredEntries) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                val entry = filteredEntries[position]
                val isChecked = selectedIndices.contains(entry.index)
                view.text = if (isMultiSelectMode) {
                    val mark = if (isChecked) "[✓] " else "[  ] "
                    mark + entry.toString()
                } else {
                    entry.toString()
                }
                view.setBackgroundColor(
                    if (isChecked) Color.parseColor("#332196F3") else Color.TRANSPARENT
                )
                return view
            }
        }
        lvArchiveEntries.adapter = listAdapter

        findViewById<Button>(R.id.btnOpenImg).setOnClickListener {
            pickImgLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnBatchImportBin).setOnClickListener {
            batchImportBinLauncher.launch(arrayOf("*/*"))
        }

        findViewById<Button>(R.id.btnAddNewBin).setOnClickListener {
            addNewBinLauncher.launch(arrayOf("*/*"))
        }

        tvToggleGuide.setOnClickListener {
            if (svImgGuide.visibility == View.VISIBLE && currentImgFile != null) {
                svImgGuide.visibility = View.GONE
                lvArchiveEntries.visibility = View.VISIBLE
                tvToggleGuide.text = "Panduan .IMG"
            } else {
                svImgGuide.visibility = View.VISIBLE
                lvArchiveEntries.visibility = View.GONE
                tvToggleGuide.text = "Tutup Panduan"
            }
        }

        etSearchIndex.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = filterArchiveList(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Klik biasa: jika mode multi-select aktif maka toggle centang; jika tidak maka buka pratinjau
        lvArchiveEntries.setOnItemClickListener { _, _, position, _ ->
            val entry = filteredEntries[position]
            if (isMultiSelectMode) {
                toggleSelection(entry.index)
            } else {
                inspectBinSlot(entry)
            }
        }

        // Tekan lama (Long Press): aktifkan Multi-Select ala ZArchiver
        lvArchiveEntries.setOnItemLongClickListener { _, _, position, _ ->
            val entry = filteredEntries[position]
            if (!isMultiSelectMode) {
                isMultiSelectMode = true
                layoutMultiSelectBar.visibility = View.VISIBLE
            }
            toggleSelection(entry.index)
            true
        }

        // Tombol Bilah Multi-Select
        findViewById<Button>(R.id.btnSelectRange).setOnClickListener { selectRangeBetweenChecked() }
        findViewById<Button>(R.id.btnSelectAll).setOnClickListener {
            if (selectedIndices.size == filteredEntries.size) {
                selectedIndices.clear()
            } else {
                filteredEntries.forEach { selectedIndices.add(it.index) }
            }
            updateMultiSelectHeader()
        }
        findViewById<Button>(R.id.btnCancelSelect).setOnClickListener { exitMultiSelectMode() }
        findViewById<Button>(R.id.btnMultiCopy).setOnClickListener { copySelectedEntriesToClipboard() }
        findViewById<Button>(R.id.btnMultiPaste).setOnClickListener { showPasteOptionsDialog() }
        findViewById<Button>(R.id.btnMultiExport).setOnClickListener { exportSelectedEntriesBatch() }
        findViewById<Button>(R.id.btnMultiDelete).setOnClickListener { deleteSelectedEntriesBatch() }

        // Pratinjau Full Screen atau Pemutar Media saat kotak pratinjau diketuk
        ivTexturePreview.setOnClickListener {
            openActiveEntryMedia()
        }

        btnOpenMediaViewer.setOnClickListener {
            openActiveEntryMedia()
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

        btnDeleteSingleBin.setOnClickListener {
            val entry = selectedEntry ?: return@setOnClickListener
            showSingleDeleteDialog(entry)
        }

        btnSaveRebuildImg.setOnClickListener {
            executeRebuildImg()
        }
    }

    private fun openActiveEntryMedia() {
        val entry = selectedEntry ?: return
        val img = currentImgFile ?: return
        if (activePreviewBitmap != null) {
            showFullScreenImagePreview()
        } else if (entry.size > 0L) {
            PesMediaInspector.openUniversalViewerFromArchive(
                context = this,
                imgFile = img,
                entry = entry,
                stagedFile = stagedPatches[entry.index],
                inMemoryBytes = currentRawBinBytes,
                type = activeDetectedMediaType
            )
        }
    }

    private fun populateImgGuideText() {
        tvImgGuideContent.text = """
            PANDUAN DAFTAR FILE .IMG PES 2013 & KEGUNAANNYA
            (Ketuk 'BUKA .IMG' di atas untuk mulai membongkar arsip)

            • dt00_e.img / dt00_i.img :
              Teks menu utama, bahasa sistem, serta komentar pertandingan.

            • dt01.img :
              Efek suara stadion (SFX), sorakan suporter, peluit wasit, dan suara tendangan bola.

            • dt02.img :
              Musik latar menu utama (Soundtrack / BGM) dan lagu masuk lapangan.

            • dt03.img :
              Video pembuka (Intro), animasi trofi juara, dan cutscene stadion (slot besar SFD/USM).

            • dt04.img :
              Database utama pemain, nama tim, statistik, transfer, dan formasi (unnamed_1900 - 2000+).

            • dt05_e.img / dt05_i.img :
              Teks nama kompetisi, nama stadion, dan deskripsi mode permainan.

            • dt06.img :
              Tampilan menu UI, logo klub/liga, papan skor (Scoreboard), bola, sepatu, dan ikon tombol.

            • dt07.img :
              Tekstur lapangan (Pitch), rumput stadion, jaring gawang, papan iklan (Adboards), dan langit/cuaca.

            • dt08.img :
              Struktur bangunan stadion, tribun penonton, terowongan pemain, dan model 3D stadion.

            • dt09.img :
              Seragam tim (Kits / Jersey), baju wasit, nomor punggung, dan font nama pemain di jersey.

            • dt0a.img :
              Wajah pemain (Faces), model rambut (Hair), dan tekstur kulit pemain.

            • dt0b.img :
              Animasi gerakan pemain, selebrasi gol, gerakan kiper, dan fisika pergerakan.

            • dt0c.img - dt0f.img :
              Data tambahan liga khusus (Copa Libertadores, UEFA Champions League, dan pembaruan DLC).

            TIPS ZARCHIVER & MEDIA EXPLORER:
            - Tekan lama pada daftar slot untuk memilih 10 - 100+ file sekaligus (Salin, Tempel dari HP/Antar-Slot, Ekspor, atau Hapus).
            - Ketuk gambar pratinjau untuk melihat ukuran penuh (Full Screen).
            - Gunakan 'Putar / Buka Isi File' untuk mendengarkan audio ADX, memutar video SFD/USM, atau melihat teks data pemain.
        """.trimIndent()
    }

    private fun toggleSelection(index: Int) {
        if (selectedIndices.contains(index)) {
            selectedIndices.remove(index)
        } else {
            selectedIndices.add(index)
        }
        if (selectedIndices.isEmpty()) {
            exitMultiSelectMode()
        } else {
            updateMultiSelectHeader()
        }
    }

    private fun updateMultiSelectHeader() {
        tvSelectedCount.text = "${selectedIndices.size} dipilih"
        listAdapter.notifyDataSetChanged()
    }

    private fun exitMultiSelectMode() {
        isMultiSelectMode = false
        selectedIndices.clear()
        layoutMultiSelectBar.visibility = View.GONE
        listAdapter.notifyDataSetChanged()
    }

    private fun selectRangeBetweenChecked() {
        if (selectedIndices.size < 2) {
            Toast.makeText(this, "Centang minimal 2 item (awal dan akhir) lalu tekan Rentang!", Toast.LENGTH_SHORT).show()
            return
        }
        val minIdx = selectedIndices.minOrNull() ?: return
        val maxIdx = selectedIndices.maxOrNull() ?: return
        for (entry in filteredEntries) {
            if (entry.index in minIdx..maxIdx) {
                selectedIndices.add(entry.index)
            }
        }
        updateMultiSelectHeader()
        Toast.makeText(this, "Terpilih rentang slot $minIdx s/d $maxIdx (${selectedIndices.size} item)", Toast.LENGTH_SHORT).show()
    }

    private fun copySelectedEntriesToClipboard() {
        val img = currentImgFile ?: return
        if (selectedIndices.isEmpty()) return
        val targets = selectedIndices.sorted()
        tvStatus.text = "Status: Menyalin ${targets.size} slot ke clipboard..."

        thread {
            internalClipboard.clear()
            for (idx in targets) {
                val bytes = stagedPatches[idx]?.readBytes()
                    ?: AfsPatcherEngine.extractSingleEntry(img, allEntries[idx])
                internalClipboard.add(bytes)
            }
            runOnUiThread {
                tvStatus.text = "Status: ${internalClipboard.size} file .bin disalin ke clipboard."
                Toast.makeText(this, "${internalClipboard.size} file .bin disalin! Pilih slot tujuan lalu tekan TEMPEL.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPasteOptionsDialog() {
        val targetStart = selectedIndices.firstOrNull() ?: (selectedEntry?.index ?: 0)
        val options = arrayOf(
            "Tempel dari Clipboard Aplikasi (${internalClipboard.size} file) mulai slot $targetStart",
            "Tempel dari Penyimpanan Telepon (Berurutan mulai slot $targetStart)",
            "Tempel dari Penyimpanan Telepon (Otomatis sesuai nomor nama file)"
        )
        AlertDialog.Builder(this)
            .setTitle("Pilih Sumber Tempel (Paste)")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pasteFromInternalClipboard(targetStart)
                    1 -> {
                        pendingPhonePasteTargetIndex = targetStart
                        pasteFromPhoneSequentialLauncher.launch(arrayOf("*/*"))
                    }
                    2 -> batchImportBinLauncher.launch(arrayOf("*/*"))
                }
            }
            .show()
    }

    private fun pasteFromInternalClipboard(startIdx: Int) {
        if (internalClipboard.isEmpty()) {
            Toast.makeText(this, "Clipboard kosong! Salin slot terlebih dahulu atau pilih 'Tempel dari Penyimpanan Telepon'.", Toast.LENGTH_LONG).show()
            return
        }
        val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
        for ((offsetIdx, data) in internalClipboard.withIndex()) {
            val targetIdx = startIdx + offsetIdx
            val outFile = File(stageDir, "unnamed_$targetIdx.bin")
            outFile.writeBytes(data)
            stagedPatches[targetIdx] = outFile
            if (targetIdx < allEntries.size) {
                allEntries[targetIdx].size = outFile.length()
                allEntries[targetIdx].isModified = true
                allEntries[targetIdx].isDeleted = false
            } else {
                allEntries.add(
                    AfsEntryInfo(
                        index = targetIdx,
                        name = "unnamed_$targetIdx.bin",
                        offset = 0L,
                        size = outFile.length(),
                        isModified = true,
                        isAdded = true
                    )
                )
            }
        }
        tvCurrentImg.text = "File: ${currentImgFile?.name} (${allEntries.size} slot)"
        exitMultiSelectMode()
        filterArchiveList(etSearchIndex.text.toString())
        updateSaveButtonText()
        Toast.makeText(this, "${internalClipboard.size} file berhasil ditempel mulai slot $startIdx!", Toast.LENGTH_SHORT).show()
    }

    private fun exportSelectedEntriesBatch() {
        val img = currentImgFile ?: return
        if (selectedIndices.isEmpty()) return
        val targets = selectedIndices.sorted()
        val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PES2013_Export").apply { mkdirs() }

        thread {
            for (idx in targets) {
                val bytes = stagedPatches[idx]?.readBytes()
                    ?: AfsPatcherEngine.extractSingleEntry(img, allEntries[idx])
                File(exportDir, "unnamed_$idx.bin").writeBytes(bytes)
            }
            runOnUiThread {
                Toast.makeText(this, "${targets.size} file .bin diekspor ke Download/PES2013_Export/", Toast.LENGTH_LONG).show()
                exitMultiSelectMode()
            }
        }
    }

    private fun deleteSelectedEntriesBatch() {
        if (selectedIndices.isEmpty()) return
        val count = selectedIndices.size
        val options = arrayOf(
            "Kosongkan isi $count slot menjadi 0 KB (Aman untuk PES 2013)",
            "Batalkan perubahan (Revert) pada $count slot terpilih"
        )
        AlertDialog.Builder(this)
            .setTitle("Hapus / Kelola $count Slot Terpilih")
            .setItems(options) { _, which ->
                val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
                for (idx in selectedIndices) {
                    if (which == 0) {
                        val emptyFile = File(stageDir, "unnamed_$idx.bin").apply { writeBytes(ByteArray(0)) }
                        stagedPatches[idx] = emptyFile
                        allEntries[idx].size = 0L
                        allEntries[idx].isDeleted = true
                        allEntries[idx].isModified = true
                    } else {
                        stagedPatches.remove(idx)
                        File(stageDir, "unnamed_$idx.bin").delete()
                        allEntries[idx].isModified = false
                        allEntries[idx].isDeleted = false
                    }
                }
                exitMultiSelectMode()
                updateSaveButtonText()
                Toast.makeText(this, "Perubahan diterapkan pada $count slot!", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showSingleDeleteDialog(entry: AfsEntryInfo) {
        val options = arrayOf(
            "Kosongkan isi ${entry.name} menjadi 0 KB (Aman untuk PES 2013)",
            "Batalkan perubahan slot ini (Kembalikan ke asli)"
        )
        AlertDialog.Builder(this)
            .setTitle("Hapus Isi ${entry.name}")
            .setItems(options) { _, which ->
                val stageDir = File(cacheDir, "staged_bins").apply { mkdirs() }
                if (which == 0) {
                    val emptyFile = File(stageDir, "unnamed_${entry.index}.bin").apply { writeBytes(ByteArray(0)) }
                    stagedPatches[entry.index] = emptyFile
                    entry.size = 0L
                    entry.isDeleted = true
                    entry.isModified = true
                    currentRawBinBytes = ByteArray(0)
                    ivTexturePreview.setImageDrawable(null)
                    tvPreviewHint.visibility = View.GONE
                    tvSelectedBinInfo.text = "${entry.name} | Dikosongkan (0 KB)"
                } else {
                    stagedPatches.remove(entry.index)
                    File(stageDir, "unnamed_${entry.index}.bin").delete()
                    entry.isModified = false
                    entry.isDeleted = false
                    inspectBinSlot(entry)
                }
                listAdapter.notifyDataSetChanged()
                updateSaveButtonText()
            }
            .show()
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
                    exitMultiSelectMode()
                    File(cacheDir, "staged_bins").deleteRecursively()

                    svImgGuide.visibility = View.GONE
                    lvArchiveEntries.visibility = View.VISIBLE
                    tvToggleGuide.text = "Panduan .IMG"

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
        tvSelectedBinInfo.text = "Memeriksa ${entry.name}..."

        thread {
            try {
                // 1. Baca header 64 KB dulu untuk deteksi instan (aman untuk file video ratusan MB di dt03.img)
                val headerBytes = readEntryHead(img, entry, stagedPatches[entry.index], 65536)
                val quickMediaType = PesMediaInspector.detectMediaType(headerBytes, false, entry.size)

                // Jika langsung terdeteksi Video (SFD/MPEG/USM) atau file > 12 MB, jangan muat semua ke RAM
                if (quickMediaType == PesMediaType.VIDEO_SFD_MPEG ||
                    quickMediaType == PesMediaType.VIDEO_USM_CRID ||
                    entry.size > 12L * 1024L * 1024L
                ) {
                    val finalType = if (quickMediaType == PesMediaType.VIDEO_USM_CRID) {
                        PesMediaType.VIDEO_USM_CRID
                    } else {
                        PesMediaType.VIDEO_SFD_MPEG
                    }
                    runOnUiThread {
                        currentRawBinBytes = null
                        currentUnpackedSubs.clear()
                        activeTextureSubIndex = -1
                        activePreviewBitmap = null
                        activeDetectedMediaType = finalType

                        ivTexturePreview.setImageResource(android.R.drawable.ic_media_play)
                        tvPreviewHint.text = "Ketuk putar video"
                        tvPreviewHint.visibility = View.VISIBLE

                        btnExportRawBin.isEnabled = true
                        btnDeleteSingleBin.isEnabled = true
                        btnOpenMediaViewer.isEnabled = true
                        btnOpenMediaViewer.text = "Putar / Ekstrak Video"
                        btnReplacePng.isEnabled = false
                        btnExportPng.isEnabled = false
                        tvSelectedBinInfo.text = "${entry.name} | ${PesMediaInspector.getMediaLabel(finalType, entry.size)}"
                    }
                    return@thread
                }

                // 2. Untuk file <= 12 MB, muat penuh & bongkar tekstur/audio/teks
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

                // Cek tipe media baik dari sub[0] maupun raw binBytes
                var mediaType = PesMediaInspector.detectMediaType(binBytes, foundBitmap != null, entry.size)
                if (mediaType == PesMediaType.BINARY_HEX && subs.isNotEmpty()) {
                    mediaType = PesMediaInspector.detectMediaType(subs[0].rawData, foundBitmap != null, entry.size)
                }

                runOnUiThread {
                    currentRawBinBytes = binBytes
                    currentUnpackedSubs = subs
                    activeTextureSubIndex = foundSubIdx
                    activePreviewBitmap = foundBitmap
                    activeDetectedMediaType = mediaType

                    btnExportRawBin.isEnabled = binBytes.isNotEmpty()
                    btnDeleteSingleBin.isEnabled = true
                    btnOpenMediaViewer.isEnabled = binBytes.isNotEmpty()

                    if (foundBitmap != null) {
                        ivTexturePreview.setImageBitmap(foundBitmap)
                        tvPreviewHint.text = "Ketuk buka penuh"
                        tvPreviewHint.visibility = View.VISIBLE
                        btnReplacePng.isEnabled = true
                        btnExportPng.isEnabled = true
                        btnOpenMediaViewer.text = "Lihat Gambar Penuh"
                        tvSelectedBinInfo.text = "${entry.name} | Tekstur: ${foundBitmap.width}x${foundBitmap.height} px (${subs.size} sub)"
                    } else {
                        btnReplacePng.isEnabled = false
                        btnExportPng.isEnabled = false
                        when (mediaType) {
                            PesMediaType.AUDIO_ADX, PesMediaType.AUDIO_OGG_WAV -> {
                                ivTexturePreview.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
                                tvPreviewHint.text = "Ketuk putar audio"
                                tvPreviewHint.visibility = View.VISIBLE
                                btnOpenMediaViewer.text = "Putar Audio / Musik"
                            }
                            PesMediaType.VIDEO_SFD_MPEG, PesMediaType.VIDEO_USM_CRID -> {
                                ivTexturePreview.setImageResource(android.R.drawable.ic_media_play)
                                tvPreviewHint.text = "Ketuk putar video"
                                tvPreviewHint.visibility = View.VISIBLE
                                btnOpenMediaViewer.text = "Putar / Ekstrak Video"
                            }
                            else -> {
                                ivTexturePreview.setImageDrawable(null)
                                tvPreviewHint.visibility = View.GONE
                                btnOpenMediaViewer.text = "Lihat Teks / Data Hex"
                            }
                        }
                        val typeDesc = PesMediaInspector.getMediaLabel(mediaType, entry.size)
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

    private fun readEntryHead(imgFile: File, entry: AfsEntryInfo, stagedFile: File?, maxBytes: Int): ByteArray {
        if (stagedFile != null && stagedFile.exists()) {
            val len = minOf(stagedFile.length(), maxBytes.toLong()).toInt()
            val out = ByteArray(len)
            stagedFile.inputStream().use { it.read(out) }
            return out
        }
        if (entry.size <= 0L) return ByteArray(0)
        val len = minOf(entry.size, maxBytes.toLong()).toInt()
        val out = ByteArray(len)
        RandomAccessFile(imgFile, "r").use { raf ->
            raf.seek(entry.offset)
            raf.readFully(out)
        }
        return out
    }

    private fun showFullScreenImagePreview() {
        val bmp = activePreviewBitmap ?: return
        val entry = selectedEntry ?: return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121212"))
            setPadding(24, 24, 24, 24)
            gravity = Gravity.CENTER
        }

        val infoText = TextView(this).apply {
            text = "${entry.name} (${bmp.width} × ${bmp.height} px)"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        val fullImageView = ImageView(this).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val btnClose = Button(this).apply {
            text = "TUTUP PRATINJAU PENUH"
        }

        container.addView(infoText)
        container.addView(fullImageView)
        container.addView(btnClose)

        val dialog = AlertDialog.Builder(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(container)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
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
                entry.size = stagedFile.length()
                entry.isModified = true
                entry.isDeleted = false
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
        val img = currentImgFile ?: return
        val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PES2013_Export").apply { mkdirs() }
        val outFile = File(exportDir, "unnamed_${entry.index}.bin")

        thread {
            val staged = stagedPatches[entry.index]
            if (staged != null && staged.exists()) {
                staged.copyTo(outFile, overwrite = true)
            } else {
                RandomAccessFile(img, "r").use { raf ->
                    FileOutputStream(outFile).use { fos ->
                        raf.seek(entry.offset)
                        val buf = ByteArray(1024 * 1024)
                        var rem = entry.size
                        while (rem > 0L) {
                            val r = raf.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (r <= 0) break
                            fos.write(buf, 0, r)
                            rem -= r
                        }
                    }
                }
            }
            runOnUiThread {
                Toast.makeText(this, "Tersimpan di: Download/PES2013_Export/${outFile.name}", Toast.LENGTH_LONG).show()
            }
        }
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
                AfsPatcherEngine.patchImg(img, stagedPatches, allEntries.size) { percent, msg ->
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
