package com.example.indoornavapp.ui.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.example.indoornavapp.model.Graph
import com.example.indoornavapp.model.Node
import kotlin.math.min

class IndoorMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val pathPaint = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 8f
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val nodePaint = Paint().apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        alpha = 100 // Semi-transparent
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private var currentGraph: Graph? = null
    private var currentPath: List<Node>? = null
    private var currentFloor: Int? = null
    private var currentLocationNode: Node? = null

    // Continuous location (from VIO / non-node-snapped sources)
    private var continuousX: Double? = null
    private var continuousY: Double? = null
    private var continuousFloor: Int? = null

    // Highlighted node (from search)
    private var highlightNodeId: String? = null

    // Map data coordinate range (Logic coordinates, matching max X/Y in JSON)
    private var mapWidthLogic = 95.0 // BUPT Library: X range 0-85 + padding
    private var mapHeightLogic = 65.0 // BUPT Library: Y range 0-55 + padding

    // Transformation properties
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    
    private var tiltAngle = 0f // 0 for 2D, e.g. 50f for 3D

    fun setMapMode3D(is3D: Boolean) {
        tiltAngle = if (is3D) 55f else 0f
        invalidate()
    }

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            // Don't let the object get too small or too large.
            scaleFactor = Math.max(0.5f, Math.min(scaleFactor, 5.0f))
            invalidate()
            return true
        }
    })

    /**
     * Set the graph data to be visualized (mainly for debugging nodes).
     */
    fun setGraph(graph: Graph) {
        this.currentGraph = graph
        
        // Dynamically calculate map boundaries based on node coordinates
        var maxX = 0.0
        var maxY = 0.0
        graph.nodes.values.forEach { node ->
            if (node.x > maxX) maxX = node.x
            if (node.y > maxY) maxY = node.y
        }
        // Add a little padding to the logic map bounds
        mapWidthLogic = if (maxX > 0) maxX * 1.1 else 100.0
        mapHeightLogic = if (maxY > 0) maxY * 1.2 else 100.0
        
        invalidate() // Trigger redraw
    }

    /**
     * Set the path to be drawn on the map.
     */
    fun setPath(path: List<Node>?) {
        this.currentPath = path
        if (path != null && path.isNotEmpty() && currentFloor == null) {
            currentFloor = path.first().floor
        }
        invalidate() // Trigger redraw
    }

    fun setCurrentFloor(floor: Int?) {
        currentFloor = floor
        invalidate()
    }

    fun setCurrentLocation(node: Node?) {
        currentLocationNode = node
        continuousX = null
        continuousY = null
        continuousFloor = null
        if (node != null && currentFloor == null) {
            currentFloor = node.floor
        }
        invalidate()
    }

    /**
     * Set current location using continuous coordinates (e.g. from VIO provider).
     * Unlike [setCurrentLocation], this does not require a node — just raw x,y,floor.
     */
    fun setCurrentLocationXY(x: Double, y: Double, floor: Int) {
        continuousX = x
        continuousY = y
        continuousFloor = floor
        currentLocationNode = null
        if (currentFloor == null) {
            currentFloor = floor
        }
        invalidate()
    }

    /**
     * Highlight a specific node (e.g. from search). Pass null to clear.
     */
    fun highlightNode(nodeId: String?) {
        highlightNodeId = nodeId
        invalidate()
    }

    fun recenter() {
        val locX: Double
        val locY: Double
        if (currentLocationNode != null) {
            locX = currentLocationNode!!.x
            locY = currentLocationNode!!.y
        } else if (continuousX != null && continuousY != null) {
            locX = continuousX!!
            locY = continuousY!!
        } else return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        val scaleX = viewWidth / mapWidthLogic.toFloat()
        val scaleY = viewHeight / mapHeightLogic.toFloat()

        val nodeX = (locX * scaleX).toFloat()
        val nodeY = (locY * scaleY).toFloat()

        translateX = (viewWidth / 2f / scaleFactor) - nodeX
        translateY = (viewHeight / 2f / scaleFactor) - nodeY
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 1. Draw the background image (handled by AppCompatImageView super.onDraw if src is set)
        super.onDraw(canvas)

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()

        if (viewWidth == 0f || viewHeight == 0f) return

        canvas.save()
        
        // --- 2.5D Isometric Transform (Tilt) ---
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        
        if (tiltAngle > 0) {
            val camera = android.graphics.Camera()
            val matrix = android.graphics.Matrix()
            camera.save()
            camera.rotateX(tiltAngle)
            // Optional: camera.translate(0f, 0f, zoomZ) for perspective scaling
            camera.getMatrix(matrix)
            camera.restore()
            
            // Center the projection
            matrix.preTranslate(-centerX, -centerY)
            matrix.postTranslate(centerX, centerY)
            
            canvas.concat(matrix)
        }
        
        // Application of Zoom & Pan
        canvas.translate(translateX, translateY)
        canvas.scale(scaleFactor, scaleFactor)

        val scaleX = viewWidth / mapWidthLogic.toFloat()
        val scaleY = viewHeight / mapHeightLogic.toFloat()

        // 2. Draw all nodes (Debugging or interactive spots)
        val floorFilter = currentFloor
        // Collision detection: keep track of drawn label bounding rects
        val drawnLabelRects = mutableListOf<RectF>()

        currentGraph?.let { graph ->
            val nodeRadius = 8f / scaleFactor // Keep node size visually consistent when zoomed
            textPaint.textSize = 24f / scaleFactor // Keep text size readable and consistent
            
            val highlightPaint = Paint().apply {
                color = Color.parseColor("#EF4444")
                style = Paint.Style.STROKE
                strokeWidth = 4f / scaleFactor
                isAntiAlias = true
            }
            
            // Reuse variables inside loop
            var screenX: Float
            var screenY: Float
            
            // Sort: draw highlighted node last so it's on top
            val sortedNodes = graph.nodes.values.sortedBy { it.id == highlightNodeId }
            
            for (node in sortedNodes) {
                if (floorFilter != null && node.floor != floorFilter) continue

                screenX = (node.x * scaleX).toFloat()
                screenY = (node.y * scaleY).toFloat()
                
                // Change color based on type
                when (node.type) {
                    "stair" -> nodePaint.color = Color.MAGENTA
                    "elevator" -> nodePaint.color = Color.CYAN
                    "entrance" -> nodePaint.color = Color.GREEN
                    "room-door" -> nodePaint.color = Color.parseColor("#FFA500") // Orange
                    else -> nodePaint.color = Color.BLUE
                }
                
                canvas.drawCircle(screenX, screenY, nodeRadius, nodePaint)

                // Draw highlight ring on search-selected node
                if (node.id == highlightNodeId) {
                    canvas.drawCircle(screenX, screenY, nodeRadius * 2.5f, highlightPaint)
                }
                
                // Draw label with collision avoidance
                val labelText = node.label
                if (labelText != null) {
                    val labelWidth = textPaint.measureText(labelText)
                    val labelHeight = textPaint.textSize
                    val labelY = screenY - nodeRadius - (4f / scaleFactor)
                    val labelRect = RectF(
                        screenX - labelWidth / 2f,
                        labelY - labelHeight,
                        screenX + labelWidth / 2f,
                        labelY
                    )

                    // Always draw highlighted node label; otherwise check overlap
                    val forceShow = node.id == highlightNodeId
                    val overlaps = !forceShow && drawnLabelRects.any { RectF.intersects(it, labelRect) }

                    if (!overlaps) {
                        canvas.drawText(labelText, screenX, labelY, textPaint)
                        drawnLabelRects.add(labelRect)
                    }
                }
            }
        }

        // 3. Draw the navigation path
        currentPath?.let { pathNodes ->
            if (pathNodes.size < 2) return

            pathPaint.strokeWidth = 8f / scaleFactor // Keep path width visually consistent when zoomed
            val visiblePath = if (floorFilter == null) {
                pathNodes
            } else {
                pathNodes.filter { it.floor == floorFilter }
            }

            if (visiblePath.size < 2) return

            val drawPath = android.graphics.Path()
            val startNode = visiblePath[0]
            
            // Move to first point
            val startX = (startNode.x * scaleX).toFloat()
            val startY = (startNode.y * scaleY).toFloat()
            drawPath.moveTo(startX, startY)

            // Line to subsequent points
            for (i in 1 until visiblePath.size) {
                val node = visiblePath[i]
                val nextX = (node.x * scaleX).toFloat()
                val nextY = (node.y * scaleY).toFloat()
                drawPath.lineTo(nextX, nextY)
            }

            canvas.drawPath(drawPath, pathPaint)
        }

        currentLocationNode?.let { node ->
            if (floorFilter == null || node.floor == floorFilter) {
                val cx = (node.x * scaleX).toFloat()
                val cy = (node.y * scaleY).toFloat()
                val radius = min(12f, 14f / scaleFactor)

                val fill = Paint().apply {
                    color = Color.parseColor("#10B981")
                    style = Paint.Style.FILL
                    isAntiAlias = true
                }
                val border = Paint().apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 3f / scaleFactor
                    isAntiAlias = true
                }

                canvas.drawCircle(cx, cy, radius, fill)
                canvas.drawCircle(cx, cy, radius, border)
            }
        }

        // Draw continuous location (VIO)
        val cX = continuousX
        val cY = continuousY
        val cF = continuousFloor
        if (cX != null && cY != null && cF != null &&
            (floorFilter == null || cF == floorFilter)) {
            val cx = (cX * scaleX).toFloat()
            val cy = (cY * scaleY).toFloat()
            val radius = min(12f, 14f / scaleFactor)

            val fill = Paint().apply {
                color = Color.parseColor("#3B82F6")
                style = Paint.Style.FILL
                isAntiAlias = true
            }
            val border = Paint().apply {
                color = Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3f / scaleFactor
                isAntiAlias = true
            }

            canvas.drawCircle(cx, cy, radius, fill)
            canvas.drawCircle(cx, cy, radius, border)
        }
        
        canvas.restore()
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    
                    translateX += dx
                    translateY += dy
                    
                    lastTouchX = event.x
                    lastTouchY = event.y
                    
                    invalidate()
                }
            }
        }
        return true
    }
    
    /**
     * Helper to convert screen touch coordinates to logical map coordinates.
     * Useful for implementing touch interaction/selecting nodes.
     */
    fun screenToLogicCoords(screenX: Float, screenY: Float): Pair<Double, Double> {
        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        
        if (viewWidth == 0f || viewHeight == 0f) return Pair(0.0, 0.0)

        val logicX = (screenX / viewWidth) * mapWidthLogic
        val logicY = (screenY / viewHeight) * mapHeightLogic
        
        return Pair(logicX, logicY)
    }
}
