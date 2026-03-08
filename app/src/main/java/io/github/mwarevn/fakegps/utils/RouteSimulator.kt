package io.github.mwarevn.fakegps.utils

import io.github.mwarevn.fakegps.utils.LatLng
import kotlinx.coroutines.*

class RouteSimulator(
    private val points: List<LatLng>,
    private var speedKmh: Double = 52.0,
    private val updateIntervalMs: Long = 300L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var job: Job? = null
    private var paused: Boolean = false
    private var totalDistanceMeters = 0.0

    init {
        for (i in 0 until points.size - 1) {
            totalDistanceMeters += PolylineUtils.haversineDistanceMeters(points[i], points[i+1])
        }
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val dLng = to.longitude - from.longitude
        val y = Math.sin(Math.toRadians(dLng)) * Math.cos(Math.toRadians(to.latitude))
        val x = Math.cos(Math.toRadians(from.latitude)) * Math.sin(Math.toRadians(to.latitude)) - Math.sin(Math.toRadians(from.latitude)) * Math.cos(Math.toRadians(to.latitude)) * Math.cos(Math.toRadians(dLng))
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360
    }

    fun start(onUpdate: (position: LatLng, progress: Int) -> Unit = { _, _ -> }, onComplete: (() -> Unit)? = null) {
        stop()
        if (points.size < 2) return

        job = scope.launch {
            var idx = 0
            var totalTraveledMeters = 0.0
            while (idx < points.size - 1 && isActive) {
                val a = points[idx]
                val b = points[idx + 1]
                val segMeters = PolylineUtils.haversineDistanceMeters(a, b)
                if (segMeters <= 0.1) { idx++; continue }

                var traveledInSeg = 0.0
                while (traveledInSeg < segMeters && isActive) {
                    if (paused) { delay(500L); continue }

                    val bearing = calculateBearing(a, b).toFloat()
                    val interval = 300L + (Math.random() * 50).toLong()
                    val stepMeters = (speedKmh * 1000.0 / 3600.0) * (interval.toDouble() / 1000.0)
                    
                    traveledInSeg += stepMeters
                    val frac = (traveledInSeg / segMeters).coerceIn(0.0, 1.0)
                    val currentPos = LatLng(
                        a.latitude + (b.latitude - a.latitude) * frac,
                        a.longitude + (b.longitude - a.longitude) * frac
                    )
                    
                    // ĐỒNG BỘ: Ghi xuống Pref để Xposed đọc được ngay lập tức
                    PrefManager.update(
                        start = true, la = currentPos.latitude, ln = currentPos.longitude,
                        bearing = bearing, speed = SpeedSyncManager.speedKmhToMs(speedKmh.toFloat())
                    )

                    val progress = if (totalDistanceMeters > 0) (((totalTraveledMeters + traveledInSeg) / totalDistanceMeters) * 100).toInt().coerceIn(0, 100) else 0
                    onUpdate(currentPos, progress)
                    delay(interval)
                }
                totalTraveledMeters += segMeters
                idx++
            }
            onComplete?.invoke()
        }
    }

    fun setSpeed(v: Double) { speedKmh = v }
    fun pause() { paused = true }
    fun resume() { paused = false }
    fun stop() { job?.cancel(); job = null }
}
