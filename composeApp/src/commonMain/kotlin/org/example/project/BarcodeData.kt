package org.example.project

import kotlin.time.Clock

data class BarcodeData(
    val value: String,
    val format: String,
    val timestamp: Long = 0,
    val x: Float = 0f,
    val y: Float = 0f,
    val z: Float = 0f
)