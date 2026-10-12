# 已知限制与路线图

> 返回 [项目主页](../README.md) · 相关文档：[技术原理](TechnicalPrinciples.md) · [原生镜像 CI 与发布](NativeImageCi.md)

---

## 已知限制

### 1. 满速除尘是全局开关，不支持单风扇定向控制

`Fan_Set_FullSpeed` 只有 `Status` 参数、**没有 `FanID`**，固件层面不接受「只让某一个风扇满速」。这是**接口能力缺失**，而非实现难度问题。

业界最成熟的第三方实现（LenovoLegionToolkit）同样做不到 —— 其全库中与风扇控制相关的入口只有一个文件，且不含任何带 `FanID` 的设定方法。规律：**读能分，写不能分。**

唯一**有硬件依据**的单风扇差异路径是 v2 自定义曲线：`LENOVO_FAN_TABLE_DATA` 每个实例带 `Fan_Id`，两个风扇各有独立的 10 点曲线表（本机实测）：

| 实例 | Fan_Id | 转速表（10 点，RPM） |
|---|---|---|
| GMZN_0 | 0 | 1700,1800,2000,2200,2800,3400,3700,4100,4400,4800 |
| GMZN_2 | 1 | 1600,1800,2100,2300,2900,3500,3700,4100,4500,4800 |

给它们下发不同曲线，即可实现「一个高一个低」的效果。但存在三个真实约束：

1. `Fan_Set_Table` 的参数是**整块 `byte[]`**（而非 `(FanID, 表)`），要分风扇需自行逆向固件期望的二进制布局；LLTK 也是整块封装，未见按 FanID 单独下发；
2. 必须先切 Custom 模式，FN+Q 会重置、需重放；
3. 这套二进制协议未充分公开 —— 这是逆向工作量，不是调用现成 API。

### 2. 满速会自动复位（设计行为）

固件约 **2 分钟**后交还 EC 控制，这是设计行为而非缺陷；程序会同步校正界面状态（硬件状态校正）。

### 3. 刷新周期受限于进程创建开销

当前 ≈ 3 秒/轮（已从 ≈21 s 优化，见[技术原理](TechnicalPrinciples.md#批量快照性能关键设计)）。进一步优化的方向是用 JNA/COM 直连替代子进程（`JnaComWmiBridge`，已后置）。

### 4. 部分功能尚未经实机数值确认

多项修复已交付但未经真机数值确认，详见下方 T2 清单。

### 5. 原生包默认关闭 LCD 次像素文本合成（**临时缓解措施，可开关、可回退**）

> **这不是修复，只是绕开**。GraalVM 原生镜像在 JavaFX 的 **LCD 次像素文本合成**路径上退化为
> 二值输出，而**代码级根因尚未定位**（issue #20）；本项只在原生镜像内改走灰度抗锯齿。
> 上游修好后应当删除本措施（回退操作见下方）。
> 完整证据链、已限定的代码范围与**上游修复进展**见
> [TS-2610-001 故障档案与修复跟踪](TroubleShooting/TS-2610-001-LCD-Subpixel-Text-Composition-Native-Image-Fix-And-Upstream-Tracking.md)。

现象与证据：同一参数下 DWrite 掩码**逐字节相同**，但合成把三个颜色通道压成同一个标量
（原生 `126480/126480/126480`，JVM `90666/91021/90713`），表现为文字更粗、更糊、没有次像素色边
（根因见 issue #20，证据链见[原生镜像 CI 与发布](NativeImageCi.md) 第 5 节第 17 条）。
灰度路径在两个构建里**逐项一致**（字形像素、字形掩码探针均同值），因此用它绕开。

#### 开关与优先级（命令行指定，不必改代码）

| 优先级 | 命令行（原生 exe 直接跟在后面） | 效果 |
|---|---|---|
| 1 | `-Dprism.lcdtext=true` / `-Dprism.lcdtext=false` | 用户直接决定；程序**不覆盖** |
| 2 | `-Dlegion.render.nativeTextGreyscale=false` | 关闭本缓解措施，回到 JavaFX 默认（Windows 即 LCD） |
| 3 | 以上都不给（原生包默认） | 程序写入 `prism.lcdtext=false` ⇒ 灰度抗锯齿 |
| — | JVM 版（`LegionFanControl.jar`） | 不受影响，保持 LCD 次像素合成 |

#### 操作说明

**① 只在这一次运行里临时恢复 LCD**（不改代码、不改发布物）：

```bat
:: 在原生包目录下（Dist\），直接带上参数启动
LegionFanControl.exe -Dprism.lcdtext=true
```

双击使用时：给 exe 建快捷方式，在「属性 → 目标」末尾加上 ` -Dprism.lcdtext=true`。

**② 临时关掉本缓解措施本身**（回到「未打本改动」的行为，同样不改代码）：

```bat
LegionFanControl.exe -Dlegion.render.nativeTextGreyscale=false
```

**③ 永久回退到本改动之前**（开发侧）：

* 删掉 `Launcher.main` 与 `FanControlApp.main` 里的 `NativeImageRenderDefaults.applyIfNativeImage();` 两行（随后本类即可整体删除）；
* 或者对合入本改动的提交执行 `git revert <提交>`。

**④ 怎么判断当前到底生效没生效**（启动日志头两行）：

```
渲染诊断：原生镜像标记 org.graalvm.nativeimage.imagecode=runtime
渲染诊断：原生文本灰度默认 已按原生镜像默认写入 prism.lcdtext=false（灰度抗锯齿） 开关 legion.render.nativeTextGreyscale=<默认>
```

第二行的措辞会直接告诉你原因：`已按原生镜像默认写入…` / `跳过：用户已显式设置 …` /
`跳过：总开关已关闭 …` / `跳过：非原生镜像 …`。

代价是失去次像素色边（1.5 倍缩放下灰阶 AA 仍清晰）。

---

## 路线图与待办

| 编号 | 事项 | 优先级 | 状态 |
|---|---|---|---|
| T1 | 探查 `Fan_Set_Table` 真实签名，判定 v2 单风扇曲线可行性 | 中 | 待提权运行 |
| T2 | 实机确认 v1 功能正确性（转速数值、温度、停止回落、刷新延迟、方位标注、**权限自提升**） | **高** | 待实测 |
| T3 | 运行探查脚本，作为数值正确性的独立佐证 | 中 | 待提权运行 |
| T4 | GraalVM 原生编译（CI 出 Windows exe） | 中 | 已完成（CI 实跑通过，产物发布到 Release） |

### T1 · 探查 `Fan_Set_Table` 真实签名

- **目的**：判定 v2 单风扇曲线是否可行
- **手段**：纯只读脚本，dump `Fan_Set_Table` 的 MOF 签名 / 参数类型 / 数组维度，并导出 `LENOVO_FAN_TABLE_DATA` 全实例原始结构
- **待验证问题**：参数是否只能整块 `byte[]` 下发？若是，单风扇曲线需自行逆向固件的二进制布局
- **需提权运行一次**

### T2 · 实机确认清单

多项修复已交付但未经真机数值确认：

1. 双击 `Dist\Bootstrap.cmd` 或 `Dist\LegionFanControl.jar` 后，**UAC 提权是否生效**、界面是否显示「已提权」且数值可读；
2. 双风扇转速是否显示真实数值（预期 1600~4800 RPM 区间）；
3. CPU / GPU 温度是否正常显示；
4. 「停止」是否真的让风扇回落，轮询读回状态与界面是否一致；
5. 刷新延迟是否明显改善（已从 ≈21 s 优化到 ≈2.95 s）；
6. 风扇方位标注是否符合机器实际。

### T3 · 运行历史遗留探查脚本

`Script/RunProbe3Elevated.cmd`（CIM vs WMI v1 转速对比）、`Script/RunShapeProbeElevated.cmd`（按权威属性名取真实值）—— 可作为 v1 数值正确性的独立佐证。

### T4 · GraalVM 原生编译（2026-10-10 用户重启，已完成）

- **工具链定案**：官方 GraalVM（`org.graalvm.buildtools:native-maven-plugin` 0.10.6），
  **明确不引入 Gluon / gluonfx**（用户决策：禁止 gluonfx）；
- **已落地**：
  1. pom 的 `native-image` profile（与默认 jar 打包完全区隔，失败互不影响）；
  2. `.github/workflows/main.BuildReleaseWindowsX64.yml` —— `windows-latest` + GraalVM JDK 25 + MSVC；
     触发方式为 `push`（`main` / `dev`）+ `workflow_dispatch`；构建产物（exe + JavaFX DLL）
     自动发布到 GitHub Release，tag 规则见 [原生镜像 CI 与发布](NativeImageCi.md)；
  3. `src/main/resources/META-INF/native-image/` 三份配置（反射 / JNI / 资源）。
- **已踩过的坑（2026-10-10 首次实机运行）**：exe 启动报
  `Graphics Device initialization failed for : d3d, sw` + `no suitable pipeline found`。
  根因两条，均已修：
  1. **平台原生 DLL 未编进镜像** —— 原生镜像下 `NativeLibLoader` 不会去 exe 同目录找，
     唯一可行通路是把 DLL 作为资源编进 exe（现已由 `resource-config.json` 的 `.*\.dll$` 纳入）；
  2. **启动链路反射元数据不全** —— Glass 工厂 / Prism 两条管线 / Windows 字体工厂 /
     212 个着色器 loader / 39 个效果 peer 都是按类名反射加载的，现已程序化生成清单。
  详见 [原生镜像 CI 与发布](NativeImageCi.md) 第 5 节。
- **待实测**：字体渲染、UAC 自提升在 exe 形态下的降级行为；以及真实机型的完整界面走查。

**历史记录（当时后置的原因，供参考）**：Swing/AWT 与 native-image 的兼容限制（本项目用 OpenJFX，不适用）；
gluonfx 只兼容 Gluon 自家 GraalVM 构建（已通过禁用 gluonfx 规避）；JNA/WMI-COM 需 tracing 元数据（JNA 桩未启用）。
