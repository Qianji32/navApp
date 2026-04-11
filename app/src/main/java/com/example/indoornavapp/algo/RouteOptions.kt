package com.example.indoornavapp.algo

enum class RouteStrategy {
    DISTANCE,
    ELEVATOR_PREFERRED
}

data class RouteOptions(
    val accessibleOnly: Boolean = false,
    val strategy: RouteStrategy = RouteStrategy.DISTANCE,
    val corridorCongestionWeight: Double = 1.0,
    val stairPenalty: Double = 40.0,
    val escalatorPenalty: Double = 12.0
)

data class PathResult(
    val path: List<com.example.indoornavapp.model.Node>,
    val totalDistance: Double,
    val estimatedTimeMinutes: Int,
    val floorTransitions: List<String>
)
