package io.github.mwarevn.fakegps.utils

import io.github.mwarevn.fakegps.domain.model.LatLng
import kotlinx.coroutines.*
import kotlin.math.*

class RouteSimulator(
    private val points: List<LatLng>,
    private var targetSpeedKmh: Double = 52.0,
    private val updateIntervalMs: Long = 300L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default)
) {
    private var job: Job? = null
    private var paused: Boolean = false
    private var totalDistanceMeters = 0.0
    private var currentActualSpeedKmh = 0.0

    // Cấu hình giảm tốc độ khi cua
    companion object {
        private const val MIN_SPEED_KMH = 5.0
        private const val CURVE_SPEED_MAX = 40.0 // Tốc độ tối đa khi cua góc rộng
        private const val SMOOTH_FACTOR = 0.15 // Tốc độ thay đổi (acceleration/deceleration)
        private const val LOOK_AHEAD_POINTS = 3 // Số điểm nhìn trước để đoán cua
    }

    init {
        for (i in 0 until points.size - 1) {
            totalDistanceMeters += PolylineUtils.haversineDistanceMeters(points[i], points[i+1])
        }
        currentActualSpeedKmh = targetSpeedKmh
    }

    private fun calculateBearing(from: LatLng, to: LatLng): Double {
        val dLng = to.longitude - from.longitude
        val y = sin(Math.toRadians(dLng)) * cos(Math.toRadians(to.latitude))
        val x = cos(Math.toRadians(from.latitude)) * sin(Math.toRadians(to.latitude)) - 
                sin(Math.toRadians(from.latitude)) * cos(Math.toRadians(to.latitude)) * cos(Math.toRadians(dLng))
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    private fun calculateAngleChange(p1: LatLng, p2: LatLng, p3: LatLng): Double {
        val b1 = calculateBearing(p1, p2)
        val b2 = calculateBearing(p2, p3)
        var diff = abs(b1 - b2)
        if (diff > 180) diff = 360 - diff
        return diff
    }

    private fun getRecommendedSpeedForCurve(points: List<LatLng>, currentIndex: Int): Double {
        if (!PrefManager.autoCurveSpeed) return targetSpeedKmh
        
        var maxAngleChange = 0.0
        // Nhìn trước vài điểm để xem có cua gấp không
        for (i in 0 until LOOK_AHEAD_POINTS) {
            val idx = currentIndex + i
            if (idx + 2 < points.size) {
                val angle = calculateAngleChange(points[idx], points[idx+1], points[idx+2])
                if (angle > maxAngleChange) maxAngleChange = angle
            }
        }

        return when {
            maxAngleChange > 70 -> 10.0 // Cua cực gắt (U-turn)
            maxAngleChange > 45 -> 18.0 // Cua gắt
            maxAngleChange > 25 -> 28.0 // Cua vừa
            maxAngleChange > 10 -> 38.0 // Cua nhẹ
            else -> targetSpeedKmh // Đường thẳng
        }.coerceAtMost(targetSpeedKmh)
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

                    // 1. Tính toán tốc độ khuyến nghị cho đoạn đường phía trước
                    val recommendedSpeed = getRecommendedSpeedForCurve(points, idx)
                    
                    // 2. Thay đổi tốc độ từ từ (Smooth Transition)
                    if (currentActualSpeedKmh < recommendedSpeed) {
                        currentActualSpeedKmh += (recommendedSpeed - currentActualSpeedKmh) * SMOOTH_FACTOR
                    } else if (currentActualSpeedKmh > recommendedSpeed) {
                        currentActualSpeedKmh -= (currentActualSpeedKmh - recommendedSpeed) * SMOOTH_FACTOR
                    }
                    
                    currentActualSpeedKmh = currentActualSpeedKmh.coerceIn(MIN_SPEED_KMH, targetSpeedKmh)

                    val bearing = calculateBearing(a, b).toFloat()
                    val interval = updateIntervalMs + (Math.random() * 50).toLong()
                    val stepMeters = (currentActualSpeedKmh * 1000.0 / 3600.0) * (interval.toDouble() / 1000.0)
                    
                    traveledInSeg += stepMeters
                    val frac = (traveledInSeg / segMeters).coerceIn(0.0, 1.0)
                    val currentPos = LatLng(
                        a.latitude + (b.latitude - a.latitude) * frac,
                        a.longitude + (b.longitude - a.longitude) * frac
                    )
                    
                    // Đồng bộ vận tốc thực tế lên UI và Xposed
                    SpeedSyncManager.updateActualSpeed(currentActualSpeedKmh.toFloat())
                    SpeedSyncManager.updateBearing(bearing)
                    SpeedSyncManager.updateCurveReduction((currentActualSpeedKmh / targetSpeedKmh).toFloat())

                    PrefManager.update(
                        start = true, la = currentPos.latitude, ln = currentPos.longitude,
                        bearing = bearing, speed = SpeedSyncManager.speedKmhToMs(currentActualSpeedKmh.toFloat())
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

    fun setSpeed(v: Double) { 
        targetSpeedKmh = v 
        if (!PrefManager.autoCurveSpeed) currentActualSpeedKmh = v
    }
    fun pause() { paused = true }
    fun resume() { paused = false }
    fun stop() { job?.cancel(); job = null }
}
