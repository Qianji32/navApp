package com.example.indoornavapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.indoornavapp.algo.PathFinder
import com.example.indoornavapp.algo.RouteOptions
import com.example.indoornavapp.algo.RouteStrategy
import com.example.indoornavapp.model.Graph
import com.example.indoornavapp.model.Node
import com.example.indoornavapp.location.IndoorLocation
import com.example.indoornavapp.location.FusionLocationProvider
import com.example.indoornavapp.location.LocationListener
import com.example.indoornavapp.location.PdrLocationProvider
import com.example.indoornavapp.location.QrLocationProvider
import com.example.indoornavapp.repository.MapRepository
import com.example.indoornavapp.ui.view.IndoorMapView
import java.util.concurrent.Executors
import kotlin.math.sqrt

import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var mapView: IndoorMapView
    private lateinit var spinnerStart: Spinner
    private lateinit var spinnerEnd: Spinner
    // private lateinit var spinnerFloor: Spinner // Handled differently now
    private lateinit var spinnerStrategy: Spinner
    private lateinit var cbAccessible: CheckBox
    private lateinit var btnNavigate: Button
    private lateinit var btnReroute: Button
    private lateinit var btnBack: ImageView
    private lateinit var actvSearch: AutoCompleteTextView
    private lateinit var btnClearSearch: ImageView
    
    // Search data: display name -> node id
    private var searchEntries = mutableListOf<Pair<String, String>>()
    
    // New Commercial UI Elements
    private lateinit var layoutFloorSelector: LinearLayout
    private lateinit var fab3dToggle: FloatingActionButton
    private lateinit var fabLocate: FloatingActionButton
    private lateinit var fabArMode: ExtendedFloatingActionButton
    private lateinit var fabDemoMode: ExtendedFloatingActionButton
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>

    private var is3DMode = false
    private var selectedFloor: Int? = null
    
    // New UI Elements
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvRoutePoints: TextView
    
    // Repository to load data
    private val repository = MapRepository()
    // Path finding algorithm
    private val pathFinder = PathFinder()
    private val qrProvider = QrLocationProvider()
    private val pdrProvider by lazy { PdrLocationProvider(this) }
    private val fusionProvider by lazy { FusionLocationProvider(listOf(qrProvider, pdrProvider)) }
    
    private var currentGraph: Graph? = null
    private var currentPath: List<Node>? = null
    private var currentLocation: IndoorLocation? = null
    private var lastReplanTimeMs: Long = 0L
    private var activeStartId: String? = null
    private var activeEndId: String? = null
    private var isNavigationInProgress = false
    private var isDemoRunning = false
    private var demoFrames: List<IndoorLocation> = emptyList()
    private var demoFrameIndex = 0
    private var demoFrameDelayMs = 180L

    private val demoRunnable = object : Runnable {
        override fun run() {
            playNextDemoFrame()
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
        val graph = currentGraph ?: return@LocationListener
        val node = graph.nodes[location.nodeId]
        runOnUiThread {
            if (location.source == "QR" && node != null) {
                mapView.setCurrentLocation(node)
            } else {
                mapView.setCurrentLocationXY(location.x, location.y, location.floor)
            }
            maybeReplanForDeviation()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize Views
        mapView = findViewById(R.id.map_view)
        spinnerStart = findViewById(R.id.spinner_start)
        spinnerEnd = findViewById(R.id.spinner_end)
        
        layoutFloorSelector = findViewById(R.id.layout_floor_selector)
        fab3dToggle = findViewById(R.id.fab_3d_toggle)
        fabLocate = findViewById(R.id.fab_locate)
        fabArMode = findViewById(R.id.fab_ar_mode)
        fabDemoMode = findViewById(R.id.fab_demo_mode)
        
        spinnerStrategy = findViewById(R.id.spinner_strategy)
        cbAccessible = findViewById(R.id.cb_accessible)
        btnNavigate = findViewById(R.id.btn_navigate)
        btnReroute = findViewById(R.id.btn_reroute)
        tvDistance = findViewById(R.id.tv_distance)
        tvTime = findViewById(R.id.tv_time)
        tvRoutePoints = findViewById(R.id.tv_route_points)
        btnBack = findViewById(R.id.btn_back)
        actvSearch = findViewById(R.id.actv_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)

        val bottomSheetLayout = findViewById<LinearLayout>(R.id.bottom_sheet_layout)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        fusionProvider.addListener(locationListener)

        fusionProvider.start()
        requestActivityRecognitionPermissionIfNeeded()
        
        // Listeners
        btnNavigate.setOnClickListener {
            if (!isNavigationInProgress) {
                applySelectedRoute()
            }
        }
        btnReroute.setOnClickListener { onRerouteClicked() }
        btnBack.setOnClickListener { finish() } // Return to HomeActivity

        fabArMode.setOnClickListener {
            // Use camera navigation (works without ARCore)
            val intent = android.content.Intent(this, CameraNavigationActivity::class.java)
            currentPath?.let { path ->
                intent.putExtra("PATH_NODE_IDS", path.map { it.id }.toTypedArray())
            }
            val destId = activeEndId ?: (spinnerEnd.selectedItem as? String)
            if (destId != null) {
                intent.putExtra("DEST_NODE_ID", destId)
            }
            startActivity(intent)
        }
        
        fab3dToggle.setOnClickListener {
            val intent = android.content.Intent(this, Map3DActivity::class.java)
            // Pass current path if available
            currentPath?.let { path ->
                intent.putExtra("PATH_NODE_IDS", path.map { it.id }.toTypedArray())
            }
            startActivity(intent)
        }
        
        fabLocate.setOnClickListener {
            mapView.recenter()
            currentLocation?.floor?.let { floor -> switchFloor(floor) }
        }

        fabDemoMode.setOnClickListener {
            if (isDemoRunning) stopDemoNavigation() else startDemoNavigation()
        }

        // Search bar
        setupSearchBar()

        // Load map data asynchronously
        loadMapData()
    }

    override fun onDestroy() {
        super.onDestroy()
        mapView.removeCallbacks(demoRunnable)
        fusionProvider.removeListener(locationListener)
        fusionProvider.stop()
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

    private fun setupSearchBar() {
        // When user selects a suggestion, navigate to that node
        actvSearch.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val displayText = actvSearch.adapter.getItem(position) as? String ?: return@OnItemClickListener
            val entry = searchEntries.find { it.first == displayText } ?: return@OnItemClickListener
            val nodeId = entry.second
            val graph = currentGraph ?: return@OnItemClickListener
            val node = graph.nodes[nodeId] ?: return@OnItemClickListener

            // Switch to the node's floor
            switchFloor(node.floor)
            // Highlight the node on map
            mapView.highlightNode(nodeId)
            // Set it as destination in the End spinner
            val endAdapter = spinnerEnd.adapter
            for (i in 0 until endAdapter.count) {
                if (endAdapter.getItem(i) == nodeId) {
                    spinnerEnd.setSelection(i)
                    break
                }
            }

            // Hide keyboard
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(actvSearch.windowToken, 0)
            actvSearch.clearFocus()
            btnClearSearch.visibility = View.VISIBLE
        }

        // Show/hide clear button based on text
        actvSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            actvSearch.text.clear()
            mapView.highlightNode(null)
            btnClearSearch.visibility = View.GONE
        }
    }

    private fun loadMapData() {
        // Simple background execution using pure threads or Executors
        Executors.newSingleThreadExecutor().execute {
            try {
                // 1. Load data from assets
                val graph = repository.loadMap(this)
                
                // 2. Update UI on Main Thread
                runOnUiThread {
                    setupGraph(graph)
                    Toast.makeText(this, "Map Loaded Successfully", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Failed to load map: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupGraph(graph: Graph) {
        this.currentGraph = graph
        
        // Pass graph to MapView for debugging visualization (optional)
        mapView.setGraph(graph)
        setupFloorSpinner(graph)
        setupStrategySpinner()

        // Populate Spinners with Node IDs (Only rooms, stairs, and elevators)
        val nodeIds = graph.nodes.values
            .filter { !it.id.contains("#F") }
            .filter { it.type == "room-door" || it.type == "entrance" || it.type == "stair" || it.type == "elevator" }
            .sortedBy { it.id } // Sort alphabetically
            .map { it.id }
        
        // Convert to Array using standard array adapter layout
        val adapter = ArrayAdapter(this, R.layout.item_spinner, nodeIds)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        
        spinnerStart.adapter = adapter
        spinnerEnd.adapter = adapter

        // Set default selections if possible
        if (nodeIds.isNotEmpty()) {
            // Check if a starting node was passed from QR Scanning
            val scannedStartNode = intent.getStringExtra("START_NODE_ID")
            val startIndex = if (scannedStartNode != null) nodeIds.indexOf(scannedStartNode) else 0

            if (startIndex >= 0) {
                spinnerStart.setSelection(startIndex)
                qrProvider.updateFromNode(graph, nodeIds[startIndex])
                pdrProvider.resetToNode(graph, nodeIds[startIndex])
            } else {
                spinnerStart.setSelection(0)
                qrProvider.updateFromNode(graph, nodeIds[0])
                pdrProvider.resetToNode(graph, nodeIds[0])
            }

            if (nodeIds.size > 1) {
                spinnerEnd.setSelection(1)
            }
        }

        spinnerStart.onItemSelectedListener = SimpleItemSelectedListener { updateRouteActionState() }
        spinnerEnd.onItemSelectedListener = SimpleItemSelectedListener { updateRouteActionState() }
        cbAccessible.setOnCheckedChangeListener { _, _ -> updateRouteActionState() }
        updateRouteActionState()

        // Populate search entries for AutoCompleteTextView
        searchEntries.clear()
        graph.nodes.values
            .filter { it.type == "room-door" || it.type == "entrance" || it.type == "stair" || it.type == "elevator" }
            .sortedBy { it.id }
            .forEach { node ->
                val display = if (node.label != null) "${node.label} (${node.id})" else node.id
                searchEntries.add(display to node.id)
            }
        val searchAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, searchEntries.map { it.first })
        actvSearch.setAdapter(searchAdapter)
    }

    private fun setupFloorSpinner(graph: Graph) {
        val floors = graph.floors.sortedDescending()
        layoutFloorSelector.removeAllViews()
        val inflater = LayoutInflater.from(this)
        
        for (floor in floors) {
            val floorView = inflater.inflate(R.layout.item_floor_button, layoutFloorSelector, false)
            val tvFloor = floorView.findViewById<TextView>(R.id.tv_floor_name)
            tvFloor.text = "F$floor"
            
            // Allow storing floor ref
            floorView.tag = floor
            
            floorView.setOnClickListener { switchFloor(floor) }
            layoutFloorSelector.addView(floorView)
        }
        
        if (floors.isNotEmpty()) {
            switchFloor(floors.last()) // Default to min floor
        }
    }

    private fun switchFloor(floor: Int) {
        selectedFloor = floor
        mapView.setCurrentFloor(floor)
        
        // Update UI Highlights
        for (i in 0 until layoutFloorSelector.childCount) {
            val child = layoutFloorSelector.getChildAt(i)
            val tv = child.findViewById<TextView>(R.id.tv_floor_name)
            if (child.tag as? Int == floor) {
                tv.setBackgroundResource(R.drawable.bg_floor_selected)
                tv.setTextColor(android.graphics.Color.parseColor("#1D4ED8"))
            } else {
                tv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                tv.setTextColor(android.graphics.Color.parseColor("#6B7280"))
            }
        }
    }

    private fun setupStrategySpinner() {
        val strategies = listOf("Distance", "Elevator Preferred")
        val adapter = ArrayAdapter(this, R.layout.item_spinner, strategies)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        spinnerStrategy.adapter = adapter
        spinnerStrategy.setSelection(0)
    }

    private fun onRerouteClicked() {
        applySelectedRoute()
    }

    private fun startDemoNavigation() {
        val graph = currentGraph ?: return
        val startId = "F1_ENTRANCE"
        val endId = "F3_D_HUMANITY"
        val result = pathFinder.findPathResult(graph, startId, endId, RouteOptions())

        if (result == null) {
            Toast.makeText(this, "Demo route not found", Toast.LENGTH_SHORT).show()
            return
        }

        setSpinnerSelection(spinnerStart, startId)
        setSpinnerSelection(spinnerEnd, endId)

        currentPath = result.path
        activeStartId = startId
        activeEndId = endId
        isNavigationInProgress = true
        mapView.setPath(result.path)

        tvRoutePoints.text = "Demo: Entrance -> Humanities Reading"
        tvDistance.text = String.format("%.1f m", result.totalDistance)
        tvTime.text = "${result.estimatedTimeMinutes} min"
        fabArMode.visibility = View.VISIBLE
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        demoFrames = buildDemoFrames(result.path)
        demoFrameIndex = 0
        isDemoRunning = true
        fabDemoMode.text = "Stop"
        fabDemoMode.visibility = View.GONE
        mapView.removeCallbacks(demoRunnable)
        playNextDemoFrame()
    }

    private fun stopDemoNavigation() {
        isDemoRunning = false
        demoFrames = emptyList()
        demoFrameIndex = 0
        fabDemoMode.text = "Demo"
        fabDemoMode.visibility = View.VISIBLE
        mapView.removeCallbacks(demoRunnable)
        updateRouteActionState()
    }

    private fun playNextDemoFrame() {
        if (!isDemoRunning || demoFrameIndex >= demoFrames.size) {
            stopDemoNavigation()
            return
        }

        val location = demoFrames[demoFrameIndex++]
        currentLocation = location
        mapView.setCurrentLocationXY(location.x, location.y, location.floor)
        switchFloor(location.floor)
        tvDistance.text = String.format("%.1f m left", estimateRemainingDemoDistance(location))

        mapView.postDelayed(demoRunnable, demoFrameDelayMs)
    }

    private fun buildDemoFrames(path: List<Node>): List<IndoorLocation> {
        if (path.isEmpty()) return emptyList()
        val frames = mutableListOf<IndoorLocation>()
        var frameNo = 0

        for (i in 0 until path.size - 1) {
            val from = path[i]
            val to = path[i + 1]
            val dx = to.x - from.x
            val dy = to.y - from.y
            val steps = 8

            for (step in 0 until steps) {
                val t = step.toDouble() / steps
                val drift = kotlin.math.sin((frameNo / 5.0)) * 0.85
                val wobble = if (frameNo in 22..38 || frameNo in 58..72) drift else drift * 0.25
                val floor = if (t < 0.5) from.floor else to.floor
                frames.add(
                    IndoorLocation(
                        nodeId = from.id,
                        x = from.x + dx * t + wobble,
                        y = from.y + dy * t - wobble * 0.55,
                        floor = floor,
                        confidence = 0.58,
                        source = "Demo"
                    )
                )
                frameNo++
            }
        }

        val end = path.last()
        frames.add(
            IndoorLocation(
                nodeId = end.id,
                x = end.x,
                y = end.y,
                floor = end.floor,
                confidence = 0.95,
                source = "Demo"
            )
        )
        demoFrameDelayMs = (55_000L / frames.size.coerceAtLeast(1)).coerceAtLeast(120L)
        return frames
    }

    private fun estimateRemainingDemoDistance(location: IndoorLocation): Double {
        val destination = currentGraph?.nodes?.get("F3_D_HUMANITY") ?: return 0.0
        val dx = destination.x - location.x
        val dy = destination.y - location.y
        return sqrt(dx * dx + dy * dy)
    }

    private fun setSpinnerSelection(spinner: Spinner, nodeId: String) {
        val adapter = spinner.adapter ?: return
        for (i in 0 until adapter.count) {
            if (adapter.getItem(i) == nodeId) {
                spinner.setSelection(i)
                return
            }
        }
    }

    private fun applySelectedRoute() {
        if (isDemoRunning) stopDemoNavigation()
        val graph = currentGraph ?: return
        val startId = spinnerStart.selectedItem as? String
        val endId = spinnerEnd.selectedItem as? String

        if (startId == null || endId == null) {
            Toast.makeText(this, "Please select start and end points", Toast.LENGTH_SHORT).show()
            return
        }

        if (startId == endId) {
            Toast.makeText(this, "Current location is destination", Toast.LENGTH_SHORT).show()
            mapView.setPath(null)
            tvRoutePoints.text = formatRouteLabel(startId, endId)
            tvDistance.text = "0.0 m"
            tvTime.text = "0 min"
            currentPath = null
            activeStartId = null
            activeEndId = null
            isNavigationInProgress = false
            updateRouteActionState()
            return
        }

        val result = pathFinder.findPathResult(graph, startId, endId, readRouteOptions())
        val path = result?.path

        if (path != null) {
            mapView.setPath(path)
            currentPath = path
            activeStartId = startId
            activeEndId = endId
            isNavigationInProgress = true

            val startNode = path.first()
            currentLocation = IndoorLocation(
                nodeId = startNode.id,
                x = startNode.x,
                y = startNode.y,
                floor = startNode.floor,
                confidence = 0.65,
                source = "RouteStart"
            )
            pdrProvider.resetToNode(graph, startNode.id)
            mapView.setCurrentLocation(startNode)
            switchFloor(startNode.floor)

            tvRoutePoints.text = formatRouteLabel(startId, endId)
            tvDistance.text = String.format("%.1f m", result.totalDistance)

            val floorHint = if (result.floorTransitions.isNotEmpty()) {
                " (${result.floorTransitions.size} floor change)"
            } else {
                ""
            }
            tvTime.text = "${result.estimatedTimeMinutes} min$floorHint"

            updateRouteActionState()
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            fabArMode.visibility = View.VISIBLE
        } else {
            mapView.setPath(null)
            Toast.makeText(this, "No path found between selected points", Toast.LENGTH_SHORT).show()
            tvDistance.text = "--"
            tvTime.text = "--"
            currentPath = null
            activeStartId = null
            activeEndId = null
            isNavigationInProgress = false
            updateRouteActionState()
        }
    }

    private fun updateRouteActionState() {
        if (!::btnNavigate.isInitialized || !::btnReroute.isInitialized || !::tvRoutePoints.isInitialized) return

        val selectedStartId = spinnerStart.selectedItem as? String
        val selectedEndId = spinnerEnd.selectedItem as? String
        val hasDifferentSelection = isNavigationInProgress &&
            selectedStartId != null &&
            selectedEndId != null &&
            (selectedStartId != activeStartId || selectedEndId != activeEndId)

        if (selectedStartId != null && selectedEndId != null) {
            tvRoutePoints.text = if (hasDifferentSelection) {
                "New: ${formatRouteLabel(selectedStartId, selectedEndId)}"
            } else {
                formatRouteLabel(selectedStartId, selectedEndId)
            }
        }

        btnNavigate.text = if (isNavigationInProgress) "In Progress" else "Start"
        btnNavigate.isEnabled = !isNavigationInProgress
        btnNavigate.alpha = if (isNavigationInProgress) 0.65f else 1.0f
        btnReroute.visibility = if (hasDifferentSelection) View.VISIBLE else View.GONE
    }

    private fun formatRouteLabel(startId: String, endId: String): String {
        val graph = currentGraph
        val startLabel = graph?.nodes?.get(startId)?.let { it.label ?: it.id } ?: startId
        val endLabel = graph?.nodes?.get(endId)?.let { it.label ?: it.id } ?: endId
        return "$startLabel -> $endLabel"
    }

    @Suppress("unused", "UNCHECKED_CAST")
    private fun legacyNavigateClicked() {
        val graph = currentGraph ?: return
        
        val startId = spinnerStart.selectedItem as? String
        val endId = spinnerEnd.selectedItem as? String

        if (startId == null || endId == null) {
            Toast.makeText(this, "Please select start and end points", Toast.LENGTH_SHORT).show()
            return
        }

        if (startId == endId) {
             Toast.makeText(this, "Current location is destination", Toast.LENGTH_SHORT).show()
             mapView.setPath(null) // Clear path
             tvDistance.text = "0.0 m"
             tvTime.text = "0 min"
             return
        }
        
        // Calculate path
        val result = pathFinder.findPathResult(graph, startId, endId, readRouteOptions())
        val path = result?.path

        if (path != null) {
            // Draw path on map
            mapView.setPath(path)
            currentPath = path
            
            // Calculate total distance
            val distance = result.totalDistance
            
            // Update UI
            tvDistance.text = String.format("%.1f m", distance)
            
            val floorHint = if (result.floorTransitions.isNotEmpty()) {
                " (${result.floorTransitions.size} floor change)"
            } else {
                ""
            }
            tvTime.text = "${result.estimatedTimeMinutes} min$floorHint"

            // Change button behavior if bottom sheet is collapsed
            btnNavigate.text = "Re-route"  // 改为"重新导航"
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            
            // Show AR Mode Fab when navigation path is found.
            fabArMode.visibility = android.view.View.VISIBLE

            val currentNodeId = currentLocation?.nodeId
            if (currentNodeId != null && graph.nodes.containsKey(currentNodeId)) {
                val position = (spinnerStart.adapter as? ArrayAdapter<String>)?.getPosition(currentNodeId) ?: -1
                if (position >= 0) {
                    spinnerStart.setSelection(position)
                }
            }
            
        } else {
            mapView.setPath(null)
            Toast.makeText(this, "No path found between selected points", Toast.LENGTH_SHORT).show()
            tvDistance.text = "--"
            tvTime.text = "--"
            currentPath = null
        }
    }

    private fun readRouteOptions(): RouteOptions {
        val strategy = when (spinnerStrategy.selectedItemPosition) {
            1 -> RouteStrategy.ELEVATOR_PREFERRED
            else -> RouteStrategy.DISTANCE
        }
        return RouteOptions(
            accessibleOnly = cbAccessible.isChecked,
            strategy = strategy
        )
    }

    private fun maybeReplanForDeviation() {
        val graph = currentGraph ?: return
        val path = currentPath ?: return
        val location = currentLocation ?: return
        if (path.isEmpty()) return

        val nearestDistance = path.minOf { node ->
            val dx = node.x - location.x
            val dy = node.y - location.y
            sqrt(dx * dx + dy * dy)
        }

        val now = System.currentTimeMillis()
        val shouldReplan = nearestDistance > 12.0 && now - lastReplanTimeMs > 5000
        if (!shouldReplan) return

        val destination = spinnerEnd.selectedItem as? String ?: return
        if (!graph.nodes.containsKey(location.nodeId)) return

        val replanned = pathFinder.findPathResult(graph, location.nodeId, destination, readRouteOptions())
        if (replanned != null) {
            currentPath = replanned.path
            mapView.setPath(replanned.path)
            tvDistance.text = String.format("%.1f m", replanned.totalDistance)
            tvTime.text = "${replanned.estimatedTimeMinutes} min (replanned)"
            lastReplanTimeMs = now
            Toast.makeText(this, "Route updated due to deviation", Toast.LENGTH_SHORT).show()
        }
    }

    private fun calculatePathDistance(path: List<Node>): Double {
        var totalDistance = 0.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val dx = a.x - b.x
            val dy = a.y - b.y
            totalDistance += sqrt(dx * dx + dy * dy)
        }
        return totalDistance
    }

    private class SimpleItemSelectedListener(
        val onSelected: () -> Unit
    ) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: android.widget.AdapterView<*>?,
            view: android.view.View?,
            position: Int,
            id: Long
        ) {
            onSelected()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }
}
