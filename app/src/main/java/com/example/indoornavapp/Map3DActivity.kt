package com.example.indoornavapp

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.indoornavapp.repository.MapRepository
import com.example.indoornavapp.ui.view.Building3DRenderer
import com.example.indoornavapp.ui.view.LabelOverlayView
import kotlin.math.sqrt

class Map3DActivity : AppCompatActivity() {

    private lateinit var glView: GLSurfaceView
    private lateinit var labelOverlay: LabelOverlayView
    private lateinit var renderer: Building3DRenderer

    private var prevX = 0f
    private var prevY = 0f
    private var prevDist = 0f

    private val floorButtons = mutableMapOf<Int, TextView>()
    private val accentColor = Color.parseColor("#1D4ED8")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_map_3d)

        val graph = MapRepository().loadMap(this)
        val pathIds = intent.getStringArrayExtra("PATH_NODE_IDS")?.toList()

        renderer = Building3DRenderer(graph, pathIds)
        renderer.onLabelsUpdated = { labels ->
            runOnUiThread {
                labelOverlay.labels = labels
                labelOverlay.invalidate()
            }
        }

        glView = findViewById(R.id.gl_surface)
        glView.setEGLContextClientVersion(2)
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        labelOverlay = findViewById(R.id.label_overlay)

        findViewById<View>(R.id.btn_back_3d).setOnClickListener { finish() }

        setupFloorSelector()
        setupTouch()
    }

    // ─── Floor selector ───────────────────────────────

    private fun setupFloorSelector() {
        val container = findViewById<LinearLayout>(R.id.floor_selector)
        val floors = renderer.availableFloors
        if (floors.size <= 1) { container.visibility = View.GONE; return }

        val dp = resources.displayMetrics.density
        val size = (38 * dp).toInt()
        val margin = (3 * dp).toInt()
        val radius = 10 * dp

        // "All" button (all floors)
        addFloorChip(container, "All", -1, size, margin, radius)
        for (f in floors.sortedDescending()) {
            addFloorChip(container, "F$f", f, size, margin, radius)
        }
        updateFloorChipStyles()
    }

    private fun addFloorChip(
        container: LinearLayout, text: String, floor: Int,
        size: Int, margin: Int, radius: Float
    ) {
        val tv = TextView(this).apply {
            this.text = text
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(margin, margin, margin, margin)
            }
            setOnClickListener {
                renderer.selectedFloor = floor
                updateFloorChipStyles()
            }
        }
        tv.tag = radius  // store for drawable creation
        floorButtons[floor] = tv
        container.addView(tv)
    }

    private fun updateFloorChipStyles() {
        val sel = renderer.selectedFloor
        val dp = resources.displayMetrics.density
        val radius = 10 * dp
        for ((floor, tv) in floorButtons) {
            if (floor == sel) {
                tv.background = roundRect(accentColor, radius)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.background = roundRect(Color.WHITE, radius)
                tv.setTextColor(Color.DKGRAY)
            }
        }
    }

    private fun roundRect(color: Int, radius: Float): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(color)
        }
    }

    // ─── Touch ────────────────────────────────────────

    private fun setupTouch() {
        glView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    prevX = ev.x; prevY = ev.y
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (ev.pointerCount == 2) prevDist = dist(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (ev.pointerCount == 1) {
                        val dx = ev.x - prevX
                        val dy = ev.y - prevY
                        renderer.rotY += dx * 0.3f
                        renderer.rotX = (renderer.rotX + dy * 0.3f).coerceIn(5f, 85f)
                    } else if (ev.pointerCount == 2) {
                        val d = dist(ev)
                        if (prevDist > 0) {
                            renderer.zoom *= prevDist / d
                            renderer.zoom = renderer.zoom.coerceIn(15f, 300f)
                        }
                        prevDist = d
                    }
                    prevX = ev.x; prevY = ev.y
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                    prevDist = 0f
                }
            }
            true
        }
    }

    private fun dist(ev: MotionEvent): Float {
        val dx = ev.getX(0) - ev.getX(1)
        val dy = ev.getY(0) - ev.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    override fun onResume() { super.onResume(); if (::glView.isInitialized) glView.onResume() }
    override fun onPause() { super.onPause(); if (::glView.isInitialized) glView.onPause() }
}
