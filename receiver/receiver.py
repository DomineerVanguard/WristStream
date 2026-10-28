#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WristStream 接收端（纯标准库，macOS/Linux/Windows 通用）
- POST /data  接收手表上报的 JSON 数组/单条事件 → SQLite + JSONL
- GET  /      状态页（各类型条数 + 最近 20 条）
- 训练记录（type=workout）单独入库 workouts 表

用法：
    python3 receiver.py --port 9025 --db wriststream.db
"""
import argparse
import json
import sqlite3
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS raw_events(
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    type    TEXT,
    ts      INTEGER,
    payload TEXT
);
CREATE TABLE IF NOT EXISTS workouts(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    kind        TEXT,
    start_ts    INTEGER,
    end_ts      INTEGER,
    duration_s  INTEGER,
    avg_hr      REAL,
    max_hr      INTEGER,
    steps       INTEGER
);
"""


class Store:
    def __init__(self, db_path: str, jsonl_path: Path):
        self.con = sqlite3.connect(db_path, check_same_thread=False)
        self.con.executescript(SCHEMA)
        self.jsonl = jsonl_path
        self.lock = threading.Lock()

    def ingest(self, events):
        """events: list[dict]"""
        with self.lock:
            for e in events:
                typ = e.get("type", "unknown")
                ts = int(e.get("ts", 0))
                if typ == "workout":
                    self.con.execute(
                        "INSERT INTO workouts(kind,start_ts,end_ts,duration_s,avg_hr,max_hr,steps)"
                        " VALUES(?,?,?,?,?,?,?)",
                        (e.get("kind"), e.get("start"), e.get("end"),
                         e.get("duration_s"), e.get("avg_hr"),
                         e.get("max_hr"), e.get("steps")),
                    )
                else:
                    self.con.execute(
                        "INSERT INTO raw_events(type,ts,payload) VALUES(?,?,?)",
                        (typ, ts, json.dumps(e, ensure_ascii=False)),
                    )
                with self.jsonl.open("a", encoding="utf-8") as f:
                    f.write(json.dumps(e, ensure_ascii=False) + "\n")
            self.con.commit()

    def counts(self):
        with self.lock:
            raw = self.con.execute("SELECT type, COUNT(*) FROM raw_events GROUP BY type").fetchall()
            workouts = self.con.execute("SELECT COUNT(*) FROM workouts").fetchone()[0]
            last = self.con.execute(
                "SELECT type, ts, payload FROM raw_events ORDER BY id DESC LIMIT 20"
            ).fetchall()
        return {"raw": dict(raw), "workouts": workouts, "last": last}


class Handler(BaseHTTPRequestHandler):
    store: Store = None  # 由 main 注入

    def do_GET(self):
        if self.path != "/":
            self.send_error(404)
            return
        c = self.store.counts()
        rows = "".join(
            f"<li><b>{t}</b> @ {ts} : {payload}</li>" for t, ts, payload in c["last"]
        )
        body = (
            "<html><meta charset='utf-8'><body>"
            f"<h2>WristStream 接收端</h2>"
            f"<p>各类型条数：{json.dumps(c['raw'], ensure_ascii=False)}</p>"
            f"<p>训练记录数：{c['workouts']}</p>"
            f"<h3>最近事件</h3><ul>{rows}</ul>"
            "</body></html>"
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if self.path != "/data":
            self.send_error(404)
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            raw = self.rfile.read(length)
            data = json.loads(raw.decode("utf-8"))
            events = data if isinstance(data, list) else [data]
            self.store.ingest(events)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(b'{"ok":true}')
        except Exception as e:  # 单批失败不影响服务
            self.send_response(400)
            self.end_headers()
            self.wfile.write(json.dumps({"ok": False, "err": str(e)}).encode())

    def log_message(self, fmt, *args):  # 静默访问日志
        pass


def local_ips():
    ips = []
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ip = info[4][0]
            if ip and not ip.startswith("127.") and ip not in ips:
                ips.append(ip)
    except Exception:
        pass
    return ips


def main():
    ap = argparse.ArgumentParser(description="WristStream 接收端")
    ap.add_argument("--port", type=int, default=9025)
    ap.add_argument("--db", default="wriststream.db")
    ap.add_argument("--jsonl", default="events.jsonl")
    args = ap.parse_args()

    Store_ = Store(args.db, Path(args.jsonl))
    Handler.store = Store_

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    print("WristStream receiver @ 0.0.0.0:%d" % args.port)
    print("本机 IP（手表端填这个）: %s" % ", ".join(local_ips()))
    print("数据落库: %s / %s" % (args.db, args.jsonl))
    print("状态页:   http://<本机IP>:%d/" % args.port)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nbye")
        server.server_close()


if __name__ == "__main__":
    main()
