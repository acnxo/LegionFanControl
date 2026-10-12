#!/usr/bin/env python3
"""生成 issue #20 复盘的配图（纯标准库 → SVG）。

为什么输出 SVG 而不是 PNG：
  本机（以及 CI 的 Linux runner）没有 PIL / matplotlib / graphviz / ImageMagick，
  而 SVG 是纯文本 XML —— 可用标准库直接生成、可 diff、可无损缩放，GitHub 也能渲染。
  中文标签由阅读端的字体渲染，不需要在仓库里塞字体。

数据约定：
  所有数字**全部取自真机日志**，每个数据集上方标注出处（哪一行、哪个版本）；
  本脚本不做任何插值、拟合或推算式数据，只做可视化。

用法：
    python3 Script/RenderAnalysisCharts.py            # 输出到 Docs/Asset/
    python3 Script/RenderAnalysisCharts.py --out-dir X
"""

from __future__ import annotations

import argparse
import pathlib
from xml.sax.saxutils import escape

# ── 调色 ────────────────────────────────────────────────────────────────
C_BG = "#ffffff"
C_AXIS = "#3b4252"
C_TEXT = "#1f2430"
C_MUTED = "#6b7280"
C_GRID = "#e5e7eb"
C_NATIVE = "#c0392b"   # GraalVM 原生镜像 exe
C_JVM = "#2d7dd2"      # JVM 版
C_MASK = "#7f8c8d"     # 掩码
C_R = "#c0392b"
C_G = "#27ae60"
C_B = "#2d7dd2"

FONT = "'Segoe UI','Microsoft YaHei','PingFang SC','Noto Sans CJK SC',sans-serif"

# ── 数据集（出处逐条标注）────────────────────────────────────────────────

# 出处：各轮 `LCD 分通道[GRAY/LCD 白底 黑字/红字] 墨量(r,g,b)=…` （V0.2.0.00038 起每轮一致）
INK_BY_CHANNEL = [
    ("GRAY 白底 黑字", (93239, 93239, 93239), (93239, 93239, 93239)),
    ("GRAY 白底 红字", (0, 93239, 93239), (0, 93239, 93239)),
    ("LCD 白底 黑字", (126480, 126480, 126480), (90666, 91021, 90713)),
    ("LCD 白底 红字", (0, 126480, 126480), (0, 91021, 90713)),
]

# 出处：`字形剖面[掩码 M 12px 1x]`（两构建相同）与 `字形剖面[合成 M 12px 1x]`
PROFILE_MASK = [387, 5028, 2855, 1831, 1831, 1660, 1660, 1705, 3190, 4506]
PROFILE_NATIVE = [0, 6885, 6885, 3060, 3060, 2295, 3060, 3825, 6885, 6885, 0]
PROFILE_JVM = [324, 4629, 2571, 1670, 1670, 1561, 1493, 1549, 2881, 4215, 0]

# 出处：`掩码矩阵[size=S scale=X] … r/g/b合计=` 与 `合成矩阵[Segoe UI size=S scale=X] 墨量(r,g,b)=`
RATIO = [
    (12, 1.0, 27882, 45900),
    (12, 1.5, 57611, 86700),
    (14, 1.0, 35450, 55335),
    (14, 1.5, 76825, 105825),
    (21, 1.0, 76825, 105825),
    (21, 1.5, 184669, 230265),
]


# ── 极简 SVG 构件 ───────────────────────────────────────────────────────
class Svg:
    def __init__(self, width: int, height: int):
        self.width = width
        self.height = height
        self.parts: list[str] = []

    def rect(self, x, y, w, h, fill, stroke="none", rx=0, opacity=None):
        extra = f' opacity="{opacity}"' if opacity is not None else ""
        self.parts.append(
            f'<rect x="{x:.1f}" y="{y:.1f}" width="{w:.1f}" height="{h:.1f}" '
            f'rx="{rx}" fill="{fill}" stroke="{stroke}"{extra}/>'
        )

    def line(self, x1, y1, x2, y2, stroke=C_AXIS, width=1, dash=None):
        d = f' stroke-dasharray="{dash}"' if dash else ""
        self.parts.append(
            f'<line x1="{x1:.1f}" y1="{y1:.1f}" x2="{x2:.1f}" y2="{y2:.1f}" '
            f'stroke="{stroke}" stroke-width="{width}"{d}/>'
        )

    def text(self, x, y, s, size=13, fill=C_TEXT, anchor="start", weight="normal"):
        self.parts.append(
            f'<text x="{x:.1f}" y="{y:.1f}" font-family="{FONT}" font-size="{size}" '
            f'fill="{fill}" text-anchor="{anchor}" font-weight="{weight}">{escape(s)}</text>'
        )

    def poly(self, pts, stroke, width=2, fill="none"):
        p = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
        self.parts.append(
            f'<polyline points="{p}" fill="{fill}" stroke="{stroke}" stroke-width="{width}"/>'
        )

    def title(self, s, sub=None) -> float:
        """标题 + 可选副标题，返回内容起始 y。"""
        self.text(24, 32, s, size=17, weight="bold")
        if sub:
            self.text(24, 54, sub, size=12, fill=C_MUTED)
            return 78
        return 60

    def render(self) -> str:
        head = (
            f'<svg xmlns="http://www.w3.org/2000/svg" width="{self.width}" '
            f'height="{self.height}" viewBox="0 0 {self.width} {self.height}">'
        )
        return head + f'<rect width="100%" height="100%" fill="{C_BG}"/>' + "".join(self.parts) + "</svg>"


def _assert_inside(svg: Svg):
    """坐标越界自检：SVG 里若出现负数或超出画布，说明布局算错了。"""
    import re

    for s in svg.parts:
        for m in re.finditer(r'(?:x|x1|x2)="(-?[\d.]+)"', s):
            v = float(m.group(1))
            assert -50 <= v <= svg.width + 50, f"x 越界：{v}（{s[:60]}）"
        for m in re.finditer(r'(?:y|y1|y2)="(-?[\d.]+)"', s):
            v = float(m.group(1))
            assert -50 <= v <= svg.height + 50, f"y 越界：{v}（{s[:60]}）"


# ── 图 1：分通道墨量（压平的直接证据）────────────────────────────────────
def chart_ink_per_channel() -> str:
    svg = Svg(940, 470)
    top = svg.title(
        "图 1　LCD 合成把「每通道覆盖」压成了一个标量",
        "分通道墨量（黑字白底 / 红字白底）。原生 exe：三通道逐位相同；JVM 版：三通道不同。数字取自真机日志。",
    )

    groups = INK_BY_CHANNEL
    panel_w, panel_h = 430, 300
    vmax = 140000
    for pi, (pname, values) in enumerate((("GraalVM 原生镜像 exe", 1), ("JVM 版", 2))):
        px = 30 + pi * (panel_w + 40)
        py = top + 40
        svg.rect(px, py, panel_w, panel_h, fill="#fcfeff", stroke=C_GRID, rx=8)
        svg.text(px + 14, py - 10, pname, size=14, weight="bold",
                 fill=C_NATIVE if pi == 0 else C_JVM)

        plot_x, plot_y = px + 54, py + 18
        plot_w, plot_h = panel_w - 74, panel_h - 96
        # y 轴
        svg.line(plot_x, plot_y, plot_x, plot_y + plot_h, stroke=C_AXIS)
        svg.line(plot_x, plot_y + plot_h, plot_x + plot_w, plot_y + plot_h, stroke=C_AXIS)
        for frac in (0, 0.5, 1.0):
            y = plot_y + plot_h * (1 - frac)
            svg.line(plot_x, y, plot_x + plot_w, y, stroke=C_GRID, dash="3 3")
            svg.text(plot_x - 8, y + 4, f"{int(vmax * frac / 1000)}k", size=11,
                     fill=C_MUTED, anchor="end")

        gcount = len(groups)
        slot = plot_w / gcount
        bw = slot / 5.0
        for gi, (label, nat, jvm) in enumerate(groups):
            base = plot_x + slot * gi + slot * 0.18
            data = nat if pi == 0 else jvm
            for ci, c in enumerate((C_R, C_G, C_B)):
                v = data[ci]
                h = plot_h * (v / vmax)
                svg.rect(base + ci * bw, plot_y + plot_h - h, bw * 0.86, max(h, 0.6), fill=c, rx=1)
                svg.text(base + ci * bw + bw * 0.43, plot_y + plot_h - h - 4,
                         f"{v // 1000}k" if v else "0", size=10, fill=C_MUTED, anchor="middle")
            svg.text(base + bw * 1.5, plot_y + plot_h + 18, label.split(" ")[0], size=11,
                     fill=C_TEXT, anchor="middle")
            svg.text(base + bw * 1.5, plot_y + plot_h + 33, label.split(" ")[1], size=10,
                     fill=C_MUTED, anchor="middle")

    svg.text(30, 470 - 18,
             "判据：黑字白底下正确合成必然 out_c = 1 − m_c ⇒ 三通道墨量必须不等；三通道逐位相同 ⇒ 每通道覆盖已被压成一个标量。",
             size=12, fill=C_MUTED)
    _assert_inside(svg)
    return svg.render()


# ── 图 2：逐列剖面（形状保留、值被二值化）───────────────────────────────
def chart_column_profile() -> str:
    svg = Svg(940, 520)
    top = svg.title(
        "图 2　字形形状与几何完全正确，但取值被二值化",
        "单字形 M（Segoe UI / 12px / 1.0x）逐列墨量。原生每一列都是 765 = 3 × 255 的整数倍 ⇒ 每像素每通道非 0 即 255。",
    )

    rows = [
        ("掩码（两构建逐字节相同）", PROFILE_MASK, C_MASK, "sum(r,g,b)"),
        ("原生 exe 合成", PROFILE_NATIVE, C_NATIVE, "每列 765 的整数倍"),
        ("JVM 版合成", PROFILE_JVM, C_JVM, "与掩码同形，约 0.92 倍"),
    ]
    vmax = 8000
    left, right = 210, 60
    plot_w = 940 - left - right
    row_h = 108
    for ri, (name, data, color, note) in enumerate(rows):
        y0 = top + ri * row_h
        svg.text(24, y0 + 30, name, size=13, weight="bold", fill=color)
        svg.text(24, y0 + 48, note, size=10, fill=C_MUTED)
        base_y = y0 + 78
        svg.line(left, base_y, left + plot_w, base_y, stroke=C_AXIS)
        n = len(data)
        slot = plot_w / max(n, 11)
        bw = slot * 0.72
        for i, v in enumerate(data):
            h = (base_y - y0 - 6) * (v / vmax)
            x = left + i * slot + (slot - bw) / 2
            svg.rect(x, base_y - h, bw, max(h, 0.8), fill=color, rx=1,
                     opacity="0.9" if ri else "0.75")
        svg.text(left + plot_w + 6, base_y - 4, f"峰值 {max(data)}", size=10,
                 fill=C_MUTED, anchor="start")
    svg.text(24, 520 - 16,
             "读法：原生那一行与掩码「同形」但取值形态不同（全为 765 的倍数），且总墨量超过掩码每像素最大值之和 —— 取样跨到了相邻像素并被累加。",
             size=12, fill=C_MUTED)
    _assert_inside(svg)
    return svg.render()


# ── 图 3：合成÷掩码 的比值随字号下降（非线性放大的证据）────────────────
def chart_inflation_ratio() -> str:
    svg = Svg(940, 430)
    top = svg.title(
        "图 3　上墨量放大不是线性的：比值随字号下降",
        "纵轴 = 合成单值 ÷ 掩码 Σr。线性缩放应给出恒定比值；实测随字号单调下降，形态与「每像素二次叠加」类非线性一致。",
    )
    plot_x, plot_y = 90, top + 30
    plot_w, plot_h = 780, 260
    svg.line(plot_x, plot_y, plot_x, plot_y + plot_h, stroke=C_AXIS)
    svg.line(plot_x, plot_y + plot_h, plot_x + plot_w, plot_y + plot_h, stroke=C_AXIS)

    rmax = 1.8
    for frac in (0.0, 0.5, 1.0, 1.5):
        y = plot_y + plot_h * (1 - frac / rmax)
        svg.line(plot_x, y, plot_x + plot_w, y, stroke=C_GRID, dash="3 3")
        svg.text(plot_x - 10, y + 4, f"{frac:.2f}", size=11, fill=C_MUTED, anchor="end")

    sizes = [12, 14, 21]
    x_of = {s: plot_x + 110 + sizes.index(s) * 250 for s in sizes}
    for s in sizes:
        svg.text(x_of[s] + 40, plot_y + plot_h + 22, f"{s}px", size=12, fill=C_TEXT, anchor="middle")

    for scale, color, dash in ((1.0, C_NATIVE, None), (1.5, "#e08a1e", "6 4")):
        pts = []
        for s, sc, mask, comp in RATIO:
            if sc != scale:
                continue
            ratio = comp / mask
            pts.append((x_of[s] + 40, plot_y + plot_h * (1 - ratio / rmax)))
        if dash:
            # 虚线折线：用短线段拼接（SVG polyline 不支持整条虚线）
            for (x1, y1), (x2, y2) in zip(pts, pts[1:]):
                svg.line(x1, y1, x2, y2, stroke=color, width=2, dash=dash)
        else:
            svg.poly(pts, stroke=color, width=2)
        for (s, sc, mask, comp), (x, y) in zip(
            [(r[0], r[1], r[2], r[3]) for r in RATIO if r[1] == scale], pts
        ):
            svg.rect(x - 3.5, y - 3.5, 7, 7, fill=color, rx=1.5)
            svg.text(x + 8, y - 8, f"{comp / mask:.2f}", size=11, fill=color)

    svg.text(760, plot_y + 14, "── 1.0x", size=12, fill=C_NATIVE)
    svg.text(760, plot_y + 34, "- - 1.5x", size=12, fill="#e08a1e")
    svg.text(24, 430 - 16,
             "注：掩码取 Σr（Σg/Σb 与之几乎相同，见掩码矩阵）。比值随字号下降 ⇒ 满覆盖像素占比升高时的放大倍数反而变小。",
             size=12, fill=C_MUTED)
    _assert_inside(svg)
    return svg.render()


# ── 图 4：推理与排除图 ─────────────────────────────────────────────────
def chart_reasoning_map() -> str:
    svg = Svg(940, 760)
    top = svg.title(
        "图 4　从现象到结论的推理与排除",
        "每个「排除」都给出可复核依据；结论按证据强度分为「已证机制」与「高置信推定」。",
    )

    def box(x, y, w, h, title, lines, color):
        svg.rect(x, y, w, h, fill="#fbfdff", stroke=color, rx=8)
        svg.rect(x, y, w, 3, fill=color, rx=1.5)
        svg.text(x + 12, y + 24, title, size=13, weight="bold", fill=color)
        for i, ln in enumerate(lines):
            svg.text(x + 12, y + 44 + i * 17, ln, size=11.5, fill=C_TEXT)

    y = top
    box(30, y, 880, 74, "现象（用户可见 + 可量化）", [
        "原生镜像 exe 文字更粗更糊、无次像素色边；真实截图量化：AA 比 0.002 / 色边均值 0.08（JVM 版 0.964 / 44.69）。",
    ], C_NATIVE)
    y += 96
    box(30, y, 880, 92, "判据 1　缺陷只落在 LCD 路径", [
        "同参数离屏对照：Text GRAY 两构建逐项一致（墨量 498320 / 深核 1731 / 中间调 885）；",
        "Text LCD 两侧迥异（原生 665040 / 中间调 0 / 色边 0 vs JVM 485366 / 1171 / 2243）。",
    ], C_MASK)
    y += 114
    svg.line(470, y - 22, 470, y - 2, stroke=C_AXIS, width=2)
    box(30, y, 880, 92, "判据 2　输入相同、输出不同（决定性）", [
        "同一参数（Segoe UI / 12px / 1.0x）：掩码逐字节相同（27882 / 27880 / 27885），",
        "合成却不同（原生 126480×3 三通道逐位相同 vs JVM 90666 / 91021 / 90713）。",
    ], C_JVM)
    y += 114
    svg.line(470, y - 22, 470, y - 2, stroke=C_AXIS, width=2)
    box(30, y, 880, 132, "逐个排除（每条都可按复盘 §4 自行复核）", [
        "DWrite 掩码 · 纹理上传（同一 prism_d3d.dll 的 nUpdateTextureB）· 着色器 .obj 与 *_Loader 常量表",
        "MaskData / GlyphCache 几何与行距 · _AlphaTest 变体（两构建均为 false）· 构建期固化（PrismSettings 44 字段全同；",
        "类初始化审计全部 RUN_TIME）· 环境差异（同机两进程 1.0x 掩码逐字节相同）· 优化等级（-Ob 实测数字逐字相同）",
    ], "#8e44ad")
    y += 154
    box(30, y, 430, 118, "已证机制（实测数字支撑）", [
        "① 每通道覆盖被压成一个标量并二值化；",
        "② 覆盖量超过该像素自身 Σmax（45855 > 40475）",
        "　 ⇒ 取样跨相邻像素并累加。",
    ], "#c0392b")
    box(480, y, 430, 118, "高置信推定（附可证伪条件）", [
        "native-image 编译产物在该路径上的行为差异；",
        "若此推定错，则同机两进程应给出不同掩码、",
        "或配置/类初始化层应有两构建差异（均未观察到）。",
    ], "#e08a1e")
    y += 140
    box(30, y, 880, 96, "未闭环（诚实边界）", [
        "① 未到指令级：具体哪段字节码被编译成了什么 —— 需上游以最小复现定位（材料准备中）。",
        "② 流水线实际取用的掩码（按位置量化的次像素档位）未直接观测 —— 即使不同，处置仍是同一个：绕开 LCD 路径。",
    ], C_MUTED)
    _assert_inside(svg)
    return svg.render()


CHARTS = {
    "issue20-fig1-ink-per-channel.svg": chart_ink_per_channel,
    "issue20-fig2-column-profile.svg": chart_column_profile,
    "issue20-fig3-inflation-ratio.svg": chart_inflation_ratio,
    "issue20-fig4-reasoning-map.svg": chart_reasoning_map,
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", default="Docs/Asset")
    args = ap.parse_args()
    out = pathlib.Path(args.out_dir)
    out.mkdir(parents=True, exist_ok=True)
    for name, fn in CHARTS.items():
        svg = fn()
        p = out / name
        p.write_text(svg, encoding="utf-8")
        print(f"  ✅ {p}  {len(svg) // 1024} KB")


if __name__ == "__main__":
    main()
