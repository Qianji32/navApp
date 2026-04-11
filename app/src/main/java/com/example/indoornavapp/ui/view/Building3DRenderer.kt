package com.example.indoornavapp.ui.view

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.indoornavapp.model.Graph
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Building3DRenderer(
    private val graph: Graph,
    private val pathNodeIds: List<String>? = null
) : GLSurfaceView.Renderer {

    companion object {
        private const val WALL_H = 3.0f
        private const val CORR_HW = 1.8f
        private const val ROOM_H = 2.8f
        private const val ROOM_HS = 2.5f
        private const val MARKER_H = 4.0f
        private const val MARKER_HS = 1.2f
        private const val FLOOR_HEIGHT = 8.0f
        private const val STEP_X = 6.0f   // horizontal staircase offset per floor
        private const val STEP_Z = 5.0f   // depth staircase offset per floor

        private const val VS = """
            uniform mat4 uMVP;
            attribute vec4 aPos;
            attribute vec4 aCol;
            varying vec4 vCol;
            void main() {
                gl_Position = uMVP * aPos;
                vCol = aCol;
            }
        """
        private const val FS = """
            precision mediump float;
            uniform float uAlpha;
            varying vec4 vCol;
            void main() {
                gl_FragColor = vec4(vCol.rgb, vCol.a * uAlpha);
            }
        """
    }

    // Camera
    var rotX = 50f
    var rotY = -30f
    var zoom = 140f

    // Floor selection: -1 = show all
    @Volatile var selectedFloor = -1
    var availableFloors: List<Int> = emptyList(); private set

    // Per-floor geometry
    private class FloorGeom {
        val pos = ArrayList<Float>(30000)
        val col = ArrayList<Float>(40000)
        var posBuf: FloatBuffer? = null
        var colBuf: FloatBuffer? = null
        var vtxCount = 0
    }
    private val floorGeom = mutableMapOf<Int, FloorGeom>()

    // Ground plane geometry
    private val gPos = ArrayList<Float>(600)
    private val gCol = ArrayList<Float>(800)
    private var gPosBuf: FloatBuffer? = null
    private var gColBuf: FloatBuffer? = null
    private var gVtxCount = 0

    // Path geometry
    private val pPos = ArrayList<Float>(12000)
    private val pCol = ArrayList<Float>(16000)
    private var pPosBuf: FloatBuffer? = null
    private var pColBuf: FloatBuffer? = null
    private var pVtxCount = 0

    // Labels
    data class LabelInfo(val text: String, val wx: Float, val wy: Float, val wz: Float, val type: String, val floor: Int)
    val labelData = mutableListOf<LabelInfo>()
    data class ScreenLabel(val text: String, val sx: Float, val sy: Float, val type: String)
    @Volatile var screenLabels: List<ScreenLabel> = emptyList()
    var onLabelsUpdated: ((List<ScreenLabel>) -> Unit)? = null

    // GL handles
    private var prog = 0
    private var hMVP = 0
    private var hAlpha = 0
    private var hPos = 0
    private var hCol = 0

    // Matrices
    private val viewMat = FloatArray(16)
    private val projMat = FloatArray(16)
    private val mvpMat = FloatArray(16)

    private var vpW = 1
    private var vpH = 1
    private var cx = 0f
    private var cy = 0f
    private var cz = 0f

    // Door connections: corridor node ID -> set of room-door node IDs connected to it
    private val doorConnections = mutableMapOf<String, MutableSet<String>>()

    init {
        for (edge in graph.edges) {
            if (edge.edgeType == "door") {
                val corridorId = if (graph.nodes[edge.from]?.type == "corridor" ||
                    graph.nodes[edge.from]?.type?.startsWith("stair") == true ||
                    graph.nodes[edge.from]?.type == "elevator")
                    edge.from else edge.to
                val roomId = if (corridorId == edge.from) edge.to else edge.from
                doorConnections.getOrPut(corridorId) { mutableSetOf() }.add(roomId)
            }
        }
        buildAllGeometry()
    }

    private fun fg(floor: Int): FloorGeom = floorGeom.getOrPut(floor) { FloorGeom() }

    // ─── Geometry helpers ─────────────────────────────

    private fun addTri(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        r: Float, g: Float, b: Float, a: Float = 1f,
        tp: ArrayList<Float> = gPos, tc: ArrayList<Float> = gCol
    ) {
        tp.add(x1); tp.add(y1); tp.add(z1)
        tp.add(x2); tp.add(y2); tp.add(z2)
        tp.add(x3); tp.add(y3); tp.add(z3)
        repeat(3) { tc.add(r); tc.add(g); tc.add(b); tc.add(a) }
    }

    private fun addQuad(
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        x3: Float, y3: Float, z3: Float,
        x4: Float, y4: Float, z4: Float,
        r: Float, g: Float, b: Float, a: Float = 1f,
        tp: ArrayList<Float> = gPos, tc: ArrayList<Float> = gCol
    ) {
        addTri(x1, y1, z1, x2, y2, z2, x3, y3, z3, r, g, b, a, tp, tc)
        addTri(x1, y1, z1, x3, y3, z3, x4, y4, z4, r, g, b, a, tp, tc)
    }

    private fun addBox(
        bx: Float, by: Float, bz: Float,
        hx: Float, hy: Float, hz: Float,
        r: Float, g: Float, b: Float,
        tp: ArrayList<Float> = gPos, tc: ArrayList<Float> = gCol
    ) {
        val x0 = bx - hx; val x1 = bx + hx
        val y0 = by;       val y1 = by + hy
        val z0 = bz - hz;  val z1 = bz + hz
        addQuad(x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, r, g, b, 1f, tp, tc)
        addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r * .85f, g * .85f, b * .85f, 1f, tp, tc)
        addQuad(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, r * .85f, g * .85f, b * .85f, 1f, tp, tc)
        addQuad(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r * .75f, g * .75f, b * .75f, 1f, tp, tc)
        addQuad(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, r * .75f, g * .75f, b * .75f, 1f, tp, tc)
    }

    // ─── Build scene ──────────────────────────────────

    private fun buildAllGeometry() {
        val nodes = graph.nodes
        if (nodes.isEmpty()) return

        val realNodes = nodes.values.filter { !it.id.contains('#') }
        if (realNodes.isEmpty()) return

        val allX = realNodes.map { it.x.toFloat() }
        val allY = realNodes.map { it.y.toFloat() }
        val baseX = (allX.min() + allX.max()) / 2f
        val baseZ = (allY.min() + allY.max()) / 2f

        val floors = realNodes.map { it.floor }.distinct().sorted()
        availableFloors = floors
        val midFloor = (floors.first() + floors.last()) / 2f
        cx = baseX + (midFloor - 1) * STEP_X
        cy = (floors.size - 1) * FLOOR_HEIGHT / 2f
        cz = baseZ + (midFloor - 1) * STEP_Z

        // Ground plane -> gPos/gCol (uses defaults)
        val m = 20f
        val gx1 = allX.min() - m; val gx2 = allX.max() + m + (floors.size - 1) * STEP_X
        val gz1 = allY.min() - m; val gz2 = allY.max() + m + (floors.size - 1) * STEP_Z
        addQuad(
            gx1, -0.1f, -(gz2),
            gx2, -0.1f, -(gz2),
            gx2, -0.1f, -(gz1),
            gx1, -0.1f, -(gz1),
            0.90f, 0.92f, 0.94f
        )

        // Floor slabs -> per-floor
        for (f in floors) {
            val fp = fg(f)
            val yOff = (f - 1) * FLOOR_HEIGHT
            val sx = (f - 1) * STEP_X
            val sz = (f - 1) * STEP_Z
            addQuad(
                allX.min() + sx - 2f, yOff + 0.01f, -(allY.max() + sz + 2f),
                allX.max() + sx + 2f, yOff + 0.01f, -(allY.max() + sz + 2f),
                allX.max() + sx + 2f, yOff + 0.01f, -(allY.min() + sz - 2f),
                allX.min() + sx - 2f, yOff + 0.01f, -(allY.min() + sz - 2f),
                0.88f, 0.90f, 0.93f, 0.5f, fp.pos, fp.col
            )
        }

        // Corridors -> per-floor
        for (edge in graph.edges) {
            if (edge.edgeType != "corridor") continue
            val a = nodes[edge.from] ?: continue
            val b = nodes[edge.to] ?: continue
            if (a.floor != b.floor) continue
            val fp = fg(a.floor)
            val yOff = (a.floor - 1) * FLOOR_HEIGHT
            val sx = (a.floor - 1) * STEP_X
            val sz = (a.floor - 1) * STEP_Z
            addCorridorSegment(
                a.x.toFloat() + sx, a.y.toFloat() + sz,
                b.x.toFloat() + sx, b.y.toFloat() + sz,
                edge.from, edge.to, yOff, fp.pos, fp.col
            )
        }

        // Rooms / markers -> per-floor
        for (node in realNodes) {
            val sx = (node.floor - 1) * STEP_X
            val sz = (node.floor - 1) * STEP_Z
            val wx = node.x.toFloat() + sx
            val wz = -(node.y.toFloat() + sz)
            val yOff = (node.floor - 1) * FLOOR_HEIGHT
            when (node.type) {
                "room-door" -> {
                    val fp = fg(node.floor)
                    addBox(wx, yOff, wz, ROOM_HS, ROOM_H, ROOM_HS, 0.55f, 0.73f, 0.95f, fp.pos, fp.col)
                    labelData.add(LabelInfo(node.label ?: node.id, wx, yOff + ROOM_H + 0.5f, wz, "room", node.floor))
                }
                "stair" -> {
                    for (sf in node.supportedFloors) {
                        val sfp = fg(sf)
                        val sfOff = (sf - 1) * FLOOR_HEIGHT
                        val ssx = (sf - 1) * STEP_X
                        val ssz = (sf - 1) * STEP_Z
                        val swx = node.x.toFloat() + ssx
                        val swz = -(node.y.toFloat() + ssz)
                        addBox(swx, sfOff, swz, MARKER_HS, MARKER_H * 0.5f, MARKER_HS, 0.85f, 0.35f, 0.65f, sfp.pos, sfp.col)
                        addBox(swx, sfOff + MARKER_H * 0.5f, swz, MARKER_HS * 0.7f, MARKER_H * 0.5f, MARKER_HS * 0.7f, 0.90f, 0.40f, 0.70f, sfp.pos, sfp.col)
                        labelData.add(LabelInfo("Stairs", swx, sfOff + MARKER_H + 0.5f, swz, "stair", sf))
                    }
                }
                "elevator" -> {
                    for (sf in node.supportedFloors) {
                        val sfp = fg(sf)
                        val sfOff = (sf - 1) * FLOOR_HEIGHT
                        val ssx = (sf - 1) * STEP_X
                        val ssz = (sf - 1) * STEP_Z
                        val swx = node.x.toFloat() + ssx
                        val swz = -(node.y.toFloat() + ssz)
                        addBox(swx, sfOff, swz, MARKER_HS, MARKER_H, MARKER_HS, 0.25f, 0.85f, 0.85f, sfp.pos, sfp.col)
                        labelData.add(LabelInfo("Elevator", swx, sfOff + MARKER_H + 0.5f, swz, "elevator", sf))
                    }
                }
                "entrance" -> {
                    val fp = fg(node.floor)
                    addBox(wx, yOff, wz, MARKER_HS, MARKER_H * 0.8f, MARKER_HS, 0.25f, 0.85f, 0.45f, fp.pos, fp.col)
                    labelData.add(LabelInfo("Exit", wx, yOff + MARKER_H * 0.8f + 0.5f, wz, "entrance", node.floor))
                }
                "escalator" -> {
                    val fp = fg(node.floor)
                    addBox(wx, yOff, wz, MARKER_HS, MARKER_H * 0.9f, MARKER_HS, 0.90f, 0.75f, 0.20f, fp.pos, fp.col)
                    labelData.add(LabelInfo("Escalator", wx, yOff + MARKER_H * 0.9f + 0.5f, wz, "escalator", node.floor))
                }
            }
        }

        // Navigation path
        if (pathNodeIds != null && pathNodeIds.size >= 2) {
            for (i in 0 until pathNodeIds.size - 1) {
                val a = nodes[pathNodeIds[i]] ?: continue
                val b = nodes[pathNodeIds[i + 1]] ?: continue
                val yA = (a.floor - 1) * FLOOR_HEIGHT
                val yB = (b.floor - 1) * FLOOR_HEIGHT
                val sxA = (a.floor - 1) * STEP_X
                val szA = (a.floor - 1) * STEP_Z
                val sxB = (b.floor - 1) * STEP_X
                val szB = (b.floor - 1) * STEP_Z
                addPathSegment(a.x.toFloat() + sxA, a.y.toFloat() + szA,
                    b.x.toFloat() + sxB, b.y.toFloat() + szB, yA, yB)
            }
        }

        // Finalize buffers
        gPosBuf = toBuffer(gPos); gColBuf = toBuffer(gCol); gVtxCount = gPos.size / 3
        for ((_, fg) in floorGeom) {
            fg.posBuf = toBuffer(fg.pos); fg.colBuf = toBuffer(fg.col); fg.vtxCount = fg.pos.size / 3
        }
        pPosBuf = toBuffer(pPos); pColBuf = toBuffer(pCol); pVtxCount = pPos.size / 3
    }

    private fun addCorridorSegment(
        ax: Float, ay: Float, bx: Float, by: Float,
        fromId: String, toId: String, yBase: Float,
        tp: ArrayList<Float>, tc: ArrayList<Float>
    ) {
        val az = -ay; val bz = -by
        val dx = bx - ax; val dz = bz - az
        val len = sqrt(dx * dx + dz * dz)
        if (len < 0.01f) return
        val px = (-dz / len) * CORR_HW
        val pz = (dx / len) * CORR_HW

        // Corridor floor strip
        addQuad(
            ax + px, yBase + 0.02f, az + pz, bx + px, yBase + 0.02f, bz + pz,
            bx - px, yBase + 0.02f, bz - pz, ax - px, yBase + 0.02f, az - pz,
            0.95f, 0.96f, 0.98f, 1f, tp, tc
        )

        val doorsAtFrom = doorConnections[fromId]?.mapNotNull { graph.nodes[it] } ?: emptyList()
        val doorsAtTo = doorConnections[toId]?.mapNotNull { graph.nodes[it] } ?: emptyList()

        val hasLeftDoorAtFrom = doorsAtFrom.any { crossSign(ax, ay, bx, by, it.x.toFloat(), it.y.toFloat()) > 0 }
        val hasRightDoorAtFrom = doorsAtFrom.any { crossSign(ax, ay, bx, by, it.x.toFloat(), it.y.toFloat()) < 0 }
        val hasLeftDoorAtTo = doorsAtTo.any { crossSign(ax, ay, bx, by, it.x.toFloat(), it.y.toFloat()) > 0 }
        val hasRightDoorAtTo = doorsAtTo.any { crossSign(ax, ay, bx, by, it.x.toFloat(), it.y.toFloat()) < 0 }

        addWallWithOpening(
            ax + px, az + pz, bx + px, bz + pz,
            hasLeftDoorAtFrom, hasLeftDoorAtTo, 0.78f, 0.82f, 0.88f, yBase, tp, tc
        )
        addWallWithOpening(
            bx - px, bz - pz, ax - px, az - pz,
            hasRightDoorAtTo, hasRightDoorAtFrom, 0.78f, 0.82f, 0.88f, yBase, tp, tc
        )
    }

    private fun crossSign(ax: Float, ay: Float, bx: Float, by: Float, rx: Float, ry: Float): Float {
        return (bx - ax) * (ry - ay) - (by - ay) * (rx - ax)
    }

    private fun addWallWithOpening(
        x1: Float, z1: Float, x2: Float, z2: Float,
        openAtStart: Boolean, openAtEnd: Boolean,
        r: Float, g: Float, b: Float, yBase: Float,
        tp: ArrayList<Float>, tc: ArrayList<Float>
    ) {
        val doorFraction = 0.3f
        val yTop = yBase + WALL_H

        when {
            openAtStart && openAtEnd -> {
                val mx1 = x1 + (x2 - x1) * doorFraction
                val mz1 = z1 + (z2 - z1) * doorFraction
                val mx2 = x1 + (x2 - x1) * (1f - doorFraction)
                val mz2 = z1 + (z2 - z1) * (1f - doorFraction)
                addQuad(mx1, yBase, mz1, mx2, yBase, mz2, mx2, yTop, mz2, mx1, yTop, mz1, r, g, b, 1f, tp, tc)
            }
            openAtStart -> {
                val mx = x1 + (x2 - x1) * doorFraction
                val mz = z1 + (z2 - z1) * doorFraction
                addQuad(mx, yBase, mz, x2, yBase, z2, x2, yTop, z2, mx, yTop, mz, r, g, b, 1f, tp, tc)
            }
            openAtEnd -> {
                val mx = x1 + (x2 - x1) * (1f - doorFraction)
                val mz = z1 + (z2 - z1) * (1f - doorFraction)
                addQuad(x1, yBase, z1, mx, yBase, mz, mx, yTop, mz, x1, yTop, z1, r, g, b, 1f, tp, tc)
            }
            else -> {
                addQuad(x1, yBase, z1, x2, yBase, z2, x2, yTop, z2, x1, yTop, z1, r, g, b, 1f, tp, tc)
            }
        }
    }

    private fun addPathSegment(ax: Float, ay: Float, bx: Float, by: Float, yA: Float, yB: Float) {
        val az = -ay; val bz = -by
        val dx = bx - ax; val dz = bz - az
        val len = sqrt(dx * dx + dz * dz)
        if (len < 0.01f) return
        val hw = 0.4f
        val nx = -dz / len; val nz = dx / len   // unit normal (perpendicular to path)
        val px = nx * hw; val pz = nz * hw
        // Red path strip
        addQuad(
            ax + px, yA + 0.15f, az + pz, bx + px, yB + 0.15f, bz + pz,
            bx - px, yB + 0.15f, bz - pz, ax - px, yA + 0.15f, az - pz,
            1.0f, 0.3f, 0.2f, 0.9f, pPos, pCol
        )
        // Direction arrows (white chevrons) along this segment
        val dirX = dx / len; val dirZ = dz / len
        val arrowSpacing = 3.5f
        val arrowLen = 1.0f
        val arrowHW = 0.65f
        var dist = arrowSpacing / 2f
        while (dist + arrowLen * 0.5f < len) {
            val t = dist / len
            val mx = ax + dx * t
            val mz = az + dz * t
            val my = yA + (yB - yA) * t + 0.17f
            val tipX = mx + dirX * arrowLen * 0.5f
            val tipZ = mz + dirZ * arrowLen * 0.5f
            val bkX = mx - dirX * arrowLen * 0.5f
            val bkZ = mz - dirZ * arrowLen * 0.5f
            addTri(
                tipX, my, tipZ,
                bkX + nx * arrowHW, my, bkZ + nz * arrowHW,
                bkX - nx * arrowHW, my, bkZ - nz * arrowHW,
                1f, 1f, 1f, 0.95f, pPos, pCol
            )
            dist += arrowSpacing
        }
    }

    // ─── GL lifecycle ─────────────────────────────────

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.94f, 0.96f, 0.99f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        val vs = compile(GLES20.GL_VERTEX_SHADER, VS)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FS)
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)

        hMVP = GLES20.glGetUniformLocation(prog, "uMVP")
        hAlpha = GLES20.glGetUniformLocation(prog, "uAlpha")
        hPos = GLES20.glGetAttribLocation(prog, "aPos")
        hCol = GLES20.glGetAttribLocation(prog, "aCol")
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        vpW = w; vpH = h
        Matrix.perspectiveM(projMat, 0, 45f, w.toFloat() / h, 0.1f, 800f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val radX = Math.toRadians(rotX.toDouble())
        val radY = Math.toRadians(rotY.toDouble())
        val eyeX = cx + (zoom * cos(radX) * sin(radY)).toFloat()
        val eyeY = cy + (zoom * sin(radX)).toFloat()
        val eyeZ = -cz + (zoom * cos(radX) * cos(radY)).toFloat()

        Matrix.setLookAtM(viewMat, 0, eyeX, eyeY, eyeZ, cx, cy, -cz, 0f, 1f, 0f)
        Matrix.multiplyMM(mvpMat, 0, projMat, 0, viewMat, 0)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(hMVP, 1, false, mvpMat, 0)

        // Ground plane - always full opacity
        GLES20.glUniform1f(hAlpha, 1f)
        draw(gPosBuf, gColBuf, gVtxCount)

        // Per-floor geometry with selection dimming
        val sel = selectedFloor
        for ((floor, fg) in floorGeom) {
            val alpha = if (sel == -1) 1f else if (floor == sel) 1f else 0.15f
            GLES20.glUniform1f(hAlpha, alpha)
            draw(fg.posBuf, fg.colBuf, fg.vtxCount)
        }

        // Navigation path - disable depth test so route is visible through walls/rooms
        if (pVtxCount > 0) {
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glUniform1f(hAlpha, 1f)
            draw(pPosBuf, pColBuf, pVtxCount)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        projectLabels()
    }

    private fun draw(p: FloatBuffer?, c: FloatBuffer?, n: Int) {
        if (p == null || c == null || n == 0) return
        p.position(0); c.position(0)
        GLES20.glEnableVertexAttribArray(hPos)
        GLES20.glVertexAttribPointer(hPos, 3, GLES20.GL_FLOAT, false, 0, p)
        GLES20.glEnableVertexAttribArray(hCol)
        GLES20.glVertexAttribPointer(hCol, 4, GLES20.GL_FLOAT, false, 0, c)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, n)
        GLES20.glDisableVertexAttribArray(hPos)
        GLES20.glDisableVertexAttribArray(hCol)
    }

    private fun projectLabels() {
        val sel = selectedFloor
        val out = labelData.mapNotNull { l ->
            if (sel != -1 && l.floor != sel) return@mapNotNull null
            val clip = FloatArray(4)
            Matrix.multiplyMV(clip, 0, mvpMat, 0, floatArrayOf(l.wx, l.wy, l.wz, 1f), 0)
            if (clip[3] <= 0.001f) return@mapNotNull null
            val sx = (clip[0] / clip[3] + 1f) / 2f * vpW
            val sy = (1f - clip[1] / clip[3]) / 2f * vpH
            ScreenLabel(l.text, sx, sy, l.type)
        }
        screenLabels = out
        onLabelsUpdated?.invoke(out)
    }

    // ─── Util ─────────────────────────────────────────

    private fun toBuffer(list: ArrayList<Float>): FloatBuffer {
        val buf = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (f in list) buf.put(f)
        buf.position(0)
        return buf
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}
