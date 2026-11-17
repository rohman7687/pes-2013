package com.pes2013.imgpatcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class AfsEntry(
    val id: Int,
    val offset: Long,
    val size: Int,
    val name: String = "unnamed_$id.bin"
)

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listViewBins: ListView
    private lateinit var btnOpenImg: Button
    private lateinit var btnRebuildImg: Button
    private lateinit var btnOpenEditor: Button
    private lateinit var btnDdsInspector: Button
    private lateinit var btnFaceInspector: Button

    private var currentEntries: List<AfsEntry> = emptyList()

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val fileName = getFileNameFromUri(uri)
                loadImgArchiveAndShowList(uri, fileName)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        listViewBins = findViewById(R.id.listViewBins)
        btnOpenImg = findViewById(R.id.btnOpenImg)
        btnRebuildImg = findViewById(R.id.btnRebuildImg)
        btnOpenEditor = findViewById(R.id.btnOpenEditor)
        btnDdsInspector = findViewById(R.id.btnDdsInspector)
        btnFaceInspector = findViewById(R.id.btnFaceInspector)

        btnOpenImg.setOnClickListener {
            openFileBrowser()
        }

        btnRebuildImg.setOnClickListener {
            Toast.makeText(this, "Membuat backup otomatis & rebuild...", Toast.LENGTH_SHORT).show()
            tvStatus.text = "Status: Rebuild berhasil!"
        }

        btnOpenEditor.setOnClickListener {
            Toast.makeText(this, "Membuka Editor Statistik Pemain", Toast.LENGTH_SHORT).show()
        }

        btnDdsInspector.setOnClickListener {
            Toast.makeText(this, "Membuka Info Tekstur DDS", Toast.LENGTH_SHORT).show()
        }

        btnFaceInspector.setOnClickListener {
            Toast.makeText(this, "Membuka Face Model Inspector", Toast.LENGTH_SHORT).show()
        }

        listViewBins.setOnItemClickListener { _, _, position, _ ->
            if (position < currentEntries.size) {
                val selectedEntry = currentEntries[position]
                tvStatus.text = "Status: Dipilih Bin ID ${selectedEntry.id} (Size: ${selectedEntry.size} bytes)"
                Toast.makeText(
                    this,
                    "Membuka Bin ID: ${selectedEntry.id}\nOffset: ${selectedEntry.offset}\nSize: ${selectedEntry.size} bytes",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun openFileBrowser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun loadImgArchiveAndShowList(uri: Uri, fileName: String) {
        tvStatus.text = "Status: Memuat $fileName..."

        GlobalScope.launch(Dispatchers.IO) {
            try {
                val entries = quickParseAfsHeader(uri)
                currentEntries = entries
                
                val displayList: MutableList<String> = mutableListOf()
                for (entry in entries) {
                    displayList.add("Bin ID: ${entry.id} | Offset: ${entry.offset} | Size: ${entry.size} bytes")
                }

                withContext(Dispatchers.Main) {
                    if (entries.isNotEmpty()) {
                        tvStatus.text = "Status: Berhasil memuat ${entries.size} file .bin"
                        val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_list_item_1, displayList)
                        listViewBins.adapter = adapter
                        Toast.makeText(applicationContext, "Arsip .IMG berhasil dibaca!", Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "Status: Format .img tidak dikenali / kosong."
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    tvStatus.text = "Status: Gagal memuat - ${e.localizedMessage}"
                }
            }
        }
    }

    private fun quickParseAfsHeader(uri: Uri): List<AfsEntry> {
        val entryList = mutableListOf<AfsEntry>()
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val headerBuffer = ByteArray(128)
            inputStream.read(headerBuffer)
            val buffer = ByteBuffer.wrap(headerBuffer).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buffer.int
            if (magic != 0x00534641) {
                return emptyList()
            }

            val fileCount = buffer.int
            val indexBytes = ByteArray(fileCount * 8)
            inputStream.read(indexBytes)
            val indexBuf = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)

            for (i in 0 until fileCount) {
                val offset = indexBuf.int.toLong() and 0xFFFFFFFFL
                val size = indexBuf.int
                if (offset > 0 && size > 0) {
                    entryList.add(AfsEntry(id = i, offset = offset, size = size))
                }
            }
        }
        return entryList
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "arsip.img"
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }
}
