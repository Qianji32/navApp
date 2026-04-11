package com.example.indoornavapp.location

data class IndoorLocation(
    val nodeId: String,
    val x: Double,
    val y: Double,
    val floor: Int,
    val confidence: Double,
    val source: String,
    val timestampMs: Long = System.currentTimeMillis()
)
