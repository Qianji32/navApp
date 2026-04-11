package com.example.indoornavapp.location

class PdrLocationProvider : LocationProvider {
    override val providerName: String = "PDR"

    private val listeners = mutableSetOf<LocationListener>()

    override fun start() = Unit

    override fun stop() = Unit

    override fun addListener(listener: LocationListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: LocationListener) {
        listeners.remove(listener)
    }

    fun emitPredictedLocation(location: IndoorLocation) {
        listeners.forEach { it.onLocation(location.copy(source = providerName)) }
    }
}
