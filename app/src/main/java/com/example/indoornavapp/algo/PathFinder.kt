package com.example.indoornavapp.algo

import com.example.indoornavapp.model.Graph
import com.example.indoornavapp.model.Node
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.sqrt

class PathFinder {

    /**
     * Finds the shortest path between two nodes using the A* algorithm.
     *
     * @param graph The graph representing the indoor map (nodes and edges).
     * @param startId The ID of the starting node.
     * @param endId The ID of the destination node.
     * @return A list of nodes representing the path from start to end, or null if no path is found.
     */
    fun findPath(graph: Graph, startId: String, endId: String): List<Node>? {
        return findPathResult(graph, startId, endId, RouteOptions())?.path
    }

    fun findPathResult(
        graph: Graph,
        startId: String,
        endId: String,
        options: RouteOptions = RouteOptions()
    ): PathResult? {
        val startNode = graph.nodes[startId] ?: return null
        val endNode = graph.nodes[endId] ?: return null

        // Open set: Using PriorityQueue to store nodes to be evaluated,
        // ordered by their f_score (g_score + h_score).
        val openSet = PriorityQueue<PathNode> { a, b ->
            a.fScore.compareTo(b.fScore)
        }

        // gScore: The cost of the cheapest path from start to the current node.
        // Default value is Infinity.
        val gScore = mutableMapOf<String, Double>()

        // cameFrom: The map of navigated nodes.
        // It stores the parent of each node in the optimal path found so far.
        val cameFrom = mutableMapOf<String, String>()

        // Initialize start node
        gScore[startId] = 0.0
        val startH = heuristic(startNode, endNode)
        openSet.add(PathNode(startId, startH)) // fScore = gScore + hScore = 0 + h

        while (openSet.isNotEmpty()) {
            // Get the node with the lowest fScore
            val current = openSet.poll() ?: break
            val currentId = current.id
            // If we reached the destination
            if (currentId == endId) {
                val path = reconstructPath(cameFrom, currentId, graph)
                val totalDistance = calculatePathDistance(path)
                val floorTransitions = buildFloorTransitions(path)
                val estimatedMinutes = estimateTimeMinutes(path, graph)
                return PathResult(path, totalDistance, estimatedMinutes, floorTransitions)
            }

            // Optimization: If we found a shorter path to this node already, ignore this old entry from PQ
            // But since we can't easily check 'contains with lower priority' in PQ
            // we check if the gScore we know is better than what this node implies?
            // Actually, we can just skip if gScore[currentId] is significantly lower than currentFScore - h(current)
            // Or simpler: Just proceed.

            // Get neighbors
            val neighbors = getNeighbors(graph.edges, currentId)

            for ((neighborId, edge) in neighbors) {
                val neighborNode = graph.nodes[neighborId] ?: continue

                if (options.accessibleOnly && !edge.accessible) {
                    continue
                }
                
                // tentative_gScore = gScore[current] + d(current, neighbor)
                val tentativeGScore = (gScore[currentId] ?: Double.MAX_VALUE) + edgeCost(edge, options)

                // If this path to neighbor is better than any previous one.
                if (tentativeGScore < (gScore[neighborId] ?: Double.MAX_VALUE)) {
                    // Record it!
                    cameFrom[neighborId] = currentId
                    gScore[neighborId] = tentativeGScore
                    
                    val hScore = heuristic(neighborNode, endNode)
                    val fScore = tentativeGScore + hScore
                    
                    openSet.add(PathNode(neighborId, fScore))
                }
            }
        }

        // Open set is empty and goal was not reached
        return null
    }

    /**
     * Reconstructs the path from the CameFrom map (backtracking map).
     */
    private fun reconstructPath(cameFrom: Map<String, String>, currentId: String, graph: Graph): List<Node> {
        val path = mutableListOf<Node>()
        var curr = currentId
        
        // Add end node first
        graph.nodes[curr]?.let { path.add(it) }

        while (cameFrom.containsKey(curr)) {
            curr = cameFrom[curr]!!
            graph.nodes[curr]?.let { path.add(it) }
        }

        // The path was built backwards (End -> Start), so reverse it to get Start -> End.
        return path.reversed()
    }

    /**
     * Heuristic function: Euclidean distance between two nodes.
     * h(n) = sqrt((x1-x2)^2 + (y1-y2)^2)
     */
    private fun heuristic(a: Node, b: Node): Double {
        val dx = a.x - b.x
        val dy = a.y - b.y
        val sameFloorDistance = sqrt(dx * dx + dy * dy)
        val floorPenalty = abs(a.floor - b.floor) * 8.0
        return sameFloorDistance + floorPenalty
    }

    /**
     * Helper to get neighbors and the distance to them from the edges list.
     */
    private fun getNeighbors(edges: List<com.example.indoornavapp.model.Edge>, nodeId: String): List<Pair<String, com.example.indoornavapp.model.Edge>> {
        val neighbors = mutableListOf<Pair<String, com.example.indoornavapp.model.Edge>>()
        for (edge in edges) {
            if (edge.from == nodeId) {
                neighbors.add(edge.to to edge)
            } else if (edge.to == nodeId) {
                // Assuming undirected graph for navigation (bi-directional edges)
                neighbors.add(edge.from to edge)
            }
        }
        return neighbors
    }

    private fun edgeCost(edge: com.example.indoornavapp.model.Edge, options: RouteOptions): Double {
        var cost = edge.distance * edge.congestionMultiplier * options.corridorCongestionWeight

        if (options.strategy == RouteStrategy.ELEVATOR_PREFERRED) {
            when (edge.edgeType) {
                "stair" -> cost += options.stairPenalty
                "escalator" -> cost += options.escalatorPenalty
            }
        }

        return cost
    }

    private fun calculatePathDistance(path: List<Node>): Double {
        var totalDistance = 0.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val dx = a.x - b.x
            val dy = a.y - b.y
            totalDistance += sqrt(dx * dx + dy * dy)
            if (a.floor != b.floor) {
                totalDistance += 8.0
            }
        }
        return totalDistance
    }

    private fun buildFloorTransitions(path: List<Node>): List<String> {
        if (path.size < 2) return emptyList()
        val changes = mutableListOf<String>()
        for (i in 0 until path.size - 1) {
            val current = path[i]
            val next = path[i + 1]
            if (current.floor != next.floor) {
                changes.add("F${current.floor} -> F${next.floor} at ${current.id}")
            }
        }
        return changes
    }

    private fun estimateTimeMinutes(path: List<Node>, graph: Graph): Int {
        if (path.size < 2) return 0

        var seconds = 0.0
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            val edge = findEdge(graph, a.id, b.id)
            val distance = edge?.distance ?: 0.0

            val speedMetersPerSecond = when (edge?.edgeType) {
                "stair" -> 0.7
                "elevator" -> 0.5
                else -> 1.1
            }
            seconds += if (speedMetersPerSecond > 0) distance / speedMetersPerSecond else distance
            if (edge?.edgeType == "elevator") {
                seconds += 12.0
            }
        }
        return (seconds / 60.0).toInt().coerceAtLeast(1)
    }

    private fun findEdge(graph: Graph, nodeA: String, nodeB: String): com.example.indoornavapp.model.Edge? {
        return graph.edges.firstOrNull {
            (it.from == nodeA && it.to == nodeB) || (it.from == nodeB && it.to == nodeA)
        }
    }

    // Helper data class for PriorityQueue
    private data class PathNode(val id: String, val fScore: Double)
}
