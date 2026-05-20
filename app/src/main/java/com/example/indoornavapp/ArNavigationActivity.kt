package com.example.indoornavapp

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.indoornavapp.algo.PathFinder
import com.example.indoornavapp.algo.PathResult
import com.example.indoornavapp.algo.RouteOptions
import com.example.indoornavapp.location.IndoorLocation
import com.example.indoornavapp.location.LocationListener
import com.example.indoornavapp.location.VioLocationProvider
import com.example.indoornavapp.model.Graph
import com.example.indoornavapp.model.Node
import com.example.indoornavapp.repository.MapRepository
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanIntentResult
import com.journeyapps.barcodescanner.ScanOptions
import io.github.sceneview.ar.ARSceneView
import kotlin.math.atan2
import kotlin.math.sqrt

class ArNavigationActivity : AppCompatActivity() {

    private lateinit var sceneView: ARSceneView
    private lateinit var tvStatus: TextView
    private lateinit var tvDestination: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvPosition: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvDirection: TextView
    private lateinit var cardNavInfo: View
    private lateinit var btnScanQr: View
    private lateinit var tvScanBtn: TextView

    private val vioProvider = VioLocationProvider()
    private var graph: Graph? = null
    private var currentLocation: IndoorLocation? = null
    private var destinationNode: Node? = null
    private var pathNodeIds: List<String>? = null
    private var pathResult: PathResult? = null
    private var latestCameraYawDeg: Float = 0f
    private var targetBearingDeg: Float = 0f

    private val barcodeLauncher = registerForActivityResult(
        ScanContract()
    ) { result: ScanIntentResult ->
        if (result.contents != null) {
            onQrScanned(result.contents)
        } else {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
        }
    }

    private val locationListener = LocationListener { location ->
        currentLocation = location
        runOnUiThread { updateNavUI(location) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ar_navigation)

        sceneView = findViewById(R.id.sceneView)
        tvStatus = findViewById(R.id.tv_tracking_status)
        tvDestination = findViewById(R.id.tv_destination)
        tvDistance = findViewById(R.id.tv_ar_distance)
        tvPosition = findViewById(R.id.tv_ar_position)
        tvSource = findViewById(R.id.tv_ar_source)
        tvDirection = findViewById(R.id.tv_direction_arrow)
        cardNavInfo = findViewById(R.id.card_nav_info)
        btnScanQr = findViewById(R.id.btn_scan_qr)
        tvScanBtn = findViewById(R.id.tv_scan_btn)

        findViewById<FloatingActionButton>(R.id.fab_back).setOnClickListener { finish() }
        btnScanQr.setOnClickListener { launchQrScanner() }

        // Load map graph
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

        // Setup VIO provider
        vioProvider.addListener(locationListener)

        // ARSceneView frame callback → feed VIO provider
        sceneView.onSessionUpdated = { session, frame ->
            val camera = frame.camera
            
            // 处理AR跟踪状态
            when (camera.trackingState) {
                com.google.ar.core.TrackingState.TRACKING -> {
                    val pose = camera.pose
                    latestCameraYawDeg = poseYawDeg(pose)
                    vioProvider.onArPoseUpdate(pose.tx(), pose.tz())
                    updateDirectionRotation()

                    if (vioProvider.isCalibrated) {
                        runOnUiThread {
                            tvStatus.text = "Tracking"
                            tvStatus.setTextColor(0xFF4CAF50.toInt())
                        }
                    } else {
                        runOnUiThread {
                            tvStatus.text = "AR Ready - Scan QR to start"
                            tvStatus.setTextColor(0xFFFFCC00.toInt())
                        }
                    }
                }
                com.google.ar.core.TrackingState.PAUSED -> {
                    runOnUiThread {
                        tvStatus.text = "Move device slowly to track"
                        tvStatus.setTextColor(0xFFFFCC00.toInt())
                    }
                }
                com.google.ar.core.TrackingState.STOPPED -> {
                    runOnUiThread {
                        tvStatus.text = "AR Unavailable"
                        tvStatus.setTextColor(0xFFFF0000.toInt())
                    }
                }
            }
        }

        // 显示方向箭头（即使未校准也能看到方向）
        tvDirection.visibility = View.VISIBLE
        tvDirection.text = "\u2191"
        tvDirection.rotation = 0f

        // Show nav info card
        cardNavInfo.visibility = View.VISIBLE
        if (destinationNode != null) {
            tvDestination.text = "To ${destinationNode!!.label ?: destinationNode!!.id}"
        } else {
            tvDestination.text = "AR Positioning Mode"
        }

        tvStatus.text = "Scan a QR code to calibrate"
        tvStatus.setTextColor(0xFFFFCC00.toInt())
    }

    override fun onDestroy() {
        super.onDestroy()
        vioProvider.removeListener(locationListener)
        vioProvider.stop()
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
            setPrompt("Scan a location QR code to set your position")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
            setOrientationLocked(false)
        }
        barcodeLauncher.launch(options)
    }

    private fun onQrScanned(content: String) {
        val g = graph ?: return
        val nodeId = content.split("|").firstOrNull()?.trim().orEmpty()
        val node = g.nodes[nodeId]
        if (node == null) {
            Toast.makeText(this, "Unknown node: $nodeId", Toast.LENGTH_SHORT).show()
            return
        }

        // Get current AR camera pose for anchor
        val session = sceneView.session ?: run {
            Toast.makeText(this, "AR session not ready", Toast.LENGTH_SHORT).show()
            return
        }
        val camera = session.update().camera
        val pose = camera.pose

        // Calibrate VIO: set this QR point as anchor
        vioProvider.calibrate(
            nodeId = node.id,
            mapX = node.x,
            mapY = node.y,
            floor = node.floor,
            arX = pose.tx(),
            arZ = pose.tz()
        )

        tvStatus.text = "Calibrated at ${node.label ?: node.id}"
        tvStatus.setTextColor(0xFF4CAF50.toInt())
        tvScanBtn.text = "Re-calibrate (Scan QR)"
        destinationNode?.let { dest ->
            pathResult = PathFinder().findPathResult(g, node.id, dest.id, RouteOptions())
        }

        Toast.makeText(this, "Position set: ${node.label ?: node.id}", Toast.LENGTH_SHORT).show()
    }

    private fun updateNavUI(location: IndoorLocation) {
        tvPosition.text = String.format("(%.1f, %.1f) F%d", location.x, location.y, location.floor)
        tvSource.text = location.source

        val dest = destinationNode
        if (dest != null) {
            val target = getGuidanceTarget(location) ?: dest
            val dx = target.x - location.x
            val dy = target.y - location.y
            val destDx = dest.x - location.x
            val destDy = dest.y - location.y
            val dist = sqrt(destDx * destDx + destDy * destDy)
            // Convert map units to approximate metres (÷ mapUnitsPerMetre)
            val distMetres = dist / vioProvider.mapUnitsPerMetre
            tvDistance.text = String.format("%.1f m", distMetres)

            // 始终显示方向箭头
            tvDirection.visibility = View.VISIBLE
            targetBearingDeg = Math.toDegrees(atan2(dx, -dy)).toFloat()
            updateDirectionRotation()
            tvDirection.text = "\u2191"
            tvDirection.setTextColor(0xFF4CAF50.toInt())

            // Arrived check (~2m)
            if (distMetres < 2.0) {
                tvDirection.text = "\u2713"
                tvDirection.rotation = 0f
                tvDirection.setTextColor(0xFF4CAF50.toInt())
                tvDestination.text = "Arrived!"
            }
        } else {
            // 无目的地时显示探索模式
            tvDirection.visibility = View.VISIBLE
            tvDirection.text = "?"
            tvDirection.rotation = 0f
            tvDirection.setTextColor(0xFFFFCC00.toInt())
        }
    }

    private fun getGuidanceTarget(location: IndoorLocation): Node? {
        val path = pathResult?.path ?: return destinationNode
        return path.firstOrNull { node ->
            node.floor == location.floor && sqrt(
                (node.x - location.x) * (node.x - location.x) +
                    (node.y - location.y) * (node.y - location.y)
            ) > 3.0
        } ?: destinationNode
    }

    private fun updateDirectionRotation() {
        runOnUiThread {
            tvDirection.rotation = normalizeDegrees(targetBearingDeg - latestCameraYawDeg)
        }
    }

    private fun poseYawDeg(pose: com.google.ar.core.Pose): Float {
        val qx = pose.qx()
        val qy = pose.qy()
        val qz = pose.qz()
        val qw = pose.qw()
        val sinyCosp = 2.0 * (qw * qy + qx * qz)
        val cosyCosp = 1.0 - 2.0 * (qy * qy + qz * qz)
        return Math.toDegrees(atan2(sinyCosp, cosyCosp)).toFloat()
    }

    private fun normalizeDegrees(value: Float): Float {
        var normalized = value % 360f
        if (normalized < -180f) normalized += 360f
        if (normalized > 180f) normalized -= 360f
        return normalized
    }
}
