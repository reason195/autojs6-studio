#!/usr/bin/env python3
"""Fake AutoJs6 MCP server for on-device verification of the grouped tool dropdown.

Implements the minimal JSON-RPC surface the studio client uses:
  initialize -> notifications/initialized -> tools/list -> tools/call
Answers plain JSON (not SSE); no session management. Binds 0.0.0.0.
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TOOLS = [
    # ---- script 组（前缀 script_）----
    {"name": "script_run", "description": "在 AutoJs6 里运行脚本，可等待完成并收集输出。",
     "inputSchema": {"type": "object", "properties": {
         "source": {"type": "string", "description": "要执行的 JavaScript 源码"},
         "name": {"type": "string", "description": "脚本名称（仅用于显示与日志）"},
         "waitForCompletion": {"type": "boolean", "description": "是否等待脚本执行完才返回"},
         "timeoutMs": {"type": "integer", "description": "等待超时毫秒", "default": 60000},
         "captureConsole": {"type": "boolean", "description": "是否捕获 console 输出"},
         "arguments": {"type": "object", "description": "传给脚本的实参对象"}},
         "required": ["source"]}},
    {"name": "script_stop", "description": "按 executionId 停止一个运行中的脚本。",
     "inputSchema": {"type": "object", "properties": {
         "executionId": {"type": "integer", "description": "脚本执行 ID"}},
         "required": ["executionId"]}},
    {"name": "script_stop_all", "description": "停止所有正在运行的脚本。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "script_list_running", "description": "列出当前运行的脚本。",
     "inputSchema": {"type": "object", "properties": {}}},
    # ---- app 组（前缀 app_）----
    {"name": "app_launch", "description": "启动一个已安装的应用。",
     "inputSchema": {"type": "object", "properties": {
         "packageName": {"type": "string", "description": "应用包名"},
         "waitForForeground": {"type": "boolean", "description": "是否等待应用进入前台"}},
         "required": ["packageName"]}},
    {"name": "app_list", "description": "列出所有已安装应用。",
     "inputSchema": {"type": "object", "properties": {
         "includeSystem": {"type": "boolean", "description": "是否包含系统应用"}}}},
    {"name": "app_kill", "description": "结束指定应用进程。",
     "inputSchema": {"type": "object", "properties": {
         "packageName": {"type": "string", "description": "应用包名"}},
         "required": ["packageName"]}},
    # ---- ui 组（前缀 ui_）----
    {"name": "ui_dump", "description": "读取当前界面的无障碍控件树。",
     "inputSchema": {"type": "object", "properties": {
         "format": {"type": "string", "description": "输出格式", "enum": ["json", "text"], "default": "json"},
         "maxNodes": {"type": "integer", "description": "最大节点数", "default": 400},
         "maxDepth": {"type": "integer", "description": "最大深度", "default": 32},
         "visibleOnly": {"type": "boolean", "description": "只保留可见节点", "default": True}},
         "required": []}},
    {"name": "ui_find", "description": "按选择器查找控件。",
     "inputSchema": {"type": "object", "properties": {
         "selector": {"type": "object", "description": "选择器对象，如 {\"text\": \"首页\"}"},
         "limit": {"type": "integer", "description": "最多返回几个", "default": 20}},
         "required": ["selector"]}},
    {"name": "ui_explain_selector", "description": "解释选择器匹配逻辑。",
     "inputSchema": {"type": "object", "properties": {
         "selector": {"type": "object", "description": "选择器对象"}},
         "required": ["selector"]}},
    {"name": "ui_current_window", "description": "读取当前前台窗口信息。",
     "inputSchema": {"type": "object", "properties": {}}},
    # ---- files 组（前缀 files_）----
    {"name": "files_read", "description": "读取文件内容。",
     "inputSchema": {"type": "object", "properties": {
         "path": {"type": "string", "description": "文件绝对路径"},
         "encoding": {"type": "string", "description": "编码", "enum": ["utf-8", "utf-16", "gbk"], "default": "utf-8"}},
         "required": ["path"]}},
    {"name": "files_write", "description": "写入文件。",
     "inputSchema": {"type": "object", "properties": {
         "path": {"type": "string", "description": "文件绝对路径"},
         "content": {"type": "string", "description": "内容"},
         "encoding": {"type": "string", "description": "编码", "default": "utf-8"},
         "append": {"type": "boolean", "description": "是否追加", "default": False}},
         "required": ["path", "content"]}},
    {"name": "files_list", "description": "列出目录内容。",
     "inputSchema": {"type": "object", "properties": {
         "path": {"type": "string", "description": "目录路径"}},
         "required": ["path"]}},
    # ---- device 组（前缀 device_）----
    {"name": "device_info", "description": "读取设备与 AutoJs6 环境信息。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "device_ping", "description": "连通性测试。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "device_battery", "description": "读取电池电量。",
     "inputSchema": {"type": "object", "properties": {}}},
    # ---- screen 组（前缀 screen_）----
    {"name": "screen_capture", "description": "截取当前屏幕。",
     "inputSchema": {"type": "object", "properties": {
         "format": {"type": "string", "description": "图片格式", "enum": ["jpeg", "png"], "default": "jpeg"},
         "quality": {"type": "integer", "description": "JPEG 质量 1-100", "default": 70},
         "maxWidth": {"type": "integer", "description": "最大宽度", "default": 1080}},
         "required": []}},
    # ---- shell 组（前缀 shell_）----
    {"name": "shell_exec", "description": "执行一条 shell 命令。",
     "inputSchema": {"type": "object", "properties": {
         "cmd": {"type": "string", "description": "命令内容"},
         "timeoutMs": {"type": "integer", "description": "超时毫秒", "default": 10000}},
         "required": ["cmd"]}},
    # ---- console / clipboard / http / image / ocr / media 组 ----
    {"name": "console_tail", "description": "读取 AutoJs6 控制台最近输出。",
     "inputSchema": {"type": "object", "properties": {
         "lines": {"type": "integer", "description": "行数", "default": 100}},
         "required": []}},
    {"name": "clipboard_get", "description": "读取剪贴板。",
     "inputSchema": {"type": "object", "properties": {}}},
    {"name": "clipboard_set", "description": "写入剪贴板。",
     "inputSchema": {"type": "object", "properties": {
         "text": {"type": "string", "description": "文本内容"}},
         "required": ["text"]}},
    {"name": "http_get", "description": "发起 HTTP GET 请求。",
     "inputSchema": {"type": "object", "properties": {
         "url": {"type": "string", "description": "URL"},
         "timeoutMs": {"type": "integer", "description": "超时毫秒", "default": 10000}},
         "required": ["url"]}},
    {"name": "image_match", "description": "在屏幕截图中查找模板图。",
     "inputSchema": {"type": "object", "properties": {
         "templatePath": {"type": "string", "description": "模板图路径"},
         "threshold": {"type": "number", "description": "相似度阈值", "default": 0.85}},
         "required": ["templatePath"]}},
    {"name": "ocr_recognize", "description": "识别屏幕文字。",
     "inputSchema": {"type": "object", "properties": {
         "language": {"type": "string", "description": "语言", "enum": ["chinese", "english"], "default": "chinese"}},
         "required": []}},
    {"name": "media_volume", "description": "读取或设置媒体音量。",
     "inputSchema": {"type": "object", "properties": {
         "action": {"type": "string", "description": "操作", "enum": ["get", "set"], "default": "get"},
         "level": {"type": "integer", "description": "音量级别（set 时）"}},
         "required": []}},
]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("[mcp-fake] %s\n" % (fmt % args))

    def _send(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        try:
            req = json.loads(self.rfile.read(length).decode("utf-8"))
        except Exception:
            self._send(400, {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "parse error"}})
            return
        method = req.get("method")
        rid = req.get("id")
        if method == "initialize":
            result = {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "serverInfo": {"name": "fake-autojs6-mcp", "version": "9.9.9"},
            }
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": result})
        elif method == "notifications/initialized":
            self.send_response(202)
            self.send_header("Content-Length", "0")
            self.end_headers()
        elif method == "tools/list":
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {"tools": TOOLS}})
        elif method == "tools/call":
            name = (req.get("params") or {}).get("name", "")
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {
                "content": [{"type": "text", "text": "FAKE_OK tool=%s" % name}],
                "isError": False,
            }})
        else:
            self._send(200, {"jsonrpc": "2.0", "id": rid, "result": {}})

    def do_GET(self):
        self._send(200, {"ok": True, "note": "fake autojs6 mcp for verification"})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9637
    srv = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    sys.stderr.write("listening on 0.0.0.0:%d, %d tools\n" % (port, len(TOOLS)))
    srv.serve_forever()