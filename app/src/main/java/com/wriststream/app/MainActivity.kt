package com.wriststream.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.TextView

/**
 * 主界面：接收端地址 + 开始/停止采集 + 训练记录控制 + 状态卡片
 * 纯框架控件（零 androidx 依赖，适配老 ROM 与 32 位环境）
 */
class MainActivity : Activity() {

    private lateinit var endpointInput: EditText
    private lateinit var statusText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var kindSpinner: Spinner
    private lateinit var workoutBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateStatus()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        requestSensorsPermission()
        handler.post(refresh)
    }

    override fun onDestroy() {
        handler.removeCallbacks(refresh)
        super.onDestroy()
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 32, 24, 32)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(TextView(this).apply {
            text = "WristStream · OPPO Watch 4 Pro"
            textSize = 18f
        })

        root.addView(TextView(this).apply { text = "接收端地址（PC）" })

        endpointInput = EditText(this).apply {
            setText(Prefs.getEndpoint(this@MainActivity))
            hint = "http://<MacIP>:9025/data"
            textSize = 14f
        }
        root.addView(endpointInput)

        statusText = TextView(this).apply { text = "..." }
        root.addView(statusText)

        toggleBtn = Button(this).apply { text = "开始采集" }
        toggleBtn.setOnClickListener {
            if (StreamService.running) {
                startService(Intent(this@MainActivity, StreamService::class.java)
                    .setAction(StreamService.ACTION_STOP))
                toggleBtn.text = "开始采集"
            } else {
                Prefs.saveEndpoint(this@MainActivity, endpointInput.text.toString().trim())
                startService(Intent(this@MainActivity, StreamService::class.java)
                    .setAction(StreamService.ACTION_START))
                toggleBtn.text = "停止采集"
            }
        }
        root.addView(toggleBtn)

        root.addView(TextView(this).apply { text = "训练类型（自建记录）" })

        kindSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_item,
                arrayOf("跑步", "骑行", "游泳", "力量训练", "羽毛球", "自由训练", "其他")
            )
        }
        root.addView(kindSpinner)

        workoutBtn = Button(this).apply { text = "开始训练" }
        workoutBtn.setOnClickListener {
            if (WorkoutRecorder.isActive) {
                WorkoutRecorder.stop(StreamService.uploader, sensorStepCount())
                workoutBtn.text = "开始训练"
            } else {
                if (!StreamService.running) {
                    Prefs.saveEndpoint(this@MainActivity, endpointInput.text.toString().trim())
                    startService(Intent(this@MainActivity, StreamService::class.java)
                        .setAction(StreamService.ACTION_START))
                    toggleBtn.text = "停止采集"
                }
                WorkoutRecorder.start(kindSpinner.selectedItem.toString(), sensorStepCount())
                workoutBtn.text = "结束训练"
            }
        }
        root.addView(workoutBtn)

        return root
    }

    /** 从运行中的采集服务拿当前步数（未启动则为 0） */
    private fun sensorStepCount(): Long = StreamService.stepCount

    private fun updateStatus() {
        val running = StreamService.running
        val sensors = StreamService.activeSensors?.joinToString() ?: "未启动"
        val hr = StreamService.currentHr
        val sent = StreamService.sentCount
        val last = StreamService.lastUploadAt
        val lastStr = if (last > 0) {
            val ago = (System.currentTimeMillis() - last) / 1000
            "${ago}s 前"
        } else "—"
        statusText.text = "状态：${if (running) "采集中" else "已停止"}\n" +
            "心率：${hr} bpm\n已上传：$sent 条（$lastStr）\n传感器：$sensors"
    }

    private fun requestSensorsPermission() {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.BODY_SENSORS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.BODY_SENSORS)
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACTIVITY_RECOGNITION)
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 100)
    }
}
