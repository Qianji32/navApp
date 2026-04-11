package com.example.indoornavapp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.journeyapps.barcodescanner.BarcodeEncoder
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter

class QrGeneratorActivity : AppCompatActivity() {

    // Curated QR positioning points: entrances, stairs, and key rooms per floor
    private data class QrPoint(val nodeId: String, val displayName: String)

    private val qrPoints = listOf(
        // 1F — Entrances & vertical transport
        QrPoint("F1_ENTRANCE",  "1F  Main Entrance"),
        QrPoint("F1_EXIT_W",    "1F  West Exit"),
        QrPoint("F1_D_LOBBY",   "1F  Lobby / Service Desk"),
        QrPoint("STAIR_C",      "1F  Central Staircase"),
        QrPoint("STAIR_N",      "1F  North Staircase"),
        // 2F — Classrooms & book stack
        QrPoint("F2_D_NEWBOOK",       "2F  New Books Room"),
        QrPoint("F2_D_BOOKSTORE",     "2F  Book Stack"),
        QrPoint("F2_D_CLASSROOM_E",   "2F  Classroom (East)"),
        QrPoint("F2_D_CLASSROOM_W",   "2F  Classroom (West)"),
        // 3F — Reading rooms
        QrPoint("F3_D_SCIENCE",       "3F  Science Reading"),
        QrPoint("F3_D_HUMANITY",      "3F  Humanities Reading"),
        QrPoint("F3_D_FOREIGN",       "3F  Foreign Lang. Reading"),
        QrPoint("F3_D_PERIODICAL",    "3F  Periodicals Room"),
        // 4F — Study & special rooms
        QrPoint("F4_D_STUDY_E",  "4F  Study Room (East)"),
        QrPoint("F4_D_STUDY_W",  "4F  Study Room (West)"),
        QrPoint("F4_D_EREAD",    "4F  E-Reading Room"),
        QrPoint("F4_D_TRAIN",    "4F  Training Room"),
        QrPoint("F4_D_DIGITAL",  "4F  Digital Collection"),
    )

    private lateinit var spinnerNodes: Spinner
    private lateinit var btnGenerate: Button
    private lateinit var ivQrCode: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_generator)

        spinnerNodes = findViewById(R.id.spinner_nodes)
        btnGenerate = findViewById(R.id.btn_generate)
        ivQrCode = findViewById(R.id.iv_qr_code)

        val displayNames = qrPoints.map { it.displayName }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerNodes.adapter = adapter

        btnGenerate.setOnClickListener {
            val pos = spinnerNodes.selectedItemPosition
            if (pos in qrPoints.indices) {
                val point = qrPoints[pos]
                generateQrCode(point.nodeId)
                Toast.makeText(this, "QR for node: ${point.nodeId}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun generateQrCode(text: String) {
        try {
            val bitMatrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 800, 800)
            val bitmap = BarcodeEncoder().createBitmap(bitMatrix)
            ivQrCode.setImageBitmap(bitmap)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to generate QR Code", Toast.LENGTH_SHORT).show()
        }
    }
}