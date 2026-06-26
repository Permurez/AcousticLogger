package com.example.acousticlogger

object ScanConfig {
    const val SCAN_DURATION_SEC = 30
    val IMPULSE_AT_SEC = intArrayOf(2, 12, 22)
    val SPECTRUM_BANDS_HZ = intArrayOf(63, 125, 250, 500, 1000, 2000, 4000, 8000)
}
