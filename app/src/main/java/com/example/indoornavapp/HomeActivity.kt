package com.example.indoornavapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions

class HomeActivity : AppCompatActivity() {

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result: ScanIntentResult ->
        if (result.contents == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        } else {
            val scannedNodeId = result.contents
            Toast.makeText(this, "Located at: $scannedNodeId", Toast.LENGTH_SHORT).show()
            val intent = Intent(this, MainActivity::class.java).apply {
                putExtra("START_NODE_ID", scannedNodeId)
            }
            startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_home_new)

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true

        // Primary action: Scan QR
        findViewById<View>(R.id.card_scan).setOnClickListener { launchQrScanner() }

        // Feature cards — each leads to a distinct Activity
        findViewById<View>(R.id.card_2d_map).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.card_3d_map).setOnClickListener {
            startActivity(Intent(this, Map3DActivity::class.java))
        }
        findViewById<View>(R.id.card_ar).setOnClickListener {
            startActivity(Intent(this, ArNavigationActivity::class.java))
        }
        findViewById<View>(R.id.card_admin).setOnClickListener {
            startActivity(Intent(this, QrGeneratorActivity::class.java))
        }

        // Top bar profile button
        findViewById<View>(R.id.btn_profile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Bottom nav (3 tabs)
        findViewById<View>(R.id.nav_map).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.nav_profile).setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt("Scan a location QR Code (e.g., 'n1')")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }
}
