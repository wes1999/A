package com.pop110.digital

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pure Kotlin 1D speed+bias Kalman filter. Units: m/s, m/s² and seconds. */
class SpeedFilter {
    var speed = 0.0
        private set
    var bias = 0.0
        private set
    var variance = 10.0
        private set
    private var p01 = 0.0
    private var p11 = 0.2
    var lastAcceleration = 0.0
        private set
    var processNoise = 0.8
        private set
    private var stationaryAccelVariance = 0.0

    fun predict(accel: Double, dt: Double) {
        if (!dt.isFinite() || dt <= 0.0 || dt > 0.25 || !accel.isFinite()) return
        // Mechanical noise + motorcycle vibration limiting.
        val a = accel.coerceIn(-9.0, 6.0)
        val oldP00 = variance
        val oldP01 = p01
        val oldP11 = p11
        speed = max(0.0, speed + (a - bias) * dt)
        lastAcceleration = a - bias
        variance = max(0.001, oldP00 - 2.0 * dt * oldP01 + dt * dt * oldP11 + processNoise * dt)
        p01 = oldP01 - dt * oldP11
        p11 = max(0.00001, oldP11 + 0.0002 * dt)
    }

    /** Measurement std-dev supplied by Android speedAccuracyMps or a conservative fallback. */
    fun correctGps(measuredMps: Double, standardDeviationMps: Double) {
        if (!measuredMps.isFinite() || measuredMps < 0.0) return
        val sigma = (if (standardDeviationMps.isFinite()) standardDeviationMps else 1.5)
            .coerceIn(0.25, 5.0)
        val innovation = measuredMps - speed
        val s = variance + sigma * sigma
        // Protect against single multipath spikes. If uncertain after GNSS blackout,
        // allow re-acquiring the absolute speed quickly.
        if (abs(innovation) > max(10.0, 6.0 * sqrt(s))) return
        val k0 = variance / s
        val k1 = p01 / s
        val oldP00 = variance
        val oldP01 = p01
        speed = max(0.0, speed + k0 * innovation)
        bias = (bias + k1 * innovation).coerceIn(-0.6, 0.6)
        variance = max(0.001, (1.0 - k0) * oldP00)
        p01 = (1.0 - k0) * oldP01
        p11 = max(0.00001, p11 - k1 * oldP01)
    }

    /** Slow stationary offset self-calibration, not a replacement for mount alignment. */
    fun stationaryUpdate(accelMps2: Double, dt: Double) {
        if (!accelMps2.isFinite() || dt <= 0.0 || dt > 0.25) return
        val step = min(0.015 * dt, 0.004)
        bias = ((1.0 - step) * bias + step * accelMps2).coerceIn(-0.6, 0.6)
        speed = 0.0
        variance = min(variance, 0.12)
    }

    /** Retunes the motion-process noise from measured stationary vibration. */
    fun learnStationaryNoise(accelMps2: Double) {
        if (!accelMps2.isFinite()) return
        val residual = (accelMps2 - bias).coerceIn(-8.0, 8.0)
        stationaryAccelVariance = 0.98 * stationaryAccelVariance + 0.02 * residual * residual
        processNoise = (0.12 + 0.2 * stationaryAccelVariance).coerceIn(0.12, 3.0)
    }

    fun calibrateBias(sampleMps2: Double) {
        if (sampleMps2.isFinite()) bias = sampleMps2.coerceIn(-0.6, 0.6)
    }

    fun reset() {
        speed = 0.0; bias = 0.0; variance = 10.0
        p01 = 0.0; p11 = 0.2; lastAcceleration = 0.0
        processNoise = 0.8; stationaryAccelVariance = 0.0
    }
}
