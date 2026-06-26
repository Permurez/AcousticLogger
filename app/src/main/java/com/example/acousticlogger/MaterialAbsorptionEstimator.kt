package com.example.acousticlogger

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

object MaterialAbsorptionEstimator {

    fun classifyCell(
        cell: GridCellSample,
        gridX: Int,
        gridY: Int,
        gridWidth: Int,
        gridHeight: Int,
    ): MaterialType {
        val brightness = (cell.red + cell.green + cell.blue) / 3f
        val saturation = max(cell.red, max(cell.green, cell.blue)) -
            min(cell.red, min(cell.green, cell.blue))
        val verticalRatio = gridY.toFloat() / gridHeight

        if (brightness > 195f && saturation < 35f && cell.luminanceVariance < 120f) {
            return MaterialType.GLASS
        }
        if (verticalRatio > 0.72f && cell.luminanceVariance > 350f) {
            return MaterialType.CARPET
        }
        if (verticalRatio > 0.72f && saturation > 25f && brightness in 60f..150f) {
            return MaterialType.WOOD
        }
        if (cell.luminanceVariance > 500f && saturation > 20f) {
            return MaterialType.CURTAIN
        }
        if (brightness > 170f && saturation < 20f && gridX in (gridWidth / 4)..(gridWidth * 3 / 4)) {
            return MaterialType.METAL
        }
        if (saturation < 22f && brightness in 70f..140f) {
            return MaterialType.CONCRETE
        }
        if (saturation < 45f) {
            return MaterialType.PLASTER
        }
        return MaterialType.UNKNOWN
    }

    fun estimate(frames: List<CameraFrameEntry>): List<MaterialEstimate> {
        if (frames.isEmpty()) return emptyList()

        val counts = mutableMapOf<MaterialType, Int>()
        var total = 0

        frames.forEach { frame ->
            frame.cells.forEachIndexed { index, cell ->
                val gx = index % frame.gridWidth
                val gy = index / frame.gridWidth
                val material = classifyCell(cell, gx, gy, frame.gridWidth, frame.gridHeight)
                counts[material] = counts.getOrDefault(material, 0) + 1
                total++
            }
        }

        if (total == 0) return emptyList()

        return counts.entries
            .sortedByDescending { it.value }
            .map { (type, count) ->
                MaterialEstimate(
                    type = type,
                    sharePercent = 100f * count / total,
                )
            }
    }

    fun weightedAverageAbsorption(
        materials: List<MaterialEstimate>,
        selector: (MaterialType) -> Float,
    ): Double {
        if (materials.isEmpty()) return MaterialType.UNKNOWN.alpha500Hz.toDouble()
        var weighted = 0.0
        var totalShare = 0.0
        materials.forEach { estimate ->
            weighted += estimate.sharePercent * selector(estimate.type)
            totalShare += estimate.sharePercent
        }
        return if (totalShare > 0) weighted / totalShare else selector(MaterialType.UNKNOWN).toDouble()
    }
}
