package com.example.acousticlogger

data class AudioBufferEntry(
    val timestampNs: Long,
    val samples: ShortArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioBufferEntry) return false
        return timestampNs == other.timestampNs && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = timestampNs.hashCode()
        result = 31 * result + samples.contentHashCode()
        return result
    }
}

data class ImuEntry(
    val timestampNs: Long,
    val qx: Float,
    val qy: Float,
    val qz: Float,
    val qw: Float,
)

data class GridCellSample(
    val red: Int,
    val green: Int,
    val blue: Int,
    val luminanceVariance: Float,
)

data class CameraFrameEntry(
    val timestampNs: Long,
    val orientation: ImuEntry,
    val gridWidth: Int,
    val gridHeight: Int,
    val cells: List<GridCellSample>,
)

data class Point3D(
    val x: Float,
    val y: Float,
    val z: Float,
    val red: Int,
    val green: Int,
    val blue: Int,
    val material: MaterialType,
)

data class RoomModel(
    val points: List<Point3D>,
    val widthM: Float,
    val heightM: Float,
    val depthM: Float,
    val volumeM3: Float,
)

enum class MaterialType(
    val label: String,
    val alpha125Hz: Float,
    val alpha500Hz: Float,
    val alpha1000Hz: Float,
    val alpha2000Hz: Float,
) {
    PLASTER("Tynk / gips", 0.01f, 0.02f, 0.03f, 0.04f),
    CONCRETE("Beton", 0.01f, 0.01f, 0.02f, 0.02f),
    GLASS("Szkło", 0.35f, 0.03f, 0.02f, 0.02f),
    CURTAIN("Zasłona / tekstylia", 0.07f, 0.30f, 0.45f, 0.50f),
    CARPET("Dywan / wykładzina", 0.08f, 0.20f, 0.35f, 0.45f),
    WOOD("Drewno", 0.10f, 0.10f, 0.10f, 0.08f),
    METAL("Metal", 0.05f, 0.01f, 0.01f, 0.01f),
    UNKNOWN("Nieznany", 0.03f, 0.05f, 0.05f, 0.05f),
}

data class MaterialEstimate(
    val type: MaterialType,
    val sharePercent: Float,
)

data class AcousticReport(
    val rt60BroadbandSec: Double,
    val rt60ByBandHz: Map<Int, Double>,
    val estimatedVolumeM3: Float,
    val sabineAverageAbsorption: Double,
    val edcDropDb: Double,
)

data class SessionResults(
    val sessionDirPath: String,
    val roomWidthM: Float,
    val roomHeightM: Float,
    val roomDepthM: Float,
    val roomVolumeM3: Float,
    val pointCount: Int,
    val rt60BroadbandSec: Double,
    val sabineAverageAbsorption: Double,
    val edcDropDb: Double,
    val topMaterialLabel: String,
    val materialsSummary: String,
    val exportFilesSummary: String,
) : java.io.Serializable

data class SessionExportResult(
    val sessionDir: java.io.File,
    val results: SessionResults,
)
