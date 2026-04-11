package com.example.indoornavapp.location

/**
 * Visual-Inertial Odometry location provider.
 *
 * Uses ARCore's 6-DoF camera pose to track the user's relative displacement
 * from a known anchor point (set by QR scan). The AR session continuously
 * provides (dx, dz) in metres which is added to the anchor's map coordinates.
 *
 * Coordinate mapping:
 *   ARCore +X  →  map +X  (rightward)
 *   ARCore -Z  →  map +Y  (forward / north in our map)
 *
 * The provider does NOT own an AR session; instead, the hosting Activity
 * calls [onArPoseUpdate] every frame with the camera's world-space translation.
 */
class VioLocationProvider : LocationProvider {
    override val providerName: String = "VIO"

    private val listeners = mutableSetOf<LocationListener>()

    // Anchor: world-map coordinates obtained from QR scan
    private var anchorX: Double = 0.0
    private var anchorY: Double = 0.0
    private var anchorFloor: Int = 1
    private var anchorNodeId: String = ""

    // Anchor: ARCore camera position at the moment of calibration
    private var arAnchorX: Float = 0f
    private var arAnchorZ: Float = 0f

    @Volatile var isCalibrated = false
        private set

    // Scale factor: map-units per AR-metre (adjusted per building)
    // BUPT library map is roughly 85 x 55 logical units for ~60 x 40 real metres
    // → ~1.4 map-units per metre
    var mapUnitsPerMetre: Double = 1.4
        set(value) { field = value }

    override fun start() = Unit
    override fun stop() { isCalibrated = false }

    override fun addListener(listener: LocationListener) { listeners.add(listener) }
    override fun removeListener(listener: LocationListener) { listeners.remove(listener) }

    /**
     * Set the known-position anchor from a QR code scan result.
     * [arX]/[arZ] = current ARCore camera translation at scan time.
     */
    fun calibrate(
        nodeId: String, mapX: Double, mapY: Double, floor: Int,
        arX: Float, arZ: Float
    ) {
        anchorNodeId = nodeId
        anchorX = mapX
        anchorY = mapY
        anchorFloor = floor
        arAnchorX = arX
        arAnchorZ = arZ
        isCalibrated = true
    }

    /**
     * Called every AR frame with the camera's world-space translation (from ARCore Pose).
     * [arX], [arZ] are the camera x/z positions in AR world space (metres).
     */
    fun onArPoseUpdate(arX: Float, arZ: Float) {
        if (!isCalibrated) return

        val deltaArX = arX - arAnchorX   // metres rightward
        val deltaArZ = arZ - arAnchorZ   // metres forward (ARCore -Z = forward)

        // Map displacement
        val mapDx = deltaArX * mapUnitsPerMetre
        val mapDy = -deltaArZ * mapUnitsPerMetre  // AR -Z → map +Y

        val currentX = anchorX + mapDx
        val currentY = anchorY + mapDy

        val location = IndoorLocation(
            nodeId = anchorNodeId,
            x = currentX,
            y = currentY,
            floor = anchorFloor,
            confidence = 0.7,
            source = providerName
        )
        listeners.forEach { it.onLocation(location) }
    }
}
