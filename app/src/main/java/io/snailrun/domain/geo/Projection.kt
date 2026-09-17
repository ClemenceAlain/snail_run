package io.snailrun.domain.geo

import io.snailrun.domain.model.LatLon
import kotlin.math.cos
import kotlin.math.min

/** A point in canvas space, in pixels. Deliberately not `androidx.compose.ui.geometry.Offset`. */
data class Point2D(val x: Float, val y: Float)

/**
 * Projects a track into a canvas of [width] x [height], scaled uniformly to fit inside
 * [padding] and centred.
 *
 * Equirectangular with a cos(lat) correction on longitude, which is accurate at the
 * few-kilometre scale of a run and costs one cosine. The scale is a single factor for
 * both axes, so the route keeps its shape instead of being stretched to the box.
 */
object Projection {

    fun fit(points: List<LatLon>, width: Float, height: Float, padding: Float): List<Point2D> {
        if (points.isEmpty()) return emptyList()

        val latMid = (points.minOf { it.lat } + points.maxOf { it.lat }) / 2.0
        val k = cos(Math.toRadians(latMid))

        val xs = points.map { (it.lon * k).toFloat() }
        val ys = points.map { (-it.lat).toFloat() }   // screen y grows downward

        val xMin = xs.min(); val xMax = xs.max()
        val yMin = ys.min(); val yMax = ys.max()
        val spanX = (xMax - xMin).coerceAtLeast(1e-9f)
        val spanY = (yMax - yMin).coerceAtLeast(1e-9f)

        val usableW = (width - 2 * padding).coerceAtLeast(1f)
        val usableH = (height - 2 * padding).coerceAtLeast(1f)
        val scale = min(usableW / spanX, usableH / spanY)

        val offsetX = (width - spanX * scale) / 2f - xMin * scale
        val offsetY = (height - spanY * scale) / 2f - yMin * scale

        return xs.indices.map { Point2D(xs[it] * scale + offsetX, ys[it] * scale + offsetY) }
    }
}
