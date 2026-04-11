package com.example.indoornavapp.location

class FusionLocationProvider(
    private val providers: List<LocationProvider>
) : LocationProvider {
    override val providerName: String = "Fusion"

    private val listeners = mutableSetOf<LocationListener>()
    private val internalListeners = mutableMapOf<LocationProvider, LocationListener>()

    private var lastPublished: IndoorLocation? = null

    override fun start() {
        providers.forEach { provider ->
            val listener = LocationListener { location -> onSourceUpdate(location) }
            internalListeners[provider] = listener
            provider.addListener(listener)
            provider.start()
        }
    }

    override fun stop() {
        providers.forEach { provider ->
            internalListeners[provider]?.let { provider.removeListener(it) }
            provider.stop()
        }
        internalListeners.clear()
    }

    override fun addListener(listener: LocationListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: LocationListener) {
        listeners.remove(listener)
    }

    private fun onSourceUpdate(location: IndoorLocation) {
        val previous = lastPublished
        val shouldPublish = previous == null ||
            location.confidence >= previous.confidence ||
            location.timestampMs - previous.timestampMs > 1500

        if (!shouldPublish) return

        lastPublished = location
        listeners.forEach { it.onLocation(location.copy(source = providerName)) }
    }
}
