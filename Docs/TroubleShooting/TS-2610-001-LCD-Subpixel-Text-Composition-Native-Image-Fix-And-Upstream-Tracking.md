# TS-2610-001 · 原生包文本渲染退化（GraalVM native-image 下 LCD 次像素文本合成）—— 修复进展与上游跟踪

> 返回 [项目主页](../../README.md) · 相关文档：[已知限制与路线图](../LimitationsAndRoadmap.md) · [原生镜像 CI 与发布](../NativeImageCi.md)

| 项 | 内容 |
| --- | --- |
| 编号 | TS-2610-001 |
| 日期 | 2026-10 |
| 状态 | 本仓库**已缓解**（原生包默认走灰度路径）；**代码级根因尚未定位**；上游已受理、**尚未修复** |
| 影响面 | 本仓库**原生包**（`…-native-windows-x64`）在 Windows x64 上的文本观感；JVM 版不受影响 |
| 触发条件 | GraalVM `native-image` 产物 + JavaFX **LCD 次像素文本合成**路径 |
| 本仓库处置 | issue [#48](https://github.com/cnxnc/LegionFanControl/issues/48) / PR [#54](https://github.com/cnxnc/LegionFanControl/pull/54)：原生包默认 `prism.lcdtext=false`，**可开关、可回退** —— 见第 8 节 |
| 证据与复现工程 | 最小复现仓库 [`acnxo/LCDSubpixelMinimalReproduction`](https://github.com/acnxo/LCDSubpixelMinimalReproduction)（44 行有效代码 + 单一判据） |
| 上游跟踪入口 | [`oracle/graal#14653`](https://github.com/oracle/graal/issues/14653)（GraalVM 侧）；最小复现仓库英文跟踪 issue [#23](https://github.com/acnxo/LCDSubpixelMinimalReproduction/issues/23) |
| 本仓库相关 issue | [#20](https://github.com/cnxnc/LegionFanControl/issues/20)（根因复盘）、[#48](https://github.com/cnxnc/LegionFanControl/issues/48)（产品缓解） |

---

## 1. 现象

同一份代码、同一台机器、同一套运行参数，只在构建形态之间对比：

* **JVM 版**（`LegionFanControl.jar`）：界面文字走 **LCD 次像素合成**，字形边缘有灰度过渡与次像素色边，
  `R` / `G` / `B` 三通道覆盖量**互不相等**；
* **原生包**（`LegionFanControl.exe`）：同一处文字**更粗、更糊、没有次像素色边**（用户原话「发虚」），
  三通道覆盖量**逐位相同**。

关键在于：**整个过程不抛任何异常**（静默退化），因此不会被日志或退出码暴露，只能靠量化指标或肉眼识别。

## 2. 影响与当前状态

| 项 | 说明 |
| --- | --- |
| 用户可见影响 | 原生包文本观感劣化（更粗、更糊、无次像素色边），与 JVM 版不一致 |
| 影响范围 | 仅 **LCD 次像素文本**路径；灰度路径在两个构建下逐项一致（见 4.3） |
| 是否报错 | **否** —— 静默退化 |
| 当前状态 | 本仓库**已缓解**：原生包默认改走灰度路径（第 8 节），观感与 JVM 版一致 |
| 尚未解决 | **代码级根因**与上游修复 —— 本文档第 9 节跟踪 |

## 3. 结论分层（证据等级）

| 等级 | 含义 | 本文对应 | 状态 |
| --- | --- | --- | --- |
| A | 可由第 10 节复现步骤直接测得、且多次测量一致 | 第 4 节各表 | 已确证 |
| B | 为解释 A 级事实提出的机制描述，尚无代码级证据 | 第 4.4 节 | 未确证（标注为推断） |
| C | 精确到语句或编译阶段的成因 | 第 7 节给出已限定范围，成因**未确定** | 未确证 |

## 4. 观测数据

### 4.1 观测条件

| 项 | 取值 |
| --- | --- |
| 操作系统 | Windows 11 x64（build 26300） |
| 显卡与缩放 | Intel UHD；显示缩放 1.5× |
| 判据样例 | 黑字白底，`Segoe UI` 14px（固定） |
| 图形后端 | D3D（CI 无 GPU 时经 WARP，仍为 D3D 后端） |
| 对比方式 | 同一台机器、同一份源码、同一套参数，仅在 JVM 与原生两种构建间对比 |

### 4.2 字形掩码：两构建逐字节相同（A 级）

| 字号 / 缩放 | 三通道掩码合计 | 单像素最大合计 | `R=G=B` 像素占比 |
| --- | --- | --- | --- |
| 12px / 1.0× | `27882 / 27880 / 27885` | 40475 | 37.1% |
| 14px / 1.0× | `35450 / 35448 / 35453` | 51010 | 41.6% |
| 21px / 1.0× | `76825 / 76823 / 76828` | 102576 | 49.0% |
| 14px / 1.5× | `76825 / 76823 / 76828` | 102576 | 49.0% |

⇒ 输入侧（DirectWrite 字形掩码）在两构建下**逐字节相同**，可排除「掩码生成差异」。

### 4.3 合成结果：两条判别特征（A 级）

| 观测项（黑字白底） | JVM 版 | 原生包 |
| --- | --- | --- |
| 三通道覆盖量 | `90666 / 91021 / 90713`（三值互不相等） | `126480 / 126480 / 126480`（**逐位相同**） |
| 逐列覆盖量（剖面） | `324 4629 2571 1670 1670 1561 1493 1549 2881 4215` | `0 6885 6885 3060 3060 2295 3060 3825 6885 6885 0` |
| 单像素覆盖之和（复杂字形处） | 不超过该像素三通道最大值之和 | `45855`，**超出**该像素三通道最大值之和 `40475` |
| 灰度路径对照 | 深核 1731 / 中间调 885 / 覆盖量 498320 / 色边 0 | 与 JVM 版**逐项一致** |

由表中数据可提取两条判别特征：

1. **覆盖值二值化**：原生侧逐列覆盖量均为 `765 = 3 × 255` 的整数倍，不存在中间取值；
2. **取样跨越相邻像素**：原生侧单像素覆盖之和**超出**该像素三通道最大值之和，
   而正确的按通道合成在数学上不可能超过该上限。

> 规模交叉验证（同判据、不同工程）：最小复现仓库独立测得
> JVM `73135 / 73618 / 73245`（`collapsed=false`）↔ 原生 `105825 / 105825 / 105825`（`collapsed=true`）；
> 本仓库界面文字为 `90666…` ↔ `126480…`。两者结论一致，说明与业务代码无关。

### 4.4 行为模型（B 级：推断）

综合 4.2、4.3：输入相同、输出不同，且输出具备上述两条判别特征，据此提出机制描述：

* 预期实现：`out_c = mix(bg_c, fg_c, m_c)`，即**每个颜色通道使用各自的覆盖** `m_c`；
* 实际表现：`out_c = mix(bg, fg, s)`，即**三通道共用同一个标量** `s`，且 `s` 呈二值化。

**该描述是对 A 级事实的机制化解释，尚未取得代码级证据支持，故标注为推断。**

## 5. 定位过程（收敛路径）

方法固定为：**提出假设 → 设计可产生定量结果的探针 → 以数据证伪或确认 → 更新结论**。

| 阶段 | 手段 | 观测结果 | 结论或排除项 |
| --- | --- | --- | --- |
| 1 | 对比截图并量化（`Script/CompareScreenshots.py`） | 原生 AA 比 / 色边均值 `0.003 / 0.20`；JVM `0.960 / 44.55` | 把观感问题转为定量判据 |
| 2 | 导出 DirectWrite 字形掩码 | 两构建逐字节相同 | 证伪「掩码生成不同」 |
| 3 | 离屏 LCD 合成探针 | 原生侧「中间调 = 0、色边 = 0、覆盖量偏高」 | 差异限定在 **LCD 合成路径** |
| 4 | 分通道统计覆盖量 | 原生侧 `126480 / 126480 / 126480` | 通道信息被合并为单一标量 |
| 5 | 逐列剖面 + 单像素上限核对 | `3 × 255` 整数倍；单像素越界 | 得两条判别特征，B 级模型成立 |
| 6 | 转储 `PrismSettings` 全字段 + 审计类初始化时机 | 44 字段全同；相关包全为 `RUN_TIME` | 排除「构建期固化运行期行为」 |
| 7 | 以 `-Ob` 重建并比对 | 体积变化，渲染数值逐位相同 | 排除编译优化等级影响 |
| 8 | 8 组版本组合矩阵 | 8/8 全部复现；JVM 侧 8/8 全部 `collapsed=false` | 该缺陷不是回归 |
| 9 | 压缩为最小复现并上移独立仓库 | 44 行有效代码 + 单一判据 | 结论可由第三方独立验证（第 9 节） |

## 6. 排除项

| # | 排除对象 | 依据 |
| --- | --- | --- |
| 1 | DirectWrite 字形掩码 | 两构建掩码逐字节相同（见 4.2） |
| 2 | 纹理上传 | 两构建调用同一 `prism_d3d.dll` 的原生例程 |
| 3 | 着色器 `.obj` 与 `*_Loader` 常量表 | `dstColor` / `glyphColor` / `gamma` / `maxTexCoordIndex` 等一致 |
| 4 | 几何类（`MaskData`、`GlyphCache`）的几何与行距 | LCD 分支无特判；实测几何一致 |
| 5 | `_AlphaTest` 着色器变体 | 两构建均取 `false` |
| 6 | 构建期固化 | `PrismSettings` 44 字段全同 + 类初始化审计全 `RUN_TIME` |
| 7 | 运行环境差异 | 同一机器两进程，1.0× 掩码逐字节相同 |
| 8 | 编译优化等级 | `-Ob` 构建渲染数值逐位相同 |
| 9 | 六项运行期参数 | `dpi=126.0`、`outputScale=1.5`、字体族数 248、LCD 对比度 1.2、LCD 文本支持 `true`、`subPixelMode=1` 两构建一致 |
| 10 | 图形管线 / 驱动 | 同一后端与驱动；WARP 下同样复现 |
| 11 | Windows 应用清单 | 加清单前后行为不变 |
| 12 | 抓屏探针本身 | 直方图核查后确认未抓到目标窗口并停用（验收改用真实截图） |

## 7. 已限定的代码范围

代码级成因尚未确定，但范围已限定至下列调用链（命名取自 JavaFX **25.0.4** 的字节码核对结果）：

1. `com.sun.prism.impl.ps.BaseShaderGraphics#drawString(GlyphList, FontStrike, float, float, Color, int, int)` —— LCD 分支；涉及 `lcdSampleInvalid`、`initLCDSampleRT()`、`drawLCDBuffer(float ×8)`，并把 `gamma` 常量传给着色器。
2. `com.sun.prism.impl.ps.BaseShaderContext#validateLCDOp(...)` —— 经 `getSpecialShader(..., SpecialShaderType)` 取用 `TEXTURE_SECOND_LCD`（嵌套枚举成员），其名字为 `Solid_TextureSecondPassLCD`，对应 `com.sun.prism.shader.Solid_TextureSecondPassLCD_Loader` 与资源 `com/sun/prism/d3d/hlsl/Solid_TextureSecondPassLCD.obj`。
3. `com.sun.prism.impl.GlyphCache#addDataToQuad(...)` —— 最终调用 `com.sun.prism.impl.VertexBuffer#addQuad(float ×12)`。
4. 着色器资源 `com/sun/prism/d3d/hlsl/Solid_TextureSecondPassLCD.obj`（另有 `…_AlphaTest.obj` 变体）。
5. `com.sun.prism.shader.Solid_TextureSecondPassLCD_Loader#loadShader(...)` —— 常量表为 `dstColor = 0`、`glyphColor = 1`、`gamma = 0`。
6. `TEXTURE_SECOND_LCD` 分支的选择与参数装配路径。

**未能继续限定的原因**：可核实的全部输入（掩码、几何、参数、常量表、着色器字节码、类初始化时机）在两构建下
均相同，运行期无异常抛出；差异只能产生于 `native-image` 对该链路的编译或优化过程 —— 进一步定位需要
GraalVM 侧的可观测性（调用树分析、生成代码比对），已超出黑盒实验可判定的范围。

> 补充：该着色器的 HLSL 源码（`Solid_TextureSecondPassLCD.hlsl`）在 OpenJFX 仓库公开路径下不可得，
> 着色器内部算术无法直读 —— 这也是「停在调用链、无法落到语句」的原因之一。

## 8. 本仓库处置与规避

**这是临时缓解措施（workaround），不是最终方案** —— 代码级根因未定位，它只是绕开退化的 LCD 合成路径。
详见 [已知限制与路线图 · 第 5 条](../LimitationsAndRoadmap.md)。

缓解机制链（已按 JavaFX 25.0.4 源码 / 字节码核实）：

| 环节 | 行为 |
| --- | --- |
| `PrismFontFactory` 静态初始化 | 读 `System.getProperty("prism.lcdtext", 默认值)` → `lcdEnabled`（**只读一次**） |
| `PrismFontStrike` 构造器 | `aaMode = lcdEnabled ? aaMode : FontResource.AA_GREYSCALE` |
| 结论 | 必须在**类初始化之前**设置该属性，否则无效 |

### 开关与优先级（命令行指定，不必改代码）

| 优先级 | 参数（交给原生 exe） | 效果 |
| --- | --- | --- |
| 1 | `-Dprism.lcdtext=true` / `=false` | 用户直接决定；程序**不覆盖** |
| 2 | `-Dlegion.render.nativeTextGreyscale=false` | **关闭本缓解措施**，回到 JavaFX 默认（Windows 即 LCD） |
| 3 | 都不给（原生包默认） | 程序写入 `prism.lcdtext=false` ⇒ 灰度抗锯齿 |
| — | JVM 版（`LegionFanControl.jar`） | 程序不处理，保持 LCD 次像素合成 |

### 回退操作

```bat
:: 只这一次运行恢复 LCD（不改代码、不改发布物）
LegionFanControl.exe -Dprism.lcdtext=true

:: 只这一次运行关掉本缓解措施本身（等同未打本改动）
LegionFanControl.exe -Dlegion.render.nativeTextGreyscale=false
```

永久回退：删掉 `Launcher.main` 与 `FanControlApp.main` 里的 `NativeImageRenderDefaults.applyIfNativeImage();`
两行（随后 `NativeImageRenderDefaults` 可整体删除），或对合入本改动的提交执行 `git revert <提交>`。

### 为什么它脆弱（据此判断何时必须复核）

该缓解依赖 pom 中把 `com.sun.javafx.font` / `com.sun.prism` 设为 `--initialize-at-run-time`：
运行期写入的属性只有在**运行期初始化**时才会被读取。若这两包日后改为构建期初始化，本缓解会**静默失效** ——
这正是它必须被当作临时措施、并在上游修复后立即移除的原因。

### 验证方法（启动日志）

```
渲染诊断：原生镜像标记 org.graalvm.nativeimage.imagecode=runtime
渲染诊断：原生文本灰度默认 已按原生镜像默认写入 prism.lcdtext=false（灰度抗锯齿） 开关 legion.render.nativeTextGreyscale=<默认>
```

第二行会直接写明本次是「已按原生镜像默认写入…」还是「跳过：用户已显式设置…」/「跳过：总开关已关闭…」/「跳过：非原生镜像…」。

## 9. 修复进展与上游跟踪

> **当前结论**：问题与最小复现代码**已反馈上游**（GraalVM 侧），等待上游定位与修复。
> 本文档与两个外部 issue 同步更新；拿到上游结论后回填本节。

### 9.1 时间线

| 日期 / 时间（UTC） | 事件 |
| --- | --- |
| 2026-10-10 | 真机截图量化确认差异（AA 比 / 色边均值断层）；排除 DWrite 掩码、纹理、着色器常量表等 |
| 2026-10-11 | 字形掩码探针证明两构建掩码**逐字节相同**；LCD 合成探针在离屏复现差异 |
| 2026-10-11 | 建立最小复现仓库（单提交快照 + GPLv3），英文 / 中文 README 与故障档案 TS-2610-001 |
| 2026-10-11 14:52 | 最小复现仓库创建英文跟踪 issue [#23](https://github.com/acnxo/LCDSubpixelMinimalReproduction/issues/23) |
| 2026-10-11 14:55 | 向上游提交 GraalVM issue [`oracle/graal#14653`](https://github.com/oracle/graal/issues/14653)（作者 `ACANX`） |
| 2026-10-11 | 本仓库提出产品缓解方案（issue [#48](https://github.com/cnxnc/LegionFanControl/issues/48)） |
| 2026-10-11 | 产品缓解实现：`NativeImageRenderDefaults` + 开关 + 回退（PR [#54](https://github.com/cnxnc/LegionFanControl/pull/54)） |
| 2026-10-11 20:23 | 上游 issue 最后一次更新；**仍无维护者评论** |

### 9.2 上游提交状态（以本文档更新时的实测为准）

| Issue | 渠道 | 状态 | 评论数 | 标签 | 最后更新（UTC） |
| --- | --- | --- | --- | --- | --- |
| [`oracle/graal#14653`](https://github.com/oracle/graal/issues/14653) | GraalVM 官方仓库 Issues（native-image 运行期 bug 模板） | **OPEN** | 0 | `bug`、`native-image` | 2026-10-11 20:23 |
| [`LCDSubpixelMinimalReproduction#23`](https://github.com/acnxo/LCDSubpixelMinimalReproduction/issues/23) | 最小复现仓库（英文跟踪） | **OPEN** | 0 | `bug`、`question` | 2026-10-11 15:03 |

**含义**：两条都已受理进入上游视野，但**尚无回应、尚无修复分支**；本仓库不据此做任何「即将修复」的预期表述。

### 9.3 已交付给上游的材料

| 材料 | 说明 |
| --- | --- |
| 最小复现工程 | 44 行有效代码 + 单一判据（`collapsed=true/false`），独立仓库、GPLv3、双语 README、一键启动脚本 |
| 版本矩阵复现结果 | 8 组组合（GraalVM for JDK 17 / 20 / 21 / 22 / 23 / 24 / 25，另含 JavaFX 27）全部复现；每组编译产物随发布页提供 |
| 定量证据链 | 掩码逐字节相同、三通道塌缩、逐列 `3 × 255` 整数倍、单像素越界 |
| 探针与脚本 | 字形掩码探针、LCD 合成探针、`Script/CompareScreenshots.py`（纯标准库量化 AA 比 / 色边均值） |
| 报告模板要素 | GraalVM 版本、操作系统、运行命令、期望行为与实际行为、运行期日志 |

### 9.4 上报渠道与口径

| 渠道 | 现状 | 说明 |
| --- | --- | --- |
| GraalVM（`oracle/graal` Issues） | **已提交** | 须用运行期 bug 模板（`1_1_native_image_run_time_bug_report.yml`） |
| OpenJFX（`openjdk/jfx`） | **未提交** | 该仓库不接收 GitHub issue（`has_issues=false`）⇒ 只能走 JBS（`bugreport.java.com`）；是否提交待定 |
| `graalvm/graalvm-ce-builds` | — | 不接收 issue（不发散） |

归因口径：**同一份代码 / 着色器 / 输入，仅原生侧输出不同** ⇒ 主责在 `native-image` 侧；
OpenJFX 仅在「某构造官方不支持 AOT」时才共担。上报材料中**不断言**，只陈述可核实事实。

### 9.5 待办

| # | 待办 | 触发条件 | 状态 |
| --- | --- | --- | --- |
| 1 | 跟踪两个上游 issue 的动静（有回复 / 标签变化 / 修复分支即回填本文档） | 持续 | 进行中 |
| 2 | 上游给出可验证修复后，用最小复现仓库复测矩阵与判据 | 上游修复 | 未开始 |
| 3 | 复测通过后**删除本仓库缓解措施**（第 8 节回退），恢复 LCD 默认 | 复测通过 | 未开始 |
| 4 | 按上游要求补交材料（如 `--bundle-create` 复现包、中间量对照数据） | 上游提出 | 材料已具备，未提交 |

## 10. 复现方法

### 10.1 本仓库（产品侧）

1. 同一释放版本内，分别运行原生包 `LegionFanControl.exe` 与 JVM 版 `LegionFanControl.jar`；
2. 各截一张同界面图，用 `Script/CompareScreenshots.py` 量化：

```bat
python Script\CompareScreenshots.py 原生.png JVM.png --label-a 原生 --label-b JVM
```

3. 判据：原生侧的 AA 比 / 色边均值应接近 JVM 侧（缓解生效后），**不应**出现 `0.00` 对 `44+` 的断层；
4. 交叉核对启动日志：`渲染诊断：属性 … prism.lcdtext=false` 与 `渲染诊断：原生文本灰度默认 …` 两行。

### 10.2 最小复现仓库（独立、无业务代码）

1. `Dist/Update.cmd` 下载对应组合的发布包，或从源码构建（见该仓库 `README.zh-CN.md`）；
2. 分别双击 `Dist/RunNativeImage.cmd` 与 `Dist/RunJVMJar.cmd`，比较两个窗口与控制台判据；
3. 判据：一侧输出 `collapsed=false`、另一侧 `collapsed=true` 即判定为复现。

> 适用性说明：CI 构建机无 GPU（D3D 经 WARP），其数值足以判断缺陷是否复现；
> 渲染观感与像素级结论应以具备真实 GPU 与 ClearType 的机器为准。

## 11. 未决问题

1. 需区分的两种可能：**(A)** `native-image` 自身的编译 / 优化缺陷（如与浮点或向量化相关的错误折叠、常量传播越界）；**(B)** JavaFX 与 AOT 编译的兼容性缺陷（如依赖 JVM 运行期特性才成立的初始化或状态假设）。
2. 进一步定位需要 GraalVM 侧的可观测性（分析调用树、生成代码比对）——已超出本仓库黑盒实验的可判定范围。
3. OpenJFX 侧是否走 JBS 正式提交（渠道受限，见 9.4）。

## 12. 相关文档与外部链接

| 类型 | 入口 | 说明 |
| --- | --- | --- |
| 本仓库文档 | [已知限制与路线图](../LimitationsAndRoadmap.md) | 第 5 条：缓解措施的开关与操作说明 |
| 本仓库文档 | [原生镜像 CI 与发布](../NativeImageCi.md) | 第 5 节第 17 条：完整排错记录与验收口径 |
| 本仓库 issue | [#20](https://github.com/cnxnc/LegionFanControl/issues/20) | 根因复盘（含配图与证据链） |
| 本仓库 issue | [#48](https://github.com/cnxnc/LegionFanControl/issues/48) | 产品缓解方案与验收标准 |
| 上游 issue | [`oracle/graal#14653`](https://github.com/oracle/graal/issues/14653) | GraalVM 侧受理与修复进展（**主跟踪**） |
| 外部跟踪 | [LCDSubpixelMinimalReproduction#23](https://github.com/acnxo/LCDSubpixelMinimalReproduction/issues/23) | 英文版跟踪 issue |
| 复现工程 | [`acnxo/LCDSubpixelMinimalReproduction`](https://github.com/acnxo/LCDSubpixelMinimalReproduction) | 最小复现代码与矩阵编译产物 |
| 复现工程档案 | [TS-2610-001（复现仓库）](https://github.com/acnxo/LCDSubpixelMinimalReproduction/blob/dev/Docs/TroubleShooting/TS-2610-001-LcdSubpixel-TextComposition-Degradation-Under-GraalVm-NativeImage.md) | 复现侧的完整证据与行为模型（该仓库尚未按本仓库的命名规范重命名，链接按其现状） |
| 本仓库规范 | [DevSpec/TroubleShootingDocSpec.md](../DevSpec/TroubleShootingDocSpec.md) | 本目录的命名与格式规范（本档案即按此规范命名） |
| `Docs/TroubleShooting/TS-2610-003-FXML-UI-Refactor-And-Native-Image-Reflection-Pitfalls.md` | 同目录另一份档案（同为 native-image 相关，但归属本仓库配置缺陷：FXML 反射登记与资源排除） |
