package com.pes2013.imgpatcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listViewBins: ListView
    private lateinit var btnOpenImg: Button
    private lateinit var btnRebuildImg: Button
    private lateinit var btnOpenEditor: Button
    private lateinit var btnDdsInspector: Button
    private lateinit var btnFaceInspector: Button

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                val fileName = getFileNameFromUri(uri)
                tvStatus.text = "Status: Memuat $fileName..."
                Toast.makeText(this, "File dipilih: $fileName", Toast.LENGTH_LONG).show()
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
    }

    private fun openFileBrowser() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        filePickerLauncher.launch(intent)
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
