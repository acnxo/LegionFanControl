#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""对比两张同界面截图，判定文字**是否带抗锯齿**、是灰度还是次像素。

为什么需要它：`RenderDiagnostics` 的「字形像素」走的是 `Node.snapshot`（离屏），
实测**两个构建的离屏快照都有灰度抗锯齿**，看不出屏幕上的差异；而屏幕差异恰恰是
issue #20 的本体。屏幕上有没有抗锯齿，只能从**截图**上量。

判定依据（两者都与「墨量」不同，不受内容/字号影响）：

* **AA 比 = 中间调像素 / 深核像素**：有抗锯齿时，字形边缘一定有部分覆盖（灰值 80~200）
  的像素；纯二值栅格化的图里这类像素**接近 0**。
* **次像素色边 = 文字像素上 max(|R-G|, |G-B|) 的均值**：ClearType/次像素渲染会让
  竖笔画左右出现橙/蓝边；灰度抗锯齿是**纯灰**，该值≈0。

用法::

    python3 Script/CompareScreenshots.py 原生.png JVM.png
    python3 Script/CompareScreenshots.py 原生.png JVM.png --zoom 225,73,150,21 放大对比.png

纯标准库实现（自带 PNG 解码），无需 Pillow / numpy。
"""

from __future__ import annotations

import argparse
import struct
import sys
import zlib

# 像素分类阈值：深核=笔画实心处，中间调=边缘部分覆盖，其余算背景
DARK_MAX = 80
MID_MAX = 200


def read_png(path: str) -> tuple[int, int, int, bytearray]:
    """解码 8 位、非隔行 PNG，返回 (宽, 高, 通道数, RGB 原始字节)。"""
    data = open(path, "rb").read()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path}: 不是 PNG 文件")
    pos, idat, w, h, bd, ct, inter = 8, b"", None, None, None, None, None
    while pos < len(data):
        (ln,) = struct.unpack(">I", data[pos:pos + 4])
        typ = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + ln]
        pos += 12 + ln
        if typ == b"IHDR":
            w, h, bd, ct, _comp, _filt, inter = struct.unpack(">IIBBBBB", chunk)
        elif typ == b"IDAT":
            idat += chunk
        elif typ == b"IEND":
            break
    if bd != 8 or inter != 0:
        raise ValueError(f"{path}: 只支持 8 位非隔行 PNG（实际 bd={bd} interlace={inter}）")
    ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(idat)
    stride, out, prev, p = w * ch, bytearray(h * w * ch), bytearray(w * ch), 0
    for y in range(h):
        ft = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if ft == 1:                                   # Sub
            for i in range(ch, stride):
                line[i] = (line[i] + line[i - ch]) & 255
        elif ft == 2:                                 # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif ft == 3:                                 # Average
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif ft == 4:                                 # Paeth
            for i in range(stride):
                a = line[i - ch] if i >= ch else 0
                b, c = prev[i], (prev[i - ch] if i >= ch else 0)
                pp = a + b - c
                pa, pb, pc = abs(pp - a), abs(pp - b), abs(pp - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, ch, out


def write_png(path: str, w: int, h: int, rgb: bytearray) -> None:
    """写 8 位 RGB PNG（行过滤器恒为 0）。"""
    def chunk(tag: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + tag + payload
                + struct.pack(">I", zlib.crc32(tag + payload) & 0xFFFFFFFF))

    raw = b"".join(b"\x00" + bytes(rgb[y * w * 3:(y + 1) * w * 3]) for y in range(h))
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n"
                          + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0))
                          + chunk(b"IDAT", zlib.compress(raw, 6))
                          + chunk(b"IEND", b""))


def gray_of(w: int, h: int, ch: int, buf: bytearray) -> bytearray:
    """取灰度图。"""
    g = bytearray(w * h)
    for i in range(w * h):
        o = i * ch
        if ch >= 3:
            g[i] = (299 * buf[o] + 587 * buf[o + 1] + 114 * buf[o + 2]) // 1000
        else:
            g[i] = buf[o]
    return g


def measure(w: int, h: int, ch: int, buf: bytearray,
            box: tuple[int, int, int, int] | None = None,
            edge_only: bool = False) -> dict:
    """统计文字像素的三个指标；box=(x,y,宽,高) 时只统计该区域。

    edge_only=True 时只统计「紧邻强边」的像素（与邻域灰度差 >= 64）。
    整屏截图里背景占比巨大，直接全图统计会被背景冲淡；只取文字边缘像素
    可以在**不需要预先知道文字位置**的前提下拿到可比的三个指标。
    """
    x0, y0, bw, bh = box or (0, 0, w, h)
    g = gray_of(w, h, ch, buf)

    def is_edge(x: int, y: int) -> bool:
        """是否属于「文字边缘」：自身是中间调，且周围有深核像素。

        只判「与邻域差异大」会把按钮渐变、窗口圆角/阴影也算进来（实测会污染指标）；
        要求附近存在深核像素，就能只留下真正的字形边缘。
        """
        if g[y * w + x] >= MID_MAX:
            return False
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                nx, ny = x + dx, y + dy
                if 0 <= nx < w and 0 <= ny < h and g[ny * w + nx] < DARK_MAX:
                    return True
        return False

    dark = mid = text_px = fringe_sum = 0
    for y in range(y0, min(y0 + bh, h)):
        for x in range(x0, min(x0 + bw, w)):
            if edge_only and not is_edge(x, y):
                continue
            o = (y * w + x) * ch
            if ch >= 3:
                r, gg, b = buf[o], buf[o + 1], buf[o + 2]
            else:
                r = gg = b = buf[o]
            gray = g[y * w + x]
            if gray < DARK_MAX:
                dark += 1
            elif gray < MID_MAX:
                mid += 1
            if gray < MID_MAX:            # 只在「有墨」的像素上量色边
                text_px += 1
                fringe_sum += max(abs(r - gg), abs(gg - b))
    return {
        "dark": dark,
        "mid": mid,
        "aa_ratio": (mid / dark) if dark else float("inf"),
        "fringe": (fringe_sum / text_px) if text_px else 0.0,
        "text_px": text_px,
    }


def verdict(m: dict) -> str:
    if m["mid"] == 0 or m["aa_ratio"] < 0.05:
        return "无抗锯齿（纯二值栅格化）"
    if m["fringe"] >= 2.0:
        return f"次像素抗锯齿（ClearType 类，色边 {m['fringe']:.1f}）"
    return f"灰度抗锯齿（纯灰，色边 {m['fringe']:.1f}）"


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="对比两张同界面截图，判定文字抗锯齿类型")
    ap.add_argument("a", help="截图 A（如原生包）")
    ap.add_argument("b", help="截图 B（如 JVM 版）")
    ap.add_argument("--label-a", default="A")
    ap.add_argument("--label-b", default="B")
    ap.add_argument("--box", help="只统计该区域，格式 x,y,宽,高")
    ap.add_argument("--all", action="store_true",
                    help="不做「只看文字边缘像素」的过滤（默认无 --box 时自动开启）")
    ap.add_argument("--zoom", nargs=2, metavar=("x,y,宽,高", "输出.png"),
                    help="额外输出放大 N 倍的逐像素对比图（最近邻，保留真实像素）")
    ap.add_argument("--scale", type=int, default=6, help="放大倍数，默认 6")
    args = ap.parse_args(argv)

    box = None
    if args.box:
        parts = [int(v) for v in args.box.split(",")]
        if len(parts) != 4:
            ap.error("--box 需要 x,y,宽,高 四个整数")
        box = tuple(parts)

    imgs = {}
    edge_only = (box is None) and not args.all
    for path, label in ((args.a, args.label_a), (args.b, args.label_b)):
        w, h, ch, buf = read_png(path)
        m = measure(w, h, ch, buf, box, edge_only)
        imgs[label] = (path, w, h, ch, buf, m)
        scope = "全图" if args.all or box else "文字边缘像素"
        print(f"[{label}] {path}  {w}x{h}   统计范围：{scope}")
        print(f"    深核像素(<{DARK_MAX})={m['dark']}  中间调像素({DARK_MAX}~{MID_MAX})={m['mid']}"
              f"  AA 比={m['aa_ratio']:.3f}  色边均值={m['fringe']:.2f}")
        print(f"    → 判定：{verdict(m)}")

    la, lb = args.label_a, args.label_b
    if imgs[la][2:4] == imgs[lb][2:4]:
        wa, ha, cha, bufa, _ = imgs[la][1:]
        _, _, _, bufb, _ = imgs[lb][1:]
        n = wa * ha
        diff = sum(abs(bufa[i] - bufb[i]) for i in range(0, n * cha, cha)) / n
        print(f"\n同尺寸逐像素差（灰度）均值={diff:.3f}"
              f"  → {'基本一致' if diff < 1 else '存在真实差异'}")

    if args.zoom:
        zbox, zout = args.zoom
        parts = [int(v) for v in zbox.split(",")]
        zx, zy, zw, zh = parts
        s = args.scale
        cw, gap = zw * s, 8
        oh = gap + 2 * (zh * s + gap)
        canvas = bytearray(b"\xcc" * (cw * oh * 3))
        for k, label in enumerate((la, lb)):
            _path, w2, _h2, ch2, buf2, _m = imgs[label]
            oy = gap + k * (zh * s + gap)
            for j in range(zh):
                for i in range(zw):
                    o = ((zy + j) * w2 + zx + i) * ch2
                    if ch2 >= 3:
                        r, g, b = buf2[o], buf2[o + 1], buf2[o + 2]
                    else:
                        r = g = b = buf2[o]
                    for dy in range(s):
                        row = ((oy + j * s + dy) * cw + i * s) * 3
                        for dx in range(s):
                            p = row + dx * 3
                            canvas[p], canvas[p + 1], canvas[p + 2] = r, g, b
        write_png(zout, cw, oh, canvas)
        print(f"\n放大对比图已写出：{zout}（上={la}，下={lb}，{s} 倍最近邻）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
