package com.pop110.digital

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves CSV in Downloads/Pop110Digital without broad storage permission (Android 10+). */
class Telemetry(private val context: Context) {
    private var writer: BufferedWriter? = null
    var savedUri: Uri? = null
        private set
    var recording = false
        private set
    private var rows = 0
    fun start(): Boolean {
        if (recording) return true
        return try {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "pop110_$stamp.csv")
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Pop110Digital")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return false
            val stream = context.contentResolver.openOutputStream(uri) ?: return false
            writer = BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8))
            writer!!.write("timestamp_utc_ms,elapsed_s,velocidade_fundida_kmh,velocidade_gps_kmh,incerteza_gps_kmh,aceleracao_ms2,aceleracao_g,offset_ms2,erro_estimado_kmh,gnss_idade_s,qualidade\n")
            savedUri = uri
            rows = 0
            recording = true
            true
        } catch (_: Exception) { stop(); false }
    }
    fun append(elapsed: Double, fusion: Double, gps: Double?, sigma: Double?,
               accel: Double, bias: Double, stdSpeed: Double, gnssAge: Double?, quality: String) {
        if (!recording) return
        try {
            fun f(v: Double?) = v?.let { String.format(Locale.US, "%.4f", it) } ?: ""
            writer?.write(listOf(System.currentTimeMillis().toString(),f(elapsed),f(fusion*3.6),
                f(gps?.times(3.6)),f(sigma?.times(3.6)),f(accel),f(accel/9.80665),
                f(bias),f(stdSpeed*3.6),f(gnssAge),quality).joinToString(",")+"\n")
            rows++
            if (rows % 15 == 0) writer?.flush()
        } catch (_: Exception) { stop() }
    }
    fun stop() {
        try { writer?.flush(); writer?.close() } catch (_: Exception) {}
        writer = null; recording = false
    }
}
