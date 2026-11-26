#!/usr/bin/env python3
"""取某个版本的发布说明，按需附上产物的 SHA-256。

`docs/release-notes.md` 的约定：每个版本一节，标题为 `## v<版本号>`。
CI 在打 tag 时这样生成 Release 正文：

    python tools/release-notes.py 1.0.1 --dist dist > notes.md

本地发版同理（`--dist build/release`），保证「发布说明里的哈希」就是「上传的文件」的哈希。
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
NOTES = ROOT / "docs" / "release-notes.md"


def section(version: str) -> str:
    text = NOTES.read_text(encoding="utf-8")
    start = re.search(rf"^## v{re.escape(version)}(?:\s|$)", text, re.M)
    if not start:
        known = re.findall(r"^## v(\S+)", text, re.M)
        raise SystemExit(f"{NOTES.name} 里没有 v{version}；已有：{', '.join(known) or '无'}")
    rest = text[start.end():]
    nxt = re.search(r"^## v", rest, re.M)
    return text[start.start(): start.end() + (nxt.start() if nxt else len(rest))].rstrip()


def hashes(dist: pathlib.Path) -> str:
    files = sorted(p for p in dist.glob("*") if p.is_file())
    if not files:
        raise SystemExit(f"{dist} 里没有可发布的文件")
    lines = ["", "SHA-256：", "", "```"]
    for f in files:
        lines.append(f"{hashlib.sha256(f.read_bytes()).hexdigest()}  {f.name}")
    lines += ["```", ""]
    return "\n".join(lines)


def main() -> int:
    # Windows 的默认 stdout 编码是 GBK/cp1252，说明里有 ▶ 这类字符时会 UnicodeEncodeError；
    # 统一成 UTF-8（也保证 CI 里写进 Release 的正文不乱码）。
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("version")
    ap.add_argument("--dist", help="产物目录；给了就把每个文件的 SHA-256 附在正文末尾")
    args = ap.parse_args()

    body = section(args.version)
    if args.dist:
        body += "\n" + hashes(pathlib.Path(args.dist))
    print(body)
    return 0


if __name__ == "__main__":
    sys.exit(main())
