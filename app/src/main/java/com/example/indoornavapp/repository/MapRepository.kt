package com.example.indoornavapp.repository

import android.content.Context
import com.example.indoornavapp.model.Edge
import com.example.indoornavapp.model.Graph
import com.example.indoornavapp.model.Node
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class MapRepository {

    /**
     * Reads the JSON file from assets and parses it into a Graph object.
     */
    fun loadMap(context: Context): Graph {
        val jsonString = readAssetJson(context, "map_data.json")
        return parseGraph(jsonString)
    }

    private fun readAssetJson(context: Context, fileName: String): String {
        return context.assets.open(fileName).use { inputStream ->
            InputStreamReader(inputStream).use { reader ->
                BufferedReader(reader).use {
                    it.readText()
                }
            }
        }
    }

    private fun parseGraph(jsonString: String): Graph {
        val root = JSONObject(jsonString)
        
        // Parse Nodes
        val nodesArray = root.getJSONArray("nodes")
        val nodesMap = mutableMapOf<String, Node>()
        for (i in 0 until nodesArray.length()) {
            val nodeObj = nodesArray.getJSONObject(i)
            val id = nodeObj.getString("id")
            val x = nodeObj.getDouble("x")
            val y = nodeObj.getDouble("y")
            val type = nodeObj.getString("type")
            val floor = nodeObj.getInt("floor")
            val supportedFloors = parseOptionalIntArray(nodeObj.optJSONArray("floors")) ?: listOf(floor)
            
            var label: String? = null
            if (type == "room-door" && nodeObj.has("room")) {
                label = nodeObj.getString("room")
            } else if (type == "stair") {
                label = "Stairs"
            } else if (type == "elevator") {
                label = "Elevator"
            } else if (type == "entrance") {
                label = "Entrance"
            }
            
            nodesMap[id] = Node(id, x, y, type, floor, label, supportedFloors)

            // Build virtual connector nodes for additional floors to support cross-floor routing.
            if (supportedFloors.size > 1) {
                for (f in supportedFloors) {
                    if (f == floor) continue
                    val virtualId = floorNodeId(id, f)
                    nodesMap[virtualId] = Node(
                        id = virtualId,
                        x = x,
                        y = y,
                        type = type,
                        floor = f,
                        label = "$id(F$f)",
                        supportedFloors = supportedFloors
                    )
                }
            }
        }

        // Parse Edges
        val edgesArray = root.getJSONArray("edges")
        val edgesList = mutableListOf<Edge>()
        for (i in 0 until edgesArray.length()) {
            val edgeObj = edgesArray.getJSONObject(i)
            val from = edgeObj.getString("from")
            val to = edgeObj.getString("to")
            val distance = edgeObj.getDouble("distance")
            val accessible = if (edgeObj.has("accessible")) edgeObj.getBoolean("accessible") else true
            val edgeType = edgeObj.optString("edge_type", "corridor")
            val congestionMultiplier = edgeObj.optDouble("congestion", 1.0)
            
            edgesList.add(
                Edge(
                    from = from,
                    to = to,
                    distance = distance,
                    accessible = accessible,
                    edgeType = edgeType,
                    congestionMultiplier = congestionMultiplier
                )
            )
        }

        // Parse connector metadata and create inter-floor virtual edges.
        if (root.has("connectors")) {
            val connectors = root.getJSONArray("connectors")
            for (i in 0 until connectors.length()) {
                val obj = connectors.getJSONObject(i)
                val nodeId = obj.optString("node", obj.optString("id"))
                val floors = parseOptionalIntArray(obj.optJSONArray("floors")) ?: continue
                if (floors.size < 2) continue

                val connectorType = obj.optString("type", "stair")
                val accessible = if (obj.has("accessible")) obj.getBoolean("accessible") else connectorType != "stair"
                val sortedFloors = floors.sorted()

                for (idx in 0 until sortedFloors.size - 1) {
                    val f1 = sortedFloors[idx]
                    val f2 = sortedFloors[idx + 1]
                    val fromId = resolveConnectorNodeId(nodeId, f1, nodesMap)
                    val toId = resolveConnectorNodeId(nodeId, f2, nodesMap)
                    if (fromId == null || toId == null) continue

                    edgesList.add(
                        Edge(
                            from = fromId,
                            to = toId,
                            distance = 8.0,
                            accessible = accessible,
                            edgeType = connectorType,
                            congestionMultiplier = 1.0
                        )
                    )
                }
            }
        }

        return Graph(nodesMap, edgesList)
    }

    private fun parseOptionalIntArray(array: JSONArray?): List<Int>? {
        if (array == null) return null
        val values = mutableListOf<Int>()
        for (i in 0 until array.length()) {
            values.add(array.getInt(i))
        }
        return values
    }

    private fun floorNodeId(base: String, floor: Int): String = "${base}#F$floor"

    private fun resolveConnectorNodeId(base: String, floor: Int, nodes: Map<String, Node>): String? {
        val baseNode = nodes[base]
        return when {
            baseNode != null && baseNode.floor == floor -> base
            nodes.containsKey(floorNodeId(base, floor)) -> floorNodeId(base, floor)
            else -> null
        }
    }
}
