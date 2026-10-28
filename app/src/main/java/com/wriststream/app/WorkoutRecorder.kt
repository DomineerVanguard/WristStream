package com.wriststream.app

import org.json.JSONObject

/**
 * 自建训练记录（对标 WHOOP Workout / 弥补系统历史不可读）：
 * 训练中自动累计心率，结束时产出 workout 事件并入库：
 * {type:"workout", kind, start, end, duration_s, avg_hr, max_hr, steps}
 */
object WorkoutRecorder {

    @Volatile var isActive = false
        private set

    @Volatile var kind = "自由训练"
        private set

    private var startTs = 0L
    private var hrSum = 0L
    private var hrCount = 0L
    private var maxHr = 0
    private var stepStart = 0L

    /** 由 SensorCollector 在心率事件时调用 */
    fun observeHr(hr: Int) {
        if (!isActive) return
        hrSum += hr
        hrCount++
        if (hr > maxHr) maxHr = hr
    }

    fun start(kindName: String, currentSteps: Long) {
        if (isActive) return
        isActive = true
        kind = kindName
        startTs = System.currentTimeMillis()
        stepStart = currentSteps
        hrSum = 0; hrCount = 0; maxHr = 0
        // 训练开始事件
        StreamService.uploader?.enqueue(
            JSONObject()
                .put("type", "workout_start")
                .put("ts", startTs)
                .put("kind", kind)
        )
    }

    fun stop(uploader: Uploader?, currentSteps: Long) {
        if (!isActive) return
        isActive = false
        val end = System.currentTimeMillis()
        val avgHr = if (hrCount > 0) hrSum.toDouble() / hrCount else 0.0
        val steps = (currentSteps - stepStart).coerceAtLeast(0)
        uploader?.enqueue(
            JSONObject()
                .put("type", "workout")
                .put("ts", end)
                .put("kind", kind)
                .put("start", startTs)
                .put("end", end)
                .put("duration_s", (end - startTs) / 1000)
                .put("avg_hr", avgHr)
                .put("max_hr", maxHr)
                .put("steps", steps)
        )
    }
}
