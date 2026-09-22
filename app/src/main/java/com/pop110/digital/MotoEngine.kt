package com.pop110.digital

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

// One chart point per 200ms, max 300 = 60 seconds.
data class ChartPoint(val t: Float, val fusion: Float, val gps: Float?)
data class DashState(
    val speedKmh: Float = 0f,
    val gpsKmh: Float? = null,
    val gnssSigmaKmh: Float? = null,
    val filterSigmaKmh: Float = 0f,
    val maxKmh: Float = 0f,
    val accelG: Float = 0f,
    val bias: Float = 0f,
    val status: String = "Aguardando localização precisa",
    val autoCalibration: Boolean = false,
    val recording: Boolean = false,
    val chart: List<ChartPoint> = emptyList()
)

class MotoEngine(private val context: Context, private val onState: (DashState) -> Unit) :
    SensorEventListener, LocationListener {
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val location = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val linear = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val filter = SpeedFilter()
    private val telemetry = Telemetry(context)
    private val points = ArrayDeque<ChartPoint>()
    private var state = DashState()
    private var running = false
    private var sensorNs = 0L
    private var gpsElapsedNs = 0L
    private var gpsSpeed: Double? = null
    private var gpsSigma: Double? = null
    private var filteredA = 0.0
    private var rawA = 0.0
    private var yawRate = 0.0
    private var stationarySeconds = 0.0
    private var lastUiMs = 0L
    private var startedMs = SystemClock.elapsedRealtime()
    private var manualCalibrationMs = 0L
    private var manualCount = 0
    private var manualSum = 0.0

    fun start() {
        if (running) return
        running = true
        linear?.let { sensors.registerListener(this, it, 20_000) }
        gyro?.let { sensors.registerListener(this, it, 20_000) }
        if (ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try { location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 200L, 0f, this) }
            catch (_: SecurityException) { publish("Permita localização precisa") }
            catch (_: IllegalArgumentException) { publish("GPS indisponível") }
        } else publish("Permita localização precisa")
        if (linear == null) publish("Sem sensor linear: apenas GNSS")
    }
    fun stop() {
        if (!running) return
        running = false
        sensors.unregisterListener(this)
        try { location.removeUpdates(this) } catch (_: SecurityException) {}
        telemetry.stop()
        state = state.copy(recording = false)
        publish()
    }
    fun startRecording(): Boolean {
        val ok = telemetry.start()
        state = state.copy(recording = ok)
        publish(if (ok) "Gravando CSV em Downloads/Pop110Digital" else "Falha ao criar CSV")
        return ok
    }
    fun stopRecording() {
        telemetry.stop(); state = state.copy(recording=false)
        publish("CSV salvo em Downloads/Pop110Digital")
    }
    fun calibration() {
        manualCalibrationMs = SystemClock.elapsedRealtime()
        manualCount = 0; manualSum = 0.0
        publish("Moto parada e celular fixo por 3 segundos")
    }
    private fun gpsAge(nowNs: Long): Double? = if (gpsElapsedNs > 0) (nowNs-gpsElapsedNs)/1e9 else null
    override fun onSensorChanged(event: SensorEvent) {
        if (!running) return
        if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
            yawRate = event.values[2].toDouble()
            return
        }
        if (event.sensor.type != Sensor.TYPE_LINEAR_ACCELERATION) return
        // Mounting contract: portrait, phone top pointing in the bike's forward direction.
        rawA = event.values[1].toDouble()
        filteredA = 0.88 * filteredA + 0.12 * rawA
        val dt = if (sensorNs > 0) (event.timestamp-sensorNs)/1e9 else 0.0
        sensorNs = event.timestamp
        if (dt !in 0.0001..0.25) return
        val nowMs = SystemClock.elapsedRealtime()
        val age = gpsAge(SystemClock.elapsedRealtimeNanos())
        val validGps = age != null && age in 0.0..3.0 && gpsSpeed != null
        val standing = validGps && gpsSpeed!! < 0.4 && (gpsSigma ?: 5.0) < 1.1 && abs(filteredA) < 0.8
        stationarySeconds = if (standing) stationarySeconds+dt else 0.0
        if (manualCalibrationMs != 0L) {
            if (nowMs - manualCalibrationMs < 3_000 && standing) {
                manualSum += rawA; manualCount++
            } else if (nowMs - manualCalibrationMs >= 3_000) {
                if (manualCount > 40 && standing) {
                    val estimate = manualSum/manualCount
                    filter.calibrateBias(estimate)
                    publish("Calibração manual concluída")
                } else publish("Calibração cancelada: aguarde GNSS e fique parado")
                manualCalibrationMs = 0L
            }
        }
        // Ignore accelerations from cornering and rough gyro behavior for display;
        // the GNSS measurement remains the main source in curves.
        val usableA = if (abs(filteredA) < 0.11 || abs(yawRate) > 1.5) 0.0 else filteredA
        filter.predict(usableA, dt)
        if (stationarySeconds > 1.5) {
            filter.learnStationaryNoise(rawA)
            filter.stationaryUpdate(rawA,dt)
        }
        maybePublish(nowMs)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    override fun onLocationChanged(loc: Location) {
        if (!running || !loc.hasSpeed()) return
        val now = SystemClock.elapsedRealtimeNanos()
        // Reject stale / replayed location objects and obviously inaccurate fixes.
        val age = (now-loc.elapsedRealtimeNanos)/1e9
        if (age !in -0.2..3.0) return
        if (loc.hasAccuracy() && loc.accuracy > 80.0f) return
        val newSpeed = loc.speed.toDouble().coerceAtLeast(0.0)
        val newSigma = if (loc.hasSpeedAccuracy()) loc.speedAccuracyMetersPerSecond.toDouble() else 1.5
        if (newSigma > 8.0) return
        gpsSpeed = newSpeed
        gpsSigma = newSigma
        gpsElapsedNs = loc.elapsedRealtimeNanos
        filter.correctGps(newSpeed,newSigma)
        // For devices missing TYPE_LINEAR_ACCELERATION, deliver GNSS-only value.
        if (linear == null) {
            state = state.copy(speedKmh=(newSpeed*3.6).toFloat())
        }
        maybePublish(SystemClock.elapsedRealtime(), true)
    }
    private fun maybePublish(now: Long, force: Boolean = false) {
        if (!force && now-lastUiMs < 200) return
        lastUiMs = now
        val age = gpsAge(SystemClock.elapsedRealtimeNanos())
        val quality = when {
            gpsSpeed == null -> "Aguardando sinal GPS"
            age == null || age > 8.0 -> "GPS perdido: medição incerta"
            age > 3.0 -> "GPS temporariamente indisponível"
            (gpsSigma ?: 10.0) > 2.0 -> "GPS com incerteza elevada"
            linear == null -> "Velocidade GNSS"
            else -> "GNSS + inércia • calibragem automática"
        }
        val speed = if (linear == null) gpsSpeed ?: 0.0 else filter.speed
        val elapsed = (now - startedMs)/1000.0
        points.addLast(ChartPoint(elapsed.toFloat(),(speed*3.6).toFloat(),
            if ((age ?: 10.0) < 3.0) gpsSpeed?.times(3.6)?.toFloat() else null))
        while (points.size > 300) points.removeFirst()
        state = state.copy(speedKmh=(speed*3.6).toFloat(),
            gpsKmh=if ((age ?: 10.0) < 3.0) gpsSpeed?.times(3.6)?.toFloat() else null,
            gnssSigmaKmh=gpsSigma?.times(3.6)?.toFloat(),
            filterSigmaKmh=(sqrt(filter.variance)*3.6).toFloat(),
            accelG=(filter.lastAcceleration/9.80665).toFloat(),
            bias=filter.bias.toFloat(),
            maxKmh=max(state.maxKmh,(speed*3.6).toFloat()),
            status=quality,
            autoCalibration=stationarySeconds > 1.5,
            chart=points.toList())
        telemetry.append(elapsed,speed,gpsSpeed,gpsSigma,filteredA,filter.bias,
            sqrt(filter.variance),age,quality.replace(",", " "))
        publish()
    }
    private fun publish(msg: String? = null) {
        if (msg != null) state = state.copy(status=msg)
        onState(state)
    }
}
