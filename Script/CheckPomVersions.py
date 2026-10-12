#!/usr/bin/env python3
"""静态护栏：Maven 模块里第三方构件的版本必须集中定义在 <properties>。

规则（开发约定第 11 条）：
  · `<dependency>` / `<plugin>` 块里的 `<version>` 一律写成 `${...}` 引用，
    版本值只在 `<properties>` 出现一次 —— 升级时只改一处；
  · 例外：项目自身版本（顶层 `<version>`）与 `<parent>` 版本不在此列。

为什么单独做护栏：`pom.xml` 里的版本最容易「顺手写死」，而后果（同一个构件两处版本不一致、
升级只改了一半）在构建期完全看不出来。本脚本只做文本扫描，不解析依赖、不联网、秒级完成。

用法：python3 Script/CheckPomVersions.py [pom 路径]
退出码：0 = 通过；1 = 发现硬编码版本（逐条打印行号）。
"""

import pathlib
import re
import sys

BLOCK_START = re.compile(r"<(dependency|plugin|parent)>")
BLOCK_END = re.compile(r"</(dependency|plugin|parent)>")
VERSION = re.compile(r"<version>(.*?)</version>")

# 父 POM 的版本不参与集中管理（本项目当前未使用 <parent>）
EXEMPT_KINDS = {"parent"}


def find_offenders(pom_path: pathlib.Path):
    """返回 [(行号, 块类型, 块起始行号, 版本值)]，均为硬编码版本。"""
    offenders = []
    kind = None
    block_line = 0
    for no, raw in enumerate(pom_path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        started = BLOCK_START.search(line)
        if started and kind is None:
            kind, block_line = started.group(1), no
        if kind is not None and kind not in EXEMPT_KINDS:
            found = VERSION.search(line)
            if found and "${" not in found.group(1):
                offenders.append((no, kind, block_line, found.group(1).strip()))
        if BLOCK_END.search(line):
            kind = None
    return offenders


def main() -> int:
    pom = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "pom.xml")
    if not pom.is_file():
        print(f"错误：找不到 {pom}", file=sys.stderr)
        return 1
    offenders = find_offenders(pom)
    if not offenders:
        print(f"通过：{pom} 中所有 dependency / plugin 的版本都是 ${{...}} 引用（版本集中在 <properties>）✅")
        return 0
    print(f"失败：{pom} 有 {len(offenders)} 处硬编码的第三方构件版本：", file=sys.stderr)
    for no, kind, block_line, value in offenders:
        print(f"  第 {no} 行（<{kind}> 起始于第 {block_line} 行）：<version>{value}</version>", file=sys.stderr)
    print("修法：把版本值写进 <properties>，此处改为 ${xxx.version} —— 见 Docs/DevelopmentConventions.md 第 11 条", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
