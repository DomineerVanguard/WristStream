package com.wriststream.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * 传感器采集器：
 * 已实测 OWW221 可用：心率(21)、步数检测(18)/步数累计(19)、加速度(1)、陀螺仪(4)、重力(9)、线性加速度(10)
 * 权限：BODY_SENSORS（心率）、ACTIVITY_RECOGNITION（步数）
 * 事件统一为 {type, ts(ms), ...} JSON，入 Uploader 队列
 */
class SensorCollector(
    private val context: Context,
    private val uploader: Uploader,
    private val onStep: (Long) -> Unit = {}
) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var batteryRunnable: Runnable? = null

    /** 已注册成功的传感器名（供 UI 展示） */
    val activeSensors = mutableListOf<String>()

    /** 最新步数累计值（用于训练记录差值） */
    @Volatile var stepCount: Long = 0

    fun start() {
        register(Sensor.TYPE_HEART_RATE, SensorManager.SENSOR_DELAY_NORMAL, "heart_rate")
        register(Sensor.TYPE_STEP_COUNTER, SensorManager.SENSOR_DELAY_NORMAL, "step_counter")
        register(Sensor.TYPE_STEP_DETECTOR, SensorManager.SENSOR_DELAY_NORMAL, "step_detector")
        register(Sensor.TYPE_ACCELEROMETER, SensorManager.SENSOR_DELAY_NORMAL, "accel")
        register(Sensor.TYPE_GYROSCOPE, SensorManager.SENSOR_DELAY_NORMAL, "gyro")
        register(Sensor.TYPE_GRAVITY, SensorManager.SENSOR_DELAY_NORMAL, "gravity")
        register(Sensor.TYPE_LINEAR_ACCELERATION, SensorManager.SENSOR_DELAY_NORMAL, "linear_accel")

        val r = Runnable { batteryEvent() }
        batteryRunnable = r
        mainHandler.postDelayed(r, 60_000)
    }

    fun stop() {
        sm.unregisterListener(this)
        batteryRunnable?.let { mainHandler.removeCallbacks(it) }
        batteryRunnable = null
    }

    private fun register(type: Int, delay: Int, name: String) {
        val s = sm.getDefaultSensor(type)
        if (s != null && sm.registerListener(this, s, delay)) {
            activeSensors.add(name)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val ts = System.currentTimeMillis()
        val o = JSONObject().put("type", sensorName(event.sensor.type)).put("ts", ts)
        when (event.sensor.type) {
            Sensor.TYPE_HEART_RATE -> {
                val v = event.values[0]
                if (v > 0f) {
                    StreamService.currentHr = v.toInt()
                    o.put("value", v)
                    WorkoutRecorder.observeHr(v.toInt())
                } else return
            }
            Sensor.TYPE_STEP_COUNTER -> {
                val v = event.values[0].toLong()
                stepCount = v
                onStep(v)
                o.put("value", v)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                o.put("value", 1)
            }
            Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION -> {
                o.put("x", event.values[0])
                o.put("y", event.values[1])
                o.put("z", event.values[2])
            }
            else -> return
        }
        uploader.enqueue(o)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun batteryEvent() {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        uploader.enqueue(
            JSONObject()
                .put("type", "battery")
                .put("ts", System.currentTimeMillis())
                .put("level", level)
                .put("charging", charging)
        )
        mainHandler.postDelayed(batteryRunnable!!, 60_000)
    }

    private fun sensorName(type: Int): String = when (type) {
        Sensor.TYPE_HEART_RATE -> "heart_rate"
        Sensor.TYPE_STEP_COUNTER -> "step_counter"
        Sensor.TYPE_STEP_DETECTOR -> "step_detector"
        Sensor.TYPE_ACCELEROMETER -> "accel"
        Sensor.TYPE_GYROSCOPE -> "gyro"
        Sensor.TYPE_GRAVITY -> "gravity"
        Sensor.TYPE_LINEAR_ACCELERATION -> "linear_accel"
        else -> "sensor_$type"
    }
}
