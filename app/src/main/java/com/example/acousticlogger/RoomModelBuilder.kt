package com.example.acousticlogger

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tan

object RoomModelBuilder {

    private const val HORIZONTAL_FOV_DEG = 68f
    private const val VOXEL_SIZE_M = 0.15f
    private const val MAX_FRAMES_FOR_MODEL = 80
    private const val GRID_CELL_COUNT = 24 * 18

    private fun subsampleFrames(frames: List<CameraFrameEntry>, maxFrames: Int): List<CameraFrameEntry> {
        if (frames.size <= maxFrames) return frames
        val step = frames.size.toFloat() / maxFrames
        return List(maxFrames) { index ->
            frames[(index * step).toInt().coerceAtMost(frames.lastIndex)]
        }
    }

    fun build(frames: List<CameraFrameEntry>): RoomModel {
        if (frames.isEmpty()) {
            return RoomModel(emptyList(), 0f, 0f, 0f, 0f)
        }

        val sampledFrames = subsampleFrames(frames, MAX_FRAMES_FOR_MODEL)
        val rawPoints = ArrayList<Point3D>(sampledFrames.size * GRID_CELL_COUNT)
        for (frame in sampledFrames) {
            rawPoints.addAll(projectFrame(frame))
        }

        val deduplicated = voxelDownsample(rawPoints)
        val bounds = computeBounds(deduplicated)
        val width = bounds.maxX - bounds.minX
        val height = bounds.maxY - bounds.minY
        val depth = bounds.maxZ - bounds.minZ
        val volume = (width * height * depth).coerceAtLeast(1f)

        return RoomModel(
            points = deduplicated,
            widthM = width,
            heightM = height,
            depthM = depth,
            volumeM3 = volume,
        )
    }

    fun exportPly(model: RoomModel, file: java.io.File) {
        file.bufferedWriter().use { writer ->
            writer.appendLine("ply")
            writer.appendLine("format ascii 1.0")
            writer.appendLine("element vertex ${model.points.size}")
            writer.appendLine("property float x")
            writer.appendLine("property float y")
            writer.appendLine("property float z")
            writer.appendLine("property uchar red")
            writer.appendLine("property uchar green")
            writer.appendLine("property uchar blue")
            writer.appendLine("property uchar material_id")
            writer.appendLine("end_header")
            model.points.forEach { point ->
                writer.appendLine(
                    "${point.x} ${point.y} ${point.z} ${point.red} ${point.green} ${point.blue} ${point.material.ordinal}",
                )
            }
        }
    }

    private fun projectFrame(frame: CameraFrameEntry): List<Point3D> {
        val points = ArrayList<Point3D>()
        val rotation = quaternionToMatrix(frame.orientation)

        for (gy in 0 until frame.gridHeight) {
            for (gx in 0 until frame.gridWidth) {
                val cellIndex = gy * frame.gridWidth + gx
                val cell = frame.cells.getOrNull(cellIndex) ?: continue
                val material = MaterialAbsorptionEstimator.classifyCell(
                    cell = cell,
                    gridX = gx,
                    gridY = gy,
                    gridWidth = frame.gridWidth,
                    gridHeight = frame.gridHeight,
                )

                val nx = (gx + 0.5f) / frame.gridWidth - 0.5f
                val ny = 0.5f - (gy + 0.5f) / frame.gridHeight
                val depthM = estimateDepthM(nx, ny, material)

                val rayCamera = rayFromNormalizedCoords(nx, ny)
                val rayWorld = rotateVector(rotation, rayCamera)
                val origin = floatArrayOf(0f, 0f, 0f)

                points.add(
                    Point3D(
                        x = origin[0] + rayWorld[0] * depthM,
                        y = origin[1] + rayWorld[1] * depthM,
                        z = origin[2] + rayWorld[2] * depthM,
                        red = cell.red,
                        green = cell.green,
                        blue = cell.blue,
                        material = material,
                    ),
                )
            }
        }
        return points
    }

    private fun estimateDepthM(nx: Float, ny: Float, material: MaterialType): Float {
        val horizontalAngle = abs(nx)
        val verticalAngle = abs(ny)

        val baseDepth = when (material) {
            MaterialType.CARPET, MaterialType.WOOD -> 1.2f + verticalAngle * 1.5f
            MaterialType.GLASS -> 2.5f + horizontalAngle * 2.0f
            MaterialType.CURTAIN -> 1.8f + horizontalAngle * 1.2f
            MaterialType.METAL -> 1.5f + horizontalAngle * 1.0f
            else -> 2.0f + horizontalAngle * 2.5f + verticalAngle * 1.0f
        }
        return baseDepth.coerceIn(0.8f, 8f)
    }

    private fun rayFromNormalizedCoords(nx: Float, ny: Float): FloatArray {
        val tanHalfFov = tan(Math.toRadians(HORIZONTAL_FOV_DEG / 2.0)).toFloat()
        val aspect = 4f / 3f
        val x = nx * 2f * tanHalfFov
        val y = ny * 2f * tanHalfFov / aspect
        val z = -1f
        return normalize(floatArrayOf(x, y, z))
    }

    private fun quaternionToMatrix(entry: ImuEntry): FloatArray {
        val x = entry.qx
        val y = entry.qy
        val z = entry.qz
        val w = entry.qw

        return floatArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }

    private fun rotateVector(matrix: FloatArray, vector: FloatArray): FloatArray {
        return floatArrayOf(
            matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2],
            matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2],
            matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2],
        )
    }

    private fun normalize(vector: FloatArray): FloatArray {
        val length = sqrt(vector[0] * vector[0] + vector[1] * vector[1] + vector[2] * vector[2])
        if (length <= 1e-6f) return vector
        return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
    }

    private fun voxelDownsample(points: List<Point3D>): List<Point3D> {
        val voxels = LinkedHashMap<Long, Point3D>()
        for (point in points) {
            val key = voxelKey(point.x, point.y, point.z)
            voxels.putIfAbsent(key, point)
        }
        return voxels.values.toList()
    }

    private fun voxelKey(x: Float, y: Float, z: Float): Long {
        val vx = (x / VOXEL_SIZE_M).toInt()
        val vy = (y / VOXEL_SIZE_M).toInt()
        val vz = (z / VOXEL_SIZE_M).toInt()
        return (vx.toLong() shl 42) xor (vy.toLong() shl 21) xor vz.toLong()
    }

    private fun computeBounds(points: List<Point3D>): Bounds {
        if (points.isEmpty()) return Bounds(0f, 0f, 0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        points.forEach { point ->
            minX = minOf(minX, point.x)
            minY = minOf(minY, point.y)
            minZ = minOf(minZ, point.z)
            maxX = maxOf(maxX, point.x)
            maxY = maxOf(maxY, point.y)
            maxZ = maxOf(maxZ, point.z)
        }
        return Bounds(minX, minY, minZ, maxX, maxY, maxZ)
    }

    private data class Bounds(
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float,
    )
}
