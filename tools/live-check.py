#!/usr/bin/env python3
"""对 mcp.txt 里列出的每台手机跑一遍真机链路（LiveMcpTest）。

CI 跑不了这一步：GitHub 的 runner 在你家局域网外面，连不到 192.168.x.x，而且令牌不该进仓库。
所以真机验证只能在本机跑，这个脚本把「改环境变量 → 找对设备 → 看日志」压成一条命令。

配置文件就是 AutoJs6 插件页导出的 MCP 配置，可以有多段（多个 JSON 对象直接拼接）：

    {"mcpServers": {"AutoJs6-Oneplus-12": {"url": "http://192.168.1.6:9637/mcp",
      "headers": {"Authorization": "Bearer <令牌>"}}}}
    {"mcpServers": {"AutoJs6-Meizu-17-Pro": {"url": "http://192.168.1.17:9637/mcp", ...}}}

    python tools/live-check.py --config mcp.txt

令牌只在本进程的环境变量里出现，打印时打码。
"""

from __future__ import annotations

import argparse
import json
import os
import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RESULT = ROOT / "shared" / "build" / "test-results" / "jvmTest" / "TEST-cn.dumate.a6studio.LiveMcpTest.xml"
TEST = "cn.dumate.a6studio.LiveMcpTest"


def load_servers(path: pathlib.Path) -> list[tuple[str, dict]]:
    """读取一份或多份拼接的 MCP 配置（AutoJs6 导出的文件常是两段 JSON 摞在一起）。"""
    text = path.read_text(encoding="utf-8")
    dec = json.JSONDecoder()
    servers: list[tuple[str, dict]] = []
    i = 0
    while i < len(text):
        if text[i].isspace():
            i += 1
            continue
        obj, i = dec.raw_decode(text, i)
        servers += list((obj.get("mcpServers") or {}).items())
    if not servers:
        raise SystemExit(f"{path} 里没有 mcpServers")
    return servers


def mask(token: str) -> str:
    return f"{token[:4]}…{token[-2:]}" if len(token) > 8 else "…"


def gradlew() -> str:
    # 绝对路径：subprocess 在 Windows 上不会在 cwd 里找 gradlew.bat。
    return str(ROOT / ("gradlew.bat" if sys.platform == "win32" else "gradlew"))


def run_one(name: str, cfg: dict) -> bool:
    url = cfg.get("url", "")
    token = (cfg.get("headers") or {}).get("Authorization", "").removeprefix("Bearer ").strip()
    print(f"\n=== {name}  {url}  token={mask(token)}")
    env = dict(os.environ, A6_MCP_URL=url, A6_MCP_TOKEN=token)
    RESULT.unlink(missing_ok=True)
    proc = subprocess.run(
        [gradlew(), ":shared:jvmTest", "--offline", "--console=plain", "--tests", TEST],
        cwd=ROOT, env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
    )
    if not RESULT.exists():
        print(proc.stdout[-2000:] or proc.stderr[-2000:])
        print("FAIL：没有产生测试结果（编译失败或任务被跳过）")
        return False

    xml = RESULT.read_text(encoding="utf-8", errors="replace")
    # 第一条 LIVE 和 CDATA 开头在同一行，所以先取出 CDATA 再按行过滤。
    body = re.search(r"<!\[CDATA\[(.*?)\]\]>", xml, re.S)
    for line in (body.group(1) if body else xml).splitlines():
        if line.startswith(("LIVE ", "SKIP ")):
            print("  " + line.strip())
    failure = re.search(r"<failure[^>]*>(.*?)</failure>", xml, re.S)
    if failure:
        first = failure.group(1).strip().splitlines()[0]
        print("  FAIL: " + first[:300])
        return False
    if "SKIP LiveMcpTest" in xml:
        print("  FAIL：测试自己跳过了，说明环境变量没传进去")
        return False
    return proc.returncode == 0


def main() -> int:
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--config", default=str(ROOT / "mcp.txt"),
                    help="MCP 配置文件（默认仓库根目录的 mcp.txt，已在 .gitignore 里）")
    args = ap.parse_args()

    path = pathlib.Path(args.config)
    if not path.exists():
        raise SystemExit(f"找不到 {path}：从 AutoJs6 插件页复制 MCP 配置存成它（令牌不要提交）")

    servers = load_servers(path)
    print(f"{path} 里 {len(servers)} 台设备")
    results = [(name, run_one(name, cfg)) for name, cfg in servers]

    print("\n==== 汇总")
    for name, ok in results:
        print(f"  {'PASS' if ok else 'FAIL'}  {name}")
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    sys.exit(main())
