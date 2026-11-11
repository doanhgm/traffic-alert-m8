package com.m8.trafficwarn

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.*
import kotlin.math.abs

// 1. Dữ liệu tọa độ các nút giao
data class TrafficPoint(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    var lastAlerted: Long = 0L
)

object PointsData {
    val list = listOf(
        TrafficPoint("1", "Võ Chí Công - Xuân La", 21.059288, 105.808726),
        TrafficPoint("2", "Cầu Nhật Tân - An Dương Vương", 21.083162, 105.817441),
        TrafficPoint("3", "Hoàng Quốc Việt - Phạm Văn Đồng", 21.047120, 105.783610)
    )
}

// 2. Service chạy ngầm định vị bằng phần cứng GPS độc lập
class TrafficAlertService : Service(), LocationListener, TextToSpeech.OnInitListener {
    private lateinit var locManager: LocationManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

    override fun onCreate() {
        super.onCreate()
        startForeground(101, buildNoti("Đang quét vị trí..."))
        tts = TextToSpeech(this, this)
        initGPS()
    }

    private fun buildNoti(text: String): Notification {
        val cid = "traffic_service_ch"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(cid, "GPS Alert", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
        return NotificationCompat.Builder(this, cid)
            .setContentTitle("Cảnh Báo Đèn Đỏ")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun initGPS() {
        locManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 2.0f, this)
    }

    override fun onLocationChanged(loc: Location) {
        if (!loc.hasBearing() || loc.speed < 1.5f) return
        val now = System.currentTimeMillis()

        for (pt in PointsData.list) {
            if (now - pt.lastAlerted < 90_000L) continue // Cooldown 90s không báo lại

            val target = Location("").apply {
                latitude = pt.lat
                longitude = pt.lon
            }
            val dist = loc.distanceTo(target)

            if (dist <= 200.0) { // Bắt đầu quét từ 200m
                val bearingTo = loc.bearingTo(target)
                var diff = abs(loc.bearing - bearingTo)
                if (diff > 180) diff = 360 - diff

                if (diff <= 35.0) { // Đang hướng thẳng về phía đèn đỏ
                    pt.lastAlerted = now
                    alert(pt.name, dist.toInt())
                    break
                }
            }
        }
    }

    private fun alert(name: String, dist: Int) {
        val msg = "Sắp đến $name, cách $dist mét"
        if (ttsReady) {
            tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "ALERT")
        } else {
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        }
        getSystemService(NotificationManager::class.java).notify(101, buildNoti(msg))
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val res = tts?.setLanguage(Locale("vi", "VN"))
            ttsReady = (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        locManager.removeUpdates(this)
        tts?.shutdown()
        toneGen.release()
    }

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
    override fun onProviderEnabled(p: String) {}
    override fun onProviderDisabled(p: String) {}
}

// 3. Màn hình bật tắt
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(60, 100, 60, 60)
        }
        val status = TextView(this).apply { text = "Trạng thái: Đang Tắt"; textSize = 18f }
        val btnStart = Button(this).apply {
            text = "BẬT CẢNH BÁO"
            setOnClickListener {
                if (checkPermission()) {
                    val intent = Intent(this@MainActivity, TrafficAlertService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                    else startService(intent)
                    status.text = "Trạng thái: ĐANG CHẠY NỀN"
                }
            }
        }
        val btnStop = Button(this).apply {
            text = "TẮT"
            setOnClickListener {
                stopService(Intent(this@MainActivity, TrafficAlertService::class.java))
                status.text = "Trạng thái: Đã Dừng"
            }
        }
        layout.addView(status)
        layout.addView(btnStart)
        layout.addView(btnStop)
        setContentView(layout)
        checkPermission()
    }

    private fun checkPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 101)
            return false
        }
        return true
    }
}
