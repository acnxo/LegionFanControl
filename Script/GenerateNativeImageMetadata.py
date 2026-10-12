#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 GraalVM 原生镜像的可达性元数据：reflect-config.json 与 jni-config.json。

为什么需要这个脚本
------------------
原生镜像的「反射 / JNI」两份清单**不能靠手写**：漏一条不会构建失败，只会在运行期
（而且是**在用户机器上**）炸，表现为 `ClassNotFoundException` /
`MissingReflectionRegistrationError` / `NoSuchMethodError`，甚至直接 segfault。

本项目就栽过：2026-10-10 真机运行原生包，崩在

    [ WindowsNativeRunloopThread ] WinApplication._runLoop(Native Method) → segfault

当时 `jni-config.json` 只有 18 条，且其中 3 条（`WinScreen`、`font.dwrite.*`）
**类名根本不存在** —— 清单是手写的，没人能保证没漏。

## 清单来源

跨项目复用 **AHA** 项目在 Windows 真机验证过的 JavaFX 25 元数据（同 JavaFX 大版本、
同为 GraalVM for JDK 25）：

| 来源 | 条数 | 怎么来的 |
| --- | --- | --- |
| `jni-config.json` | 85 | 扫描 openjfx **三平台全部 native 源码**里 `FindClass` 与 `Get*ID` 的目标类 |
| `reachability-metadata.json` 的 `reflection` | 440 | tracing agent 采集 + 静态审计；其中 JavaFX/Glass/Prism/Scenario 相关 413 条，另含 43 条标了 `jniAccessible` |

这是技能 `graalvm-reachability-metadata` 里「经验库对照」那条路径 —— 比自己瞎猜强，
但**替代不了真机走查**：清单齐不等于证明完整。

## 裁剪规则

只保留**产物里真实存在**的类：

* 应用与 JavaFX 类 → 以 fat jar 的类清单为准；
* JDK 类（`java.*` / `javax.*` / `sun.*` / `jdk.*`）→ 以 JDK 的 `jmods` 为准。

不在产物里的条目（本项目不含的 gtk / mac 平台类、ES2 管线等）一律丢弃 ——
注册一个不存在的类没有任何作用，只会让构建日志变脏、并掩盖真正的问题。

同时**保留现有文件里仍然有效的条目**（按同一存在性规则过滤），这样反复运行不会
把人工补的条目冲掉。但要注意两件事：

* 旧 schema 的 `reflect-config.json` **表达不了 JNI 可达**
  （`jniAccessible` 是现代 `reachability-metadata.json` 才有的字段），
  所以 AHA 里标了 `jniAccessible` 的条目会被**转写**进 `jni-config.json`；
* 两份文件都是**裸数组、`additionalProperties: false`，写不了注释**。
  所以「为什么是这些类」只能写在本脚本与 `Docs/NativeImageCi.md` 里。

## 用法

    python3 Script/GenerateNativeImageMetadata.py \\
        --aha-dir  /path/to/AHA \\
        --jar      Dist/LegionFanControl.jar \\
        --jdk-home /usr/local/java/25 \\
        --out-dir  src/main/resources/META-INF/native-image

输出会**覆盖** `--out-dir` 下的 `reflect-config.json` 与 `jni-config.json`。
退出码 0 表示生成并自检通过；任何一条不满足存在性规则都会让脚本报错退出。
"""

import argparse
import json
import pathlib
import re
import sys
import zipfile

# ---------------------------------------------------------------------------
# 本项目自有的、必须保留的条目（不来自 AHA，删了就回不去）
# ---------------------------------------------------------------------------

# JavaFX 的 Application 子类：LauncherImpl 用 getConstructor().newInstance() 实例化它，
# closed-world 看不到这条路径 —— 少了它启动即 ClassNotFoundException。
APP_CLASS = "io.github.acnxo.legion.fan.FanControlApp"

# Windows 字体工厂：原清单把包名写错了（写成 font.dwrite，真实包是 font.directwrite），
# 等于从未生效。这几个类有 native 方法，反射与 JNI 两边都要登记。
FONT_CLASSES = [
    "com.sun.javafx.font.directwrite.DWFactory",
    "com.sun.javafx.font.directwrite.DWFontFile",
    "com.sun.javafx.font.directwrite.DWFontStrike",
]

# Windows glass 层在 **native 侧用 Class.forName 按名取类** 的两个类：
#   WinWindow._createWindow  →  WinGestureSupport
#   WinApplication._runLoop  →  WinDnDClipboard
# native-image 的 ClassForNameSupport 只认登记表，缺了就抛 ClassNotFoundException：
# 窗口本身仍能显示，但**手势与拖放支持会静默失效**，控制台刷异常栈。
# （2026-10-11 由最小复现的窗口模式暴露，见 issue #20 相关记录。）
# 注意：这两条在 JNI 侧已由 AHA 清单覆盖，这里只需要补 **reflect**（Class.forName 走反射登记表）。
WIN_GLASS_CLASSES = [
    "com.sun.glass.ui.win.WinGestureSupport",
    "com.sun.glass.ui.win.WinDnDClipboard",
]

# 本项目历史上手工加过、且类确实存在的条目：予以保留（多登记无害，漏登记致命）
KEEP_EXTRA_JNI = [
    "com.sun.glass.ui.win.WinApplication",
    "com.sun.javafx.iio.jpeg.JPEGImageLoader",
    # 屏幕像素诊断（RenderDiagnostics 的「屏幕字形像素」）走 javafx.scene.robot.Robot，
    # native 侧是 GlassRobot / WinRobot：这两个类的成员会被 native 以 Get*ID 按名字找，
    # 不在册则「抓屏幕」这一步会失败（2026-10-10 新增该诊断时补登）。
    "com.sun.glass.ui.GlassRobot",
    "com.sun.glass.ui.win.WinRobot",
]

# 「字形掩码探针」（RenderDiagnostics.probeGlyphMasks）用反射直插字体内部，重放 JavaFX 在
# getLCDMask 里的两步 DWrite 查询 —— 用于判定 issue #20 的根因（WSL 里真机实测）。
# 这些类/成员必须可用于反射访问，否则原生镜像里探针只会打印「不可用」，白跑一轮。
PROBE_REFLECT_CLASSES = [
    # 取字体资源 → strike → glyph
    "com.sun.javafx.font.PrismFontFactory",
    "com.sun.javafx.font.FontResource",
    "com.sun.javafx.font.FontStrike",
    "com.sun.javafx.font.Glyph",
    "com.sun.javafx.geom.transform.BaseTransform",
    # 重放 DWrite 分析：DWGlyph.createAnalysis / IDWriteGlyphRunAnalysis / DWRITE_GLYPH_RUN / RECT
    "com.sun.javafx.font.directwrite.DWGlyph",
    "com.sun.javafx.font.directwrite.DWFontStrike",
    "com.sun.javafx.font.directwrite.IDWriteGlyphRunAnalysis",
    "com.sun.javafx.font.directwrite.DWRITE_GLYPH_RUN",
    "com.sun.javafx.font.directwrite.RECT",
    # PrismSettings 的静态字段需全量可见（判定是否被 native-image 构建期固化）
    "com.sun.prism.impl.PrismSettings",
]

# ---------------------------------------------------------------------------
# FXML 界面（2026-10-12 重构起）：界面结构迁到 FanControlView.fxml，FXMLLoader 在**运行期**
# 按类名实例化这些控件 / 布局，并按名字注入控制器的 @FXML 字段与事件方法 ——
# 与「按名字加载的 JavaFX 类」同理，封闭世界分析看不到它们，缺了就启动即「界面加载失败」。
# 规则：FXML 里新增任何控件 / 布局类，都必须加到这里（详见 Docs/NativeImageCi.md 第 5 节第 20 条）。
# ---------------------------------------------------------------------------
FXML_UI_CLASSES = (
    # FXMLLoader 自身是**按名字加载**的：FXMLLoaderHelper.<clinit> 会
    # Utils.forceInit("javafx.fxml.FXMLLoader") —— 不登记就会被封闭世界分析裁掉，
    # 原生包里表现为启动即 AssertionError: ClassNotFoundException: javafx.fxml.FXMLLoader
    # （2026-10-12 冒烟正面断言抓到，见 Docs/NativeImageCi.md 第 5 节第 20 条）。
    "javafx.fxml.FXMLLoader",
    "javafx.fxml.FXML",
    "javafx.scene.control.Label",
    # setOnAction 声明在 ButtonBase（Button 的父类）上，FXML 绑定 onAction 时按它解析。
    # 2026-10-12 实测：只登记 Button ⇒ MissingReflectionRegistrationError: ButtonBase.setOnAction。
    "javafx.scene.control.ButtonBase",
    # onAction 的形参类型（事件处理器的接口）。
    "javafx.event.EventHandler",
    "javafx.scene.control.Button",
    "javafx.scene.layout.GridPane",
    "javafx.scene.layout.HBox",
    # Pane 是 HBox/GridPane 的父类，而 FXMLLoader 处理 <children> 时是按 Pane 解析
    # getChildren() 的；父类条目不会被子类条目覆盖，必须单独登记。
    # 2026-10-12 实测：漏了它 ⇒ MissingReflectionRegistrationError: Pane.getChildren()。
    "javafx.scene.layout.Pane",
    # getChildren() 的返回值，FXML 继续往集合里 add 子节点。
    "javafx.collections.ObservableList",
    "javafx.scene.layout.ColumnConstraints",
    # FXML 反射调用的成员分散在**祖先类**上：getId/setId/getStyleClass 在 Node，
    # setMaxWidth/setPrefWidth 在 Region，setWrapText/setText 在 Labeled ——
    # allDeclaredMethods 只覆盖本类声明的成员，父类必须单独登记（并且自身也要 allDeclared*）。
    # 2026-10-12 实测：只登记控件类，原生包报 MissingReflectionRegistrationError: Node.getId()。
    "javafx.scene.Node",
    "javafx.scene.Parent",
    "javafx.scene.layout.Region",
    "javafx.scene.control.Control",
    "javafx.scene.control.Labeled",
    # FXML 属性里的枚举：alignment="CENTER" → Pos.valueOf，hgrow="ALWAYS" → Priority.valueOf
    "javafx.geometry.Pos",
    "javafx.scene.layout.Priority",
    # FXMLLoader 解析 fxml 走 StAX：javax.xml.stream.XMLInputFactory.newInstance() 会用
    # Class.forName 找 JDK 内部实现（FactoryFinder），该实现必须登记才进镜像，
    # 否则报 javax.xml.stream.FactoryConfigurationError: Provider … not found
    # （2026-10-12 实测，见 Docs/NativeImageCi.md 第 5 节第 21 条）。
    "com.sun.xml.internal.stream.XMLInputFactoryImpl",
    # JavaFX 的反射工具类会在运行期用**自己的私有 SecureClassLoader** 定义 Trampoline
    # （字节码是 javafx 模块里的一份资源）。native-image 禁止运行期定义类，报：
    #   UnsupportedFeatureError: Classes cannot be defined at runtime … Tried to define class
    # 但 Class.forName 在镜像里是先查 AOT 类注册表的：只要这个类**被编进镜像**，
    # 就根本不会走到私有加载器 ⇒ 登记即强制包含，运行期不再定义。
    # 2026-10-12 实测：登记前启动即崩（栈见 Docs/NativeImageCi.md 第 5 节第 21 条），登记后冒烟通过。
    "com.sun.javafx.reflect.Trampoline",
)

# fx:controller 指定的控制器：FXML 反射注入 @FXML 字段、反射调用 onAction 方法
CONTROLLER_CLASS = "io.github.acnxo.legion.fan.FanControlController"

JDK_PREFIXES = ("java.", "javax.", "sun.", "jdk.", "com.sun.management.")

# ---------------------------------------------------------------------------
# JavaFX 的「按名字加载」清单 —— 静态审计得来，不是猜的
#
# 为什么单独一套：这些类在源码里**不以字面量出现**，封闭世界分析看不到它们，
# 只有运行到那一步才按名字去找 —— 找不到就是 ClassNotFoundException，
# 而**构建期毫无征兆**（2026-10-10 真机：界面刚建就拓在
# `Utils.forceInit → Class.forName("javafx.css.StyleableFloatProperty")`）。
#
# 两类判据（任一命中即登记）：
#   ① 名字拼接 / 字符串加载（如 `com.sun.prism.shader.<name>_Loader`、`PPS<name>Peer`）；
#   ② `Utils.forceInit(Class)` —— 它的实现就是 `Class.forName(名字, true, 类加载器)`，
#      只被它引用的类会被分析丢掉。
#
# 升级 JavaFX 后重扫方式：
#   grep -rhoE 'forceInit\(\s*[A-Za-z0-9_$.]+\.class' <javafx 各模块 sources>/  \
#     | sed -E 's/.*forceInit\(\s*([A-Za-z0-9_$.]+)\.class/\1/' | sort -u
#   grep -rhoE 'Class\.forName\(\s*"[A-Za-z0-9_.$]+"' <同上> | sed 's/.*"\(.*\)"/\1/' | sort -u
# ---------------------------------------------------------------------------

# ① 类族（正则，按产物类清单展开）
JAVAFX_NAME_LOADED_FAMILIES = (
    r"com\.sun\.prism\.shader\.\w+_Loader$",           # 212 个：D3D / ES2 内置 shader
    r"com\.sun\.scenario\.effect\.impl\..*Peer$",         # 效果 peer：PPS<name>Peer / Pr<name>Peer
    r"com\.sun\.scenario\.effect\.impl\..*Delegate$",     # 效果渲染 delegate
    r"com\.sun\.prism\.\w+\.\w*Pipeline$",             # 四条管线（Class.forName("com.sun.prism.<order>.<ORDER>Pipeline")）
    r"com\.sun\.glass\.ui\.\w+\.\w*PlatformFactory$", # 三平台 Glass 工厂
    r"javafx\.css\.Styleable\w*Property$",               # CSS 属性接口：Utils.forceInit 逐个 Class.forName
)

# ② Utils.forceInit(...) 的直接目标（简单名按产物类清单解析成全限定名）
JAVAFX_FORCE_INIT_TARGETS = (
    "AmbientLight", "Arc", "ArcTo", "Box", "Camera", "Canvas", "Circle", "Clipboard",
    "ClosePath", "Control", "CssParser", "CubicCurve", "CubicCurveTo", "Cylinder",
    "DirectionalLight", "Dragboard", "Ellipse", "EmbeddedWindow", "Font", "Group",
    "HLineTo", "HeaderButtonOverlay", "Image", "ImageView", "Interpolator", "KeyValue",
    "Labeled", "LightBase", "Line", "LineTo", "Material", "Mesh", "MeshView", "MoveTo",
    "Node", "Pane", "ParallelCamera", "Parent", "Path", "PathElement", "PerspectiveCamera",
    "PointLight", "Polygon", "Polyline", "PopupWindow", "PreviewFeature", "Printer",
    "QuadCurve", "QuadCurveTo", "Rectangle", "Region", "Rule", "SVGPath", "Scene", "Shape",
    "Shape3D", "Sphere", "SpotLight", "Stage", "StyleableBooleanProperty",
    "StyleableDoubleProperty", "StyleableFloatProperty", "StyleableIntegerProperty",
    "StyleableLongProperty", "StyleableObjectProperty", "StyleableStringProperty",
    "SubScene", "TableColumnBase", "Text", "TextFlow", "TouchPoint", "Transform",
    "TriangleMesh", "VLineTo", "Window", "javafx.scene.effect.Effect",
)

# ③ Class.forName("字面量") 的目标（同一个 grep 的另一半；非 JavaFX 的如 java.nio.ByteOrder 不列）
JAVAFX_CLASS_FOR_NAME = (
    "com.sun.glass.ui.Screen",
    "com.sun.javafx.embed.swing.SwingFXUtilsImpl",
    "com.sun.javafx.iio.java2d.J2DImageLoaderFactory",
    "com.sun.prism.GraphicsPipeline",
)


def load_class_set_from_jar(jar_path: pathlib.Path) -> set:
    """fat jar 里的类全名集合。"""
    with zipfile.ZipFile(jar_path) as zf:
        return {
            name[:-len(".class")].replace("/", ".")
            for name in zf.namelist()
            if name.endswith(".class")
        }


def load_class_set_from_jdk(jdk_home: pathlib.Path) -> set:
    """JDK 的 jmods 里的类全名集合（jmod 本身就是 zip）。

    jmod 的条目形如 `classes/java/lang/Object.class`，所以要去掉 `classes/` 前缀。
    """
    classes = set()
    for jmod in sorted((jdk_home / "jmods").glob("*.jmod")):
        with zipfile.ZipFile(jmod) as zf:
            for name in zf.namelist():
                if name.endswith(".class"):
                    classes.add(name[len("classes/"):-len(".class")].replace("/", "."))
    return classes


def to_old_style(entry: dict) -> dict:
    """现代 `reachability-metadata.json` 的 reflection 条目 → 旧 schema 的 reflect-config 条目。

    `type` → `name`；`jniAccessible` 在旧 schema 里没有对应字段，直接丢弃
    （它由调用方转写到 jni-config.json）。
    """
    out = {"name": entry["type"]}
    for key in (
        "allDeclaredConstructors",
        "allPublicConstructors",
        "allDeclaredMethods",
        "allPublicMethods",
        "allDeclaredFields",
        "allPublicFields",
        "allDeclaredClasses",
        "allPublicClasses",
    ):
        if entry.get(key):
            out[key] = True
    for key in ("methods", "fields", "constructors"):
        if entry.get(key):
            out[key] = entry[key]
    return out


def all_declared(name: str) -> dict:
    """JNI 条目统一用全量：native 按名字 + 签名查成员，逐条列容易漏。

    这条结论来自 AHA 的两次踩坑（issue #37 / #39）：成员都声明在注册类自己身上，
    且签名会跨版本漂移，因此宁可多、不可少。
    """
    return {
        "name": name,
        "allDeclaredConstructors": True,
        "allDeclaredMethods": True,
        "allDeclaredFields": True,
    }


FLAGS = (
    "allDeclaredConstructors",
    "allPublicConstructors",
    "allDeclaredMethods",
    "allPublicMethods",
    "allDeclaredFields",
    "allPublicFields",
    "allDeclaredClasses",
    "allPublicClasses",
)


def _members(entry: dict, key: str) -> list:
    return list(entry.get(key) or [])


def _merge_members(a: dict, b: dict, key: str) -> list:
    """把两个条目里的显式成员数组并起来（按内容去重，保持确定顺序）。"""
    seen, out = set(), []
    for item in _members(a, key) + _members(b, key):
        ident = (item.get("name"), tuple(item.get("parameterTypes") or ()))
        if ident in seen:
            continue
        seen.add(ident)
        out.append(item)
    out.sort(key=lambda i: (i.get("name") or "", tuple(i.get("parameterTypes") or ())))
    return out


def merge_entries(a: dict, b: dict) -> dict:
    """同一个类的两条条目 → 一条「并集」条目。

    为什么必须求并集、而不是「谁先到算谁」：
    同一个类可能同时出现在「已有清单」与「AHA 清单」里，两侧的写法不一样 ——
    已有清单常用 `allDeclared*`（全量），AHA 清单常用逐条 `methods`（精确）。
    若采用「首个条目生效」，窄的那条会把宽的那条**覆盖掉**，造成**静默的覆盖回归**：
    清单条数变多了，但某些类的可达性反而变差了。

    本项目 2026-10-10 真实踩过：253 个类因此丢掉 `allDeclared*`（含 `D3DPipeline` /
    `SWPipeline` / `WinPlatformFactory` / `QuantumToolkit` / `DWFactory`），
    产物构建全绿，真机却停在

        Graphics Device initialization failed for :  d3d, sw
        Error initializing QuantumRenderer: no suitable pipeline found

    —— 管线靠 `Class.forName` + 取构造器加载，构造器没登记就拿不到。

    判定规则：某类已声明对应的 bulk 标志时，显式成员是冗余的（bulk 已覆盖），
    丢弃以避免「bulk + 显式」的混搭形状；否则保留两侧显式成员的并集。
    """
    assert a["name"] == b["name"]
    out = {"name": a["name"]}
    for flag in FLAGS:
        if a.get(flag) or b.get(flag):
            out[flag] = True
    members = (
        ("methods", "allDeclaredMethods", "allPublicMethods"),
        ("fields", "allDeclaredFields", "allPublicFields"),
        ("constructors", "allDeclaredConstructors", "allPublicConstructors"),
    )
    for key, bulk_a, bulk_b in members:
        if out.get(bulk_a) or out.get(bulk_b):
            continue  # 已被 bulk 覆盖，显式成员冗余
        merged = _merge_members(a, b, key)
        if merged:
            out[key] = merged
    return out


def dedup_sort(entries) -> list:
    """按 name 合并同类条目并排序，保证生成结果稳定（同输入必得同输出）。"""
    by_name = {}
    for e in entries:
        if e["name"] in by_name:
            by_name[e["name"]] = merge_entries(by_name[e["name"]], e)
        else:
            by_name[e["name"]] = e
    return [by_name[k] for k in sorted(by_name)]


def covers(before: dict, after: dict) -> list:
    """after 是否完整覆盖 before？返回丢失项（空列表 = 无回归）。

    这是对「合并语义」的硬守卫：清单可以变多，但**不许有任何一个类的可达性变差**。
    """
    lost = [f for f in FLAGS if before.get(f) and not after.get(f)]
    for key, bulk_a, bulk_b in (
        ("methods", "allDeclaredMethods", "allPublicMethods"),
        ("fields", "allDeclaredFields", "allPublicFields"),
        ("constructors", "allDeclaredConstructors", "allPublicConstructors"),
    ):
        if after.get(bulk_a) or after.get(bulk_b):
            continue  # 被 bulk 覆盖，不丢
        have = {(i.get("name"), tuple(i.get("parameterTypes") or ())) for i in _members(after, key)}
        for item in _members(before, key):
            if (item.get("name"), tuple(item.get("parameterTypes") or ())) not in have:
                lost.append(f"{key}:{item.get('name')}")
    return lost


def expand_name_loaded(classes: set) -> list:
    """把「按名字加载」的类族展开成具体类名（只取产物里真实存在的）。"""
    out = []
    for pattern in JAVAFX_NAME_LOADED_FAMILIES:
        rx = re.compile(pattern)
        out += sorted(c for c in classes if rx.search(c))
    return out


def resolve_force_init(classes: set) -> list:
    """把 forceInit 的简单类名解析成全限定名。

    源码里的写法是 `forceInit(Node.class)`（已 import），所以只拿到简单名。
    这里按产物类清单反查：**凡是以该简单名结尾、且在 JavaFX 前缀下的类全部登记**
    （宁可多不可少；同名类真的有多个就都登记）。
    """
    prefixes = ("javafx.", "com.sun.javafx.", "com.sun.glass.", "com.sun.prism.", "com.sun.scenario.")
    out = []
    for item in JAVAFX_FORCE_INIT_TARGETS:
        if "." in item:
            out.append(item)
            continue
        out += sorted(c for c in classes if c.endswith("." + item) and c.startswith(prefixes))
    return out


def dump(path: pathlib.Path, entries: list) -> None:
    path.write_text(
        json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> int:
    ap = argparse.ArgumentParser(description="生成原生镜像的 reflect/jni 元数据")
    ap.add_argument("--aha-dir", required=True, type=pathlib.Path, help="AHA 仓库根目录")
    ap.add_argument("--jar", required=True, type=pathlib.Path, help="fat jar（用于判定类是否存在）")
    ap.add_argument("--jdk-home", required=True, type=pathlib.Path, help="JDK 根目录（读 jmods）")
    ap.add_argument("--out-dir", required=True, type=pathlib.Path, help="输出目录")
    args = ap.parse_args()

    # ---------- 1. 产物里真实存在的类 ----------
    exist = load_class_set_from_jar(args.jar) | load_class_set_from_jdk(args.jdk_home)
    print(f"产物类集合：fat jar + jmods 共 {len(exist)} 个类")

    def exists(name: str) -> bool:
        # 数组类型（AHA 清单里有 `com.sun.glass.ui.Screen[]`）无法作为类登记
        return not name.endswith("[]") and name in exist

    # ---------- 2. 读 AHA 的两份清单 ----------
    aha_root = args.aha_dir.resolve()
    aha_jni_paths = sorted(aha_root.rglob("jni-config.json"))
    aha_meta_paths = sorted(aha_root.rglob("reachability-metadata.json"))
    if not aha_jni_paths or not aha_meta_paths:
        print("错误：在 --aha-dir 下找不到 jni-config.json / reachability-metadata.json", file=sys.stderr)
        return 2
    aha_jni = json.loads(aha_jni_paths[-1].read_text(encoding="utf-8"))
    aha_reflection = json.loads(aha_meta_paths[-1].read_text(encoding="utf-8"))["reflection"]
    print(f"AHA 来源：jni {len(aha_jni)} 条；reflection {len(aha_reflection)} 条")

    # 只取 JavaFX / Glass / Prism / Scenario 相关的反射条目 —— 其余属于 AHA 自己的技术栈
    # （picocli / Jackson / sqlite 等），与本项目无关。
    jfx_prefixes = (
        "javafx.",
        "com.sun.javafx.",
        "com.sun.glass.",
        "com.sun.prism.",
        "com.sun.scenario.",
        "com.sun.media.",
    )

    # ---------- 3. 汇成两份目标清单 ----------
    reflect = []
    jni = []

    for entry in aha_reflection:
        name = entry["type"]
        if not name.startswith(jfx_prefixes):
            continue
        if not exists(name):
            continue
        reflect.append(to_old_style(entry))
        # 关键一步：现代 schema 的 `jniAccessible` 在旧 schema 里表达不了，
        # 必须转写到 jni-config.json，否则「类在册、JNI 不可达」的坑照旧。
        if entry.get("jniAccessible"):
            jni.append(all_declared(name))

    for entry in aha_jni:
        if exists(entry["name"]):
            jni.append(all_declared(entry["name"]))

    # JavaFX「按名字加载」的三类来源（见文件头的长注释）
    named = (
        expand_name_loaded(exist)
        + resolve_force_init(exist)
        + list(JAVAFX_CLASS_FOR_NAME)
    )
    added_named = 0
    for name in named:
        if exists(name) and name not in {e["name"] for e in reflect}:
            reflect.append(
                {"name": name, "allDeclaredConstructors": True,
                 "allDeclaredMethods": True, "allDeclaredFields": True}
            )
            added_named += 1
    print(f"JavaFX 按名字加载：候选 {len(named)} 个，新增登记 {added_named} 个")

    # FXML 界面：控制器（反射注入字段 / 调用事件方法）与 FXML 里用到的控件、布局类
    added_fxml = 0
    for name in (CONTROLLER_CLASS, *FXML_UI_CLASSES):
        if exists(name) and name not in {e["name"] for e in reflect}:
            if name == CONTROLLER_CLASS:
                reflect.append(all_declared(name))     # 控制器需要 allDeclaredFields（@FXML 注入）
            else:
                reflect.append(all_declared(name))
            added_fxml += 1
    print(f"FXML 界面：候选 {1 + len(FXML_UI_CLASSES)} 个，新增登记 {added_fxml} 个")

    for name in (APP_CLASS, *FONT_CLASSES, *WIN_GLASS_CLASSES):
        if exists(name):
            reflect.append({"name": name, "allDeclaredConstructors": True, "allDeclaredMethods": True})
        else:
            print(f"错误：本项目的必需类不存在于产物中：{name}", file=sys.stderr)
            return 3
    for name in PROBE_REFLECT_CLASSES:
        if exists(name):
            reflect.append({"name": name, "allDeclaredConstructors": True,
                            "allDeclaredMethods": True, "allDeclaredFields": True})
        else:
            print(f"错误：字形掩码探针所需类不存在于产物中：{name}", file=sys.stderr)
            return 3
    for name in (*FONT_CLASSES, *KEEP_EXTRA_JNI):
        if exists(name):
            jni.append(all_declared(name))

    # ---------- 4. 保留现有文件里仍然有效的条目 ----------
    # 记下旧条目，稍后逐条做「覆盖回归」断言。
    previous = {}
    for fname, bucket in (("reflect-config.json", reflect), ("jni-config.json", jni)):
        old = args.out_dir / fname
        if not old.exists():
            continue
        kept = 0
        for entry in json.loads(old.read_text(encoding="utf-8")):
            if exists(entry["name"]):
                bucket.append(entry)
                previous.setdefault(fname, {})[entry["name"]] = entry
                kept += 1
        print(f"  {fname}：保留现有有效条目 {kept} 条")

    before = {k: dict(v) for k, v in previous.items()}
    reflect, jni = dedup_sort(reflect), dedup_sort(jni)

    # ---------- 5. 自检：每一条都必须在产物里存在 ----------
    for label, items in (("reflect-config.json", reflect), ("jni-config.json", jni)):
        bad = [e["name"] for e in items if not exists(e["name"])]
        if bad:
            print(f"错误：{label} 中存在产物里没有的类：{bad[:10]}", file=sys.stderr)
            return 4
        print(f"  {label}：{len(items)} 条，全部经存在性校验 ✅")

    # 类族完整性：每个「按名字加载」的类族都必须 100% 在册 —— 这是防「运行期才拓」的硬指标
    reg_names = {e["name"] for e in reflect}
    incomplete = 0
    for pattern in JAVAFX_NAME_LOADED_FAMILIES:
        rx = re.compile(pattern)
        members = sorted(c for c in exist if rx.search(c))
        missing = [c for c in members if c not in reg_names]
        incomplete += len(missing)
        flag = "✅" if not missing else "❌"
        print(f"  类族 {flag} {pattern}：产内 {len(members)} 个，未登记 {len(missing)} 个")
        for c in missing[:5]:
            print(f"      缺 {c}", file=sys.stderr)
    if incomplete:
        print(f"错误：按名字加载的类族仍有 {incomplete} 个未登记（运行期必然拓）", file=sys.stderr)
        return 7

    # ---------- 6. 断言：相对旧文件没有任何一个类的可达性变差 ----------
    # 这一条是 2026-10-10 那次真机「no suitable pipeline found」的直接产物：
    # 当时条数从 259 涨到 408，但 253 个类反向丢了 allDeclared*，构建全绿、真机崩。
    for fname, items in (("reflect-config.json", reflect), ("jni-config.json", jni)):
        now = {e["name"]: e for e in items}
        regressions = []
        for name, old_entry in before.get(fname, {}).items():
            lost = covers(old_entry, now.get(name, {"name": name}))
            if lost:
                regressions.append(f"{name} -> 丢失 {', '.join(lost)}")
        if regressions:
            print(f"错误：{fname} 出现覆盖回归（{len(regressions)} 个类），禁止写出：", file=sys.stderr)
            for line in regressions[:10]:
                print("   " + line, file=sys.stderr)
            return 6
        print(f"  {fname}：相对旧文件无覆盖回归 ✅（逐类核对 {len(before.get(fname, {}))} 条）")

    args.out_dir.mkdir(parents=True, exist_ok=True)
    dump(args.out_dir / "reflect-config.json", reflect)
    dump(args.out_dir / "jni-config.json", jni)

    # JNI 清单里必须含这几个「native 会按名字回调」的基础类，缺一个就是 segfault。
    jni_names = {e["name"] for e in jni}
    for must in ("java.lang.Runnable", "java.lang.Object", "java.lang.String",
                 "com.sun.glass.ui.Window", "com.sun.glass.ui.View",
                 "com.sun.glass.ui.win.WinWindow", "com.sun.glass.ui.EventLoop"):
        if must not in jni_names:
            print(f"错误：JNI 清单缺少关键类 {must}", file=sys.stderr)
            return 5
    print("\n生成完成 ✅  关键 JNI 类齐全（Runnable / Object / Window / View / WinWindow / EventLoop）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
