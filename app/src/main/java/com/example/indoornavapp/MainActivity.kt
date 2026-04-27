package com.example.indoornavapp

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<LinearLayout>

    private var is3DMode = false
    private var selectedFloor: Int? = null
    
    // New UI Elements
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    
    // Repository to load data
    private val repository = MapRepository()
    // Path finding algorithm
    private val pathFinder = PathFinder()
    private val qrProvider = QrLocationProvider()
    private val pdrProvider = PdrLocationProvider()
    private val fusionProvider = FusionLocationProvider(listOf(qrProvider, pdrProvider))
    
    private var currentGraph: Graph? = null
    private var currentPath: List<Node>? = null
    private var currentLocation: IndoorLocation? = null
    private var lastReplanTimeMs: Long = 0L

    private val locationListener = LocationListener { location ->
        currentLocation = location
        val graph = currentGraph ?: return@LocationListener
        val node = graph.nodes[location.nodeId]
        runOnUiThread {
            mapView.setCurrentLocation(node)
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
        
        spinnerStrategy = findViewById(R.id.spinner_strategy)
        cbAccessible = findViewById(R.id.cb_accessible)
        btnNavigate = findViewById(R.id.btn_navigate)
        tvDistance = findViewById(R.id.tv_distance)
        tvTime = findViewById(R.id.tv_time)
        btnBack = findViewById(R.id.btn_back)
        actvSearch = findViewById(R.id.actv_search)
        btnClearSearch = findViewById(R.id.btn_clear_search)

        val bottomSheetLayout = findViewById<LinearLayout>(R.id.bottom_sheet_layout)
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheetLayout)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

        qrProvider.addListener(locationListener)

        fusionProvider.start()
        
        // Listeners
        btnNavigate.setOnClickListener { onNavigateClicked() }
        btnBack.setOnClickListener { finish() } // Return to HomeActivity

        fabArMode.setOnClickListener {
            val intent = android.content.Intent(this, ArNavigationActivity::class.java)
            currentPath?.let { path ->
                intent.putExtra("PATH_NODE_IDS", path.map { it.id }.toTypedArray())
            }
            val destId = spinnerEnd.selectedItem as? String
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

        // Search bar
        setupSearchBar()

        // Load map data asynchronously
        loadMapData()
    }

    override fun onDestroy() {
        super.onDestroy()
        fusionProvider.removeListener(locationListener)
        fusionProvider.stop()
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
            } else {
                spinnerStart.setSelection(0)
                qrProvider.updateFromNode(graph, nodeIds[0])
            }

            if (nodeIds.size > 1) {
                spinnerEnd.setSelection(1)
            }
        }

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

    private fun onNavigateClicked() {
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
            btnNavigate.text = "In Progress"
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            
            // Show AR Mode Fab when navigation path is found.
            fabArMode.visibility = android.view.View.VISIBLE

            val currentNodeId = currentLocation?.nodeId
            if (currentNodeId != null && graph.nodes.containsKey(currentNodeId)) {
                val position = (spinnerStart.adapter as ArrayAdapter<String>).getPosition(currentNodeId)
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
