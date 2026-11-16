package com.pes2013.imgpatcher

import android.os.Bundle
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var listViewBins: ListView
    private lateinit var btnOpenImg: Button
    private lateinit var btnRebuildImg: Button
    private lateinit var btnOpenEditor: Button
    private lateinit var btnDdsInspector: Button
    private lateinit var btnFaceInspector: Button

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
            Toast.makeText(this, "Membuka arsip .IMG...", Toast.LENGTH_SHORT).show()
            tvStatus.text = "Status: Arsip dimuat."
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
}
