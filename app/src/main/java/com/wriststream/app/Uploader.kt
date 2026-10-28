package com.wriststream.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 事件上传器：
 * - 所有事件进队列（线程安全）
 * - 后台线程每 5 秒批量 POST 一次 JSON 数组到接收端
 * - 失败则保留队列稍后重试（简单可靠，先不实现指数退避）
 */
class Uploader(
    private val endpointProvider: () -> String
) {
    val queue = ConcurrentLinkedQueue<JSONObject>()

    private val stopFlag = AtomicBoolean(false)
    private var thread: Thread? = null

    fun enqueue(event: JSONObject) {
        queue.add(event)
    }

    fun start() {
        if (thread != null) return
        stopFlag.set(false)
        thread = Thread({ loop() }, "wriststream-uploader").also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        stopFlag.set(true)
        thread?.join(2000)
        thread = null
    }

    private fun loop() {
        while (!stopFlag.get()) {
            try {
                Thread.sleep(5000)
                flushOnce()
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                // 单次失败不致命
            }
        }
    }

    private fun flushOnce() {
        if (queue.isEmpty()) return
        val batch = JSONArray()
        var n = 0
        while (n < 200) {
            val e = queue.poll() ?: break
            batch.put(e)
            n++
        }
        if (n == 0) return
        val ok = post(batch.toString())
        if (ok) {
            StreamService.sentCount += n
            StreamService.lastUploadAt = System.currentTimeMillis()
        } else {
            // 放回队列，下次再试（简化：仅当接收端不可达时）
            // 避免无限增长：先丢回开头重试一次
            for (i in 0 until batch.length()) queue.add(batch.getJSONObject(i))
        }
    }

    private fun post(body: String): Boolean {
        val url = endpointProvider()
        if (url.isBlank()) return false
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            val code = conn.responseCode
            conn.disconnect()
            code in 200..299
        } catch (e: Exception) {
            false
        }
    }
}
