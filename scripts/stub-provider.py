#!/usr/bin/env python3
"""本地联调用的最小 OpenAI 兼容上游桩服务。

仅用于端到端冒烟：验证 Provider 适配、路由、容量、Trace 与 /v1 协议链路，
不连接任何真实模型供应商。默认监听 19099。
"""
import json
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 19099


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # 静音默认访问日志
        pass

    def _send(self, status, payload, sse=False):
        body = payload.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/event-stream" if sse else "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._send(200, json.dumps({
                "object": "list",
                "data": [{"id": "stub-model", "object": "model", "created": int(time.time()), "owned_by": "stub"}],
            }))
            return
        self._send(404, json.dumps({"error": {"message": "not found", "type": "invalid_request_error"}}))

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8") if length else "{}"
        try:
            body = json.loads(raw)
        except json.JSONDecodeError:
            body = {}
        auth = self.headers.get("Authorization") or ""
        if not auth.startswith("Bearer "):
            self._send(401, json.dumps({"error": {"message": "missing api key", "type": "invalid_request_error"}}))
            return
        if self.path.startswith("/v1/chat/completions"):
            self._chat(body)
            return
        self._send(404, json.dumps({"error": {"message": "not found", "type": "invalid_request_error"}}))

    def _chat(self, body):
        created = int(time.time())
        prompt_tokens, completion_tokens = 8, 12
        usage = {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        }
        if body.get("stream"):
            chunks = ["你好", "，这是", "本地桩", "响应。"]
            lines = []
            for index, text in enumerate(chunks):
                lines.append("data: " + json.dumps({
                    "id": "chatcmpl-stub",
                    "object": "chat.completion.chunk",
                    "created": created,
                    "model": body.get("model", "stub-model"),
                    "choices": [{"index": 0, "delta": {"content": text}, "finish_reason": None}],
                    "usage": usage if index == len(chunks) - 1 else None,
                }, ensure_ascii=False))
            lines.append("data: [DONE]")
            self._send(200, "\n\n".join(lines) + "\n\n", sse=True)
            return
        self._send(200, json.dumps({
            "id": "chatcmpl-stub",
            "object": "chat.completion",
            "created": created,
            "model": body.get("model", "stub-model"),
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": "你好，这是本地桩响应。"},
                "finish_reason": "stop",
            }],
            "usage": usage,
        }, ensure_ascii=False))


if __name__ == "__main__":
    print(f"stub provider listening on {PORT}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
