package com.example.indoornavapp.model

data class Node(
    val id: String,
    val x: Double,
    val y: Double,
    val type: String,
    val floor: Int,
    val label: String? = null,
    val supportedFloors: List<Int> = listOf(floor)
)

data class Edge(
    val from: String,
    val to: String,
    val distance: Double,
    val accessible: Boolean = true,
    val edgeType: String = "corridor",
    val congestionMultiplier: Double = 1.0
)

data class Graph(
    val nodes: Map<String, Node>,
    val edges: List<Edge>
) {
    val floors: Set<Int>
        get() = nodes.values.map { it.floor }.toSet()
}
