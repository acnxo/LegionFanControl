# Probe —— 开发期探查工具

本目录存放**开发期**用于确认硬件接口能力的工具，**不参与打包**（不在 `src` 下）。

```
Probe/
├── README.md          本文件
├── Java/              开发辅助工具（Java）
│   ├── DumpScript.java    反射导出生成的快照脚本，供语法校验
│   ├── Compare.java       量化进程创建开销与刷新周期
│   └── Bench.java         单次子进程调用耗时实测
└── Powershell/        硬件探查脚本（需提权运行）
    ├── FanWmiV1.ps1       首次接口全量探查
    ├── FanWmiV2.ps1       修正 FanID 后的复探与签名校准
    ├── FanRpmV3.ps1       转速通路对比（CIM vs WMI v1）
    └── FanShape.ps1       按权威属性名取值结构确认
```

---

## ⚠️ 运行前必读

**这四个脚本对硬件的影响不同，务必区分：**

| 脚本 | 是否触发硬件动作 | 说明 |
|---|---|---|
| `FanWmiV1.ps1` | ⚠️ **会** | 调用 `SetFanCooling(1)` 触发固件除尘波形，采样 26 秒后复位 |
| `FanWmiV2.ps1` | ⚠️ **会** | 调用 `Fan_Set_FullSpeed(true)` 触发满速，采样 26 秒后复位 |
| `FanRpmV3.ps1` | ✅ 否 | **纯读取**，不修改任何硬件状态 |
| `FanShape.ps1` | ✅ 否 | **纯读取**，不修改任何硬件状态 |

- 所有脚本**都需要管理员权限**（联想 WMI 非管理员必拒绝访问）。
- V1 / V2 的硬件动作是**短暂且会自行复位**的（与联想电脑管家的「降温除尘」是同一条固件通路），
  但运行期间风扇会明显提速、噪声增大，属预期现象。
- 请通过 `Script/` 下的 `.cmd` 启动器运行（会自动处理 UAC 提权）。

---

## 逐一说明

### FanWmiV1.ps1 —— 首次接口全量探查

**用途**：最初的一次摸底，确认本机到底暴露了哪些联想 WMI 能力。

**做了什么**：

1. 枚举 `LENOVO_*` 各类的方法清单（作为 v1/v2 能力规划依据）
2. 转储 `LENOVO_FAN_TABLE_DATA` 全部实例（风扇曲线表的原始结构，v2 曲线编辑的依据）
3. 能力探测：`IsSupportFanCooling` / `GetSmartFanMode` / 温度接口
4. 基线转速 → `SetFanCooling(1)` → 采样 26 秒 → `SetFanCooling(0)` 复位

**产出**：`probe-result-elevated.txt`（已归档至 `Script/Evidence/`）

**历史结论**：正是这次探查发现 `GetFan1Speed`/`GetFan2Speed` **恒返回 0**，
从而引出下面两个脚本。

---

### FanWmiV2.ps1 —— 修正后的复探与签名校准

**用途**：针对 V1 暴露的问题做修正后再探一次，重点是把**方法签名**校准清楚，
好让 Java 桥接层知道确切的参数名与出参名。

**相对 V1 的修正**：

- `FanID` 取值是 **0 / 1**（不是 1 / 2）—— V1 用错导致读数为 0
- 满速通路是 `LENOVO_FAN_METHOD.Fan_Set_FullSpeed`
  （`SetFanCooling` 在本机报告 **不支持**）
- 把方法返回值**完整以 JSON 形式导出**，便于确认内嵌对象的真实属性名

**做了什么**：触发一次满速 → 采样 26 秒 → 复位。

**产出**：`probe-result-elevated-v2.txt`（已归档至 `Script/Evidence/`）

---

### FanRpmV3.ps1 —— 转速通路对比

**用途**：**定位「转速恒为 0」的根因**。

**核心假设**：`Get-WmiObject`（WMI v1）无法正确解析联想返回的**内嵌对象**；
改用 `Get-CimInstance`（WMI v2 / CIM）后应能取到真实值。

**做了什么**：对**同一次采集**同时走 CIM 与 WMI v1 两条路径取转速并对比，
另顺带采集 `Fan_GetCurrentSensorTemperature`（为温度通路校准做准备）。**纯读取。**

**产出**：`probe-result-rpm-v3.txt`

**历史结论**：证实了上述假设 —— 这正是 `PowerShellWmiBridge` 全面改用 CIM 的依据。

---

### FanShape.ps1 —— 取值结构确认

**用途**：在**拿到权威规格之后**，逐项确认「正确的属性名」在本机确实能取到真实值。

**权威来源**：[LenovoLegionToolkit](https://github.com/BartoszCichecki/LenovoLegionToolkit) 的
`WMI.LenovoFanMethod.cs` / `WMI.LenovoOtherMethod.cs` / `Enums.cs`（`CapabilityID`）。

**做了什么**：按权威属性名逐项取值，并打印 `Fan_Get_FullSpeed` 返回值的**实际类型**。**纯读取。**

**产出**：`probe-result-shape.txt`

> 背景：早期版本因**猜测属性名**（用了不存在的 `.Data` / `.Speed`）导致读数恒为 0 ——
> 因为 `[int]$null` 恰好等于 0，把「取不到」伪装成了「真实读数为 0」。
> 本项目由此确立约定：**逆向厂商私有接口时先找权威开源实现，绝不猜字段名。**

---

## Java 开发辅助工具

不参与打包，需先编译：

```bash
javac -encoding UTF-8 -cp "target/classes" -d Probe/Java Probe/Java/*.java
```

| 工具 | 用途 | 运行命令 |
|---|---|---|
| `DumpScript` | **反射导出** `buildSnapshotScript()` 生成的完整脚本，供人工核对与语法校验 | `java -cp "target/classes;Probe/Java" DumpScript` |
| `Compare` | 量化「逐项读取 vs 单次批量快照」的刷新周期差异 | `java -cp "target/classes;Probe/Java" Compare` |
| `Bench` | 从 Java 侧实测单次 powershell 子进程调用开销 | `java -cp "target/classes;Probe/Java" Bench` |

- `Compare` 产出 `compare-result.txt`：实测进程创建 7 → 1 次，刷新周期 **≈20.9 s → ≈2.95 s（降幅 86%）**。
- `DumpScript` 产出 `snapshot-script.ps1`，是批量快照脚本**语法校验**（`Parser::ParseInput`）的输入。

---

## 启动器与产出归档

| 位置 | 内容 |
|---|---|
| `Script/run-*-elevated.cmd` | 四个探查脚本的 UAC 提权启动器（**双击运行**） |
| `Script/ValidateScripts.ps1` | 早期脚本集合的语法校验示例（无需管理员） |
| `Script/Evidence/` | 全部探查与验证产出归档 |

启动器与脚本的对应关系：

| 启动器 | 调用脚本 |
|---|---|
| `RunProbeElevated.cmd` | `Probe/Powershell/FanWmiV1.ps1` |
| `RunProbe2Elevated.cmd` | `Probe/Powershell/FanWmiV2.ps1` |
| `RunProbe3Elevated.cmd` | `Probe/Powershell/FanRpmV3.ps1` |
| `RunShapeProbeElevated.cmd` | `Probe/Powershell/FanShape.ps1` |

**产出位置**：脚本把结果写入 `$PSScriptRoot`，即 `Probe/Powershell/probe-result-*.txt`；
历史结果统一归档在 `Script/Evidence/`。

---

## 维护约定

1. **任何拼接生成的 PowerShell 脚本，交付前必须过 `Parser::ParseInput` 语法校验 + 内容导出核对。**
2. 探查脚本一律**优先纯读取**；确需触发硬件动作的（如 V1/V2），必须在脚本头注释与本文档中显式标注。
3. 结果文件名沿用 `probe-result-*.txt` 命名，便于与 `Script/Evidence/` 中的归档对应。
