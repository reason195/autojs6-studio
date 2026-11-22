#!/usr/bin/env python3
r"""打包 Windows 免安装版 zip。

用 Python 的 zipfile 而不是 `Compress-Archive`：PowerShell 版本的条目分隔符是反斜杠，
在 Linux/macOS 上解压会得到一个叫 `AutoJs6 Studio\runtime\bin\...` 的文件，而不是目录。
这里固定用 `/`，与本机历史和 CI 产物保持一致。

    python tools/make-zip.py --out build/release/AutoJs6Studio-1.0.1-windows-x64.zip
"""

from __future__ import annotations

import argparse
import pathlib
import sys
import zipfile

DEFAULT_SRC = "desktop/build/compose/binaries/main/app/AutoJs6 Studio"


def main() -> int:
    # Windows 的默认 stdout 编码是 GBK/cp1252，说明里有 ▶ 这类字符时会 UnicodeEncodeError；
    # 统一成 UTF-8（也保证 CI 里写进 Release 的正文不乱码）。
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, ValueError):
        pass

    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--src", default=DEFAULT_SRC, help=f"打包目录（默认 {DEFAULT_SRC}）")
    ap.add_argument("--out", required=True, help="输出 zip 路径")
    args = ap.parse_args()

    src = pathlib.Path(args.src)
    if not src.is_dir():
        print(f"找不到目录：{src}（先跑 ./gradlew :desktop:createDistributable）", file=sys.stderr)
        return 1

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.unlink(missing_ok=True)

    files = sorted(p for p in src.rglob("*") if p.is_file())
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for p in files:
            z.write(p, p.relative_to(src).as_posix())

    names = zipfile.ZipFile(out).namelist()
    backslashes = [n for n in names if "\\" in n]
    if backslashes:
        print(f"条目里有反斜杠，解压会出错：{backslashes[:3]}", file=sys.stderr)
        return 1
    print(f"{out}  {len(files)} 个文件  {out.stat().st_size / 1048576:.1f} MB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
