package com.example.indoornavapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.indoornavapp.algo.PathFinder
import com.example.indoornavapp.algo.RouteOptions
import com.example.indoornavapp.algo.PathResult
import com.example.indoornavapp.location.IndoorLocation
import com.example.indoornavapp.location.LocationListener
import com.example.indoornavapp.location.QrLocationProvider
import com.example.indoornavapp.location.PdrLocationProvider
import com.example.indoornavapp.location.FusionLocationProvider
import com.example.indoornavapp.model.Graph
import com.example.indoornavapp.model.Node
import com.example.indoornavapp.repository.MapRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Camera-based navigation with UI overlay.
 * This is a lightweight AR alternative that works without ARCore.
 * 
 * Features:
 * - Camera preview as background
 * - Direction arrow overlay
 * - Real-time position display
 * - Path guidance text
 */
class CameraNavigationActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var tvStatus: TextView
    private lateinit var tvDestination: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvEta: TextView
    private lateinit var tvDirection: TextView
    private lateinit var tvInstruction: TextView
    private lateinit var cardNavInfo: View
    private lateinit var btnScanQr: View
    private lateinit var btnRefresh: View  // 新增刷新按钮

    // Location providers
    private val qrProvider = QrLocationProvider()
    private val pdrProvider by lazy { PdrLocationProvider(this) }
    private val fusionProvider by lazy { FusionLocationProvider(listOf(qrProvider, pdrProvider)) }
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private var accelerometerValues: FloatArray? = null
    private var magneticValues: FloatArray? = null
    private var deviceAzimuthDeg: Float = 0f
    
    private var graph: Graph? = null
    private var currentLocation: IndoorLocation? = null
    private var destinationNode: Node? = null
    private var pathNodeIds: List<String>? = null
    private var pathResult: PathResult? = null

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result: ScanIntentResult ->
        if (result.contents != null) {
            onQrScanned(result.contents)
        } else {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val activityRecognitionPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        fusionProvider.stop()
        fusionProvider.start()
    }

    private val locationListener = LocationListener { location ->
        currentLocation = location
        runOnUiThread { updateNavUI(location) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_navigation)

        initViews()
        setupClickListeners()
        loadMapData()
        checkCameraPermission()
        
        fusionProvider.addListener(locationListener)
        fusionProvider.start()
        requestActivityRecognitionPermissionIfNeeded()
    }

    private fun initViews() {
        previewView = findViewById(R.id.previewView)
        tvStatus = findViewById(R.id.tv_tracking_status)
        tvDestination = findViewById(R.id.tv_destination)
        tvDistance = findViewById(R.id.tv_ar_distance)
        tvPosition = findViewById(R.id.tv_ar_position)
        tvEta = findViewById(R.id.tv_ar_eta)
        tvDirection = findViewById(R.id.tv_direction_arrow)
        tvDirection.text = "\u2191"
        tvInstruction = findViewById(R.id.tv_nav_instruction)
        cardNavInfo = findViewById(R.id.card_nav_info)
        btnScanQr = findViewById(R.id.btn_scan_qr)
        btnRefresh = findViewById(R.id.btn_refresh_route)
    }

    private fun setupClickListeners() {
        findViewById<FloatingActionButton>(R.id.fab_back).setOnClickListener { finish() }
        btnScanQr.setOnClickListener { launchQrScanner() }
        btnRefresh.setOnClickListener { refreshRoute() }
    }

    // 刷新路线
    private fun refreshRoute() {
        // 重新从intent获取最新的起点和终点
        val newPathIds = intent.getStringArrayExtra("PATH_NODE_IDS")?.toList()
        if (newPathIds != null && newPathIds.size >= 2) {
            pathNodeIds = newPathIds
            val endId = newPathIds.last()
            destinationNode = graph?.nodes?.get(endId)
            
            // 重新计算路径
            val startId = newPathIds.first()
            pathResult = graph?.let { PathFinder().findPathResult(it, startId, endId, RouteOptions()) }
            
            // 更新UI
            if (destinationNode != null) {
                tvDestination.text = "→ ${destinationNode!!.label ?: destinationNode!!.id}"
            }
            
            // 更新距离和ETA
            pathResult?.let { result ->
                tvDistance.text = String.format("%.1fm", result.totalDistance)
                tvEta.text = if (result.estimatedTimeMinutes < 1) "<1min" else "${result.estimatedTimeMinutes}min"
            }
            
            Toast.makeText(this, "Route updated", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No route to update", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadMapData() {
        val repo = MapRepository()
        graph = repo.loadMap(this)

        // Read path + destination from intent
        pathNodeIds = intent.getStringArrayExtra("PATH_NODE_IDS")?.toList()
        val destId = intent.getStringExtra("DEST_NODE_ID")
        
        if (destId != null) {
            destinationNode = graph?.nodes?.get(destId)
        } else if (pathNodeIds != null && pathNodeIds!!.isNotEmpty()) {
            destinationNode = graph?.nodes?.get(pathNodeIds!!.last())
        }

        // Calculate path if we have both start and end
        if (pathNodeIds != null && pathNodeIds!!.size >= 2) {
            val startId = pathNodeIds!!.first()
            val endId = pathNodeIds!!.last()
            pathResult = PathFinder().findPathResult(graph!!, startId, endId, RouteOptions())
        }

        // Show nav info card
        cardNavInfo.visibility = View.VISIBLE
        if (destinationNode != null) {
            tvDestination.text = "→ ${destinationNode!!.label ?: destinationNode!!.id}"
        } else {
            tvDestination.text = "Camera Positioning Mode"
        }

        tvStatus.text = "Camera Mode - Scan QR to start"
        tvStatus.setTextColor(0xFF4CAF50.toInt())
        
        // Show direction arrow with animation
        tvDirection.visibility = View.VISIBLE
        startArrowAnimation()
    }

    // Arrow blinking animation
    private fun startArrowAnimation() {
        val blinkAnimation = AlphaAnimation(1.0f, 0.3f).apply {
            duration = 800
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        tvDirection.startAnimation(blinkAnimation)
    }

    override fun onResume() {
        super.onResume()
        registerOrientationSensors()
    }

    override fun onPause() {
        super.onPause()
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.unregisterListener(this)
    }

    private fun registerOrientationSensors() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        } ?: run {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                startCamera()
            }
            else -> {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun requestActivityRecognitionPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        ) return

        activityRecognitionPermissionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            val preview = Preview.Builder()
                .build()
                .also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
            
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview)
            } catch (e: Exception) {
                Toast.makeText(this, "Camera failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan a location QR code")
            setCameraId(0)
            setBeepEnabled(false)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun onQrScanned(content: String) {
        try {
            val g = graph ?: return
            val nodeId = content.split("|").firstOrNull()?.trim().orEmpty()
            val node = g.nodes[nodeId]
            if (node == null) {
                Toast.makeText(this, "Unknown QR node: $nodeId", Toast.LENGTH_SHORT).show()
                return
            }

            qrProvider.updateFromNode(g, node.id)
            pdrProvider.resetToNode(g, node.id)
            destinationNode?.let { dest ->
                pathResult = PathFinder().findPathResult(g, node.id, dest.id, RouteOptions())
            }

            tvStatus.text = "Calibrated at ${node.label ?: node.id}"
            tvStatus.setTextColor(0xFF4CAF50.toInt())
            tvInstruction.text = "Walk forward"

            Toast.makeText(this, "Location set: ${node.label ?: node.id}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "QR parse error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNavUI(location: IndoorLocation) {
        // Update position display
        tvPosition.text = String.format("(%.1f, %.1f) F%d", location.x, location.y, location.floor)
        
        // Update distance if we have a destination
        destinationNode?.let { dest ->
            val target = getGuidanceTarget(location) ?: dest
            val distance = calculateDistance(location.x, location.y, dest.x, dest.y)
            tvDistance.text = String.format("%.1fm", distance)
            
            // Update ETA (assuming 1m/s walking speed)
            val etaMinutes = (distance / 1.0 / 60).toInt()
            tvEta.text = if (etaMinutes < 1) "<1min" else "${etaMinutes}min"
            
            // Update direction arrow
            updateDirectionArrow(location, target)
            
            // Update instruction text
            updateInstructionText(distance, location, target)
        }
    }

    private fun getGuidanceTarget(location: IndoorLocation): Node? {
        val path = pathResult?.path ?: return destinationNode
        return path.firstOrNull { node ->
            node.floor == location.floor && calculateDistance(location.x, location.y, node.x, node.y) > 3.0
        } ?: destinationNode
    }

    private fun updateDirectionArrow(current: IndoorLocation, target: Node) {
        val dx = target.x - current.x
        val dy = target.y - current.y
        
        val targetBearingDeg = Math.toDegrees(atan2(dx, -dy)).toFloat()
        val angle = normalizeDegrees(targetBearingDeg - deviceAzimuthDeg)
        
        tvDirection.rotation = angle
        
        // Change arrow color based on proximity
        val distance = sqrt(dx * dx + dy * dy)
        val color = when {
            distance < 2 -> 0xFF4CAF50.toInt() // Green - close
            distance < 10 -> 0xFFFFCC00.toInt() // Yellow - medium
            else -> 0xFFFFFFFF.toInt() // White - far
        }
        tvDirection.setTextColor(color)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                updateDeviceAzimuth()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.clone()
                updateFallbackOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticValues = event.values.clone()
                updateFallbackOrientation()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateFallbackOrientation() {
        val accelerometer = accelerometerValues ?: return
        val magnetic = magneticValues ?: return
        if (SensorManager.getRotationMatrix(rotationMatrix, null, accelerometer, magnetic)) {
            SensorManager.getOrientation(rotationMatrix, orientationAngles)
            updateDeviceAzimuth()
        }
    }

    private fun updateDeviceAzimuth() {
        deviceAzimuthDeg = normalizeDegrees(Math.toDegrees(orientationAngles[0].toDouble()).toFloat())
        val location = currentLocation ?: return
        val target = getGuidanceTarget(location) ?: return
        updateDirectionArrow(location, target)
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized < -180f) normalized += 360f
        if (normalized > 180f) normalized -= 360f
        return normalized
    }

    private fun updateInstructionText(distance: Double, current: IndoorLocation, target: Node) {
        val instruction = when {
            distance < 1 -> "You have arrived!"
            distance < 3 -> "Destination is nearby"
            current.floor != target.floor -> {
                if (current.floor < target.floor) "Take the elevator up" else "Take the elevator down"
            }
            else -> {
                val dx = target.x - current.x
                val dy = target.y - current.y
                when {
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) * 2 -> if (dx > 0) "Turn right" else "Turn left"
                    kotlin.math.abs(dy) > kotlin.math.abs(dx) * 2 -> if (dy > 0) "Go forward" else "Go backward"
                    else -> "Walk straight"
                }
            }
        }
        tvInstruction.text = instruction
    }

    private fun calculateDistance(x1: Double, y1: Double, x2: Double, y2: Double): Double {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }

    override fun onDestroy() {
        super.onDestroy()
        fusionProvider.removeListener(locationListener)
        fusionProvider.stop()
    }
}
