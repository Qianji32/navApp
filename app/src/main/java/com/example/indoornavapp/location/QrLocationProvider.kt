package com.example.indoornavapp.location

import com.example.indoornavapp.model.Graph

class QrLocationProvider : LocationProvider {
    override val providerName: String = "QR"

    private val listeners = mutableSetOf<LocationListener>()

    override fun start() = Unit

    override fun stop() = Unit

    override fun addListener(listener: LocationListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: LocationListener) {
        listeners.remove(listener)
    }

    fun updateFromNode(graph: Graph, nodeId: String) {
        val node = graph.nodes[nodeId] ?: return
        val location = IndoorLocation(
            nodeId = node.id,
            x = node.x,
            y = node.y,
            floor = node.floor,
            confidence = 1.0,
            source = providerName
        )
        listeners.forEach { it.onLocation(location) }
    }
}
