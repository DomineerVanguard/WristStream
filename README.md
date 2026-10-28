# WristStream · OPPO Watch 4 Pro 健康实时流

把 OPPO Watch 4 Pro（OWW221）的多传感器数据实时上报到 PC 本地，并支持**自建训练记录**（训练/健身记录必须项，无需 Root）。

## 架构

```
手表 App（前台服务+WakeLock）                  PC 接收端（纯 Python 标准库）
 ├ 心率/步数/加速度/陀螺仪/重力/线性加速度  ──WiFi POST JSON──▶  /data ──▶ SQLite + JSONL
 ├ 电池/充电状态（每 60s）                                                ├ raw_events（逐条）
 └ 自建训练记录（类型/时长/均心率/最大心率/步数）                        └ workouts（训练汇总）
```

- 设备基线：Android 11 / 32 位（armeabi-v7a）/ 378×496。App 纯 Kotlin + 框架控件，**零第三方依赖**。
- 传感器已真机确认：心率(21)、步数(18/19)、加速度(1)、陀螺仪(4)、重力(9)、线性加速度(10)。血氧无连续传感器，暂不支持。

## 编译 APK（无本地 JDK 方案：GitHub Actions）

1. 把 `WristStream/` 推送到你的 GitHub 仓库（main 分支）
2. Actions → **Build APK** → 手动 Run workflow（或 push 自动触发）
3. 完成后在 Artifacts 下载 `wriststream-apk`，解压得 `app-debug.apk`

> 本地有 Android Studio 也可直接打开编译。

## 安装到手表

```bash
cd <本机自开目录>
./adb install -r WristStream/app/build/outputs/apk/debug/app-debug.apk   # 或用下载的 APK

# 防杀后台（Doze 白名单）
./adb shell dumpsys deviceidle whitelist +com.wriststream.app
```

## 启动 PC 接收端

```bash
cd WristStream/receiver
python3 receiver.py --port 9025 --db wriststream.db
# 记下输出的"本机 IP"，例如 192.168.1.5
```

## 手表端操作

1. 确保手表与电脑**同一 Wi-Fi**（手表：设置 → WLAN 开启并连网；可用 `./adb shell ip -4 addr show wlan0` 查看手表 IP）
2. 打开 WristStream，把"接收端地址"改成 `http://<电脑IP>:9025/data`（例 `http://192.168.1.5:9025/data`）
3. 点**开始采集**，授予身体传感器 + 活动识别权限
4. 训练时：选类型（跑步/骑行/…）→ **开始训练** → 结束 → 自动上报训练汇总
5. 验证：浏览器打开 `http://<电脑IP>:9025/` 看状态页；或 `sqlite3 wriststream.db "select * from workouts"`

## 事件格式（与调研文档 §3.2.3 对齐）

```json
{"type":"heart_rate","ts":1763943639450,"value":75.0}
{"type":"step_counter","ts":1763943639450,"value":12345}
{"type":"accel","ts":1763943639450,"x":0.1,"y":0.2,"z":9.8}
{"type":"battery","ts":1763943639450,"level":86,"charging":false}
{"type":"workout","ts":1763943639450,"kind":"跑步","start":...,"end":...,"duration_s":1800,"avg_hr":142,"max_hr":168,"steps":3200}
```

## 后续

- 采样率分级（省电）、断线重连/本地缓存、WebSocket 实时推送
- 与 `oppo-health-bridge`（未来有 Root 手机时）历史数据合流
- 衍生指标层（恢复度/睡眠评分，对标 WHOOP）与腕上 AI 终端
