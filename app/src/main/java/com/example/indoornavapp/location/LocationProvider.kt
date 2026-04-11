package com.example.indoornavapp.location

fun interface LocationListener {
    fun onLocation(location: IndoorLocation)
}

interface LocationProvider {
    val providerName: String
    fun start()
    fun stop()
    fun addListener(listener: LocationListener)
    fun removeListener(listener: LocationListener)
}
