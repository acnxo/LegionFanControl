# 技术原理

> 返回 [项目主页](../README.md) · 相关文档：[开发约定](DevelopmentConventions.md) · [已知限制与路线图](LimitationsAndRoadmap.md)

本文覆盖 LegionFanControl 的技术实现细节：桥接架构、性能设计、WMI 接口规格与若干踩坑记录。

---

## 整体架构

```
┌─────────────────────────────────────────────────────────┐
│  FanControlApp（JavaFX 界面 + 轮询调度）                   │
│      │                                                   │
│      ▼                                                   │
│  WmiBridge（接口契约）                                     │
│      ├── PowerShellWmiBridge   ← 当前基线实现              │
│      └── JnaComWmiBridge       ← 桩，已后置（见路线图 T4）   │
└──────────────────────┬──────────────────────────────────┘
                       │ 每次调用：启动 powershell.exe 子进程
                       ▼
        Get-CimInstance / Invoke-CimMethod
                       │
                       ▼
        root\WMI 命名空间（联想 WMI 提供程序）
        ├── LENOVO_FAN_METHOD       风扇读写入口
        ├── LENOVO_OTHER_METHOD     CapabilityID 通路
        ├── LENOVO_GAMEZONE_DATA    智能模式；旧机型兜底
        └── LENOVO_FAN_TABLE_DATA   风扇曲线表（v2 依据）
```

入口链：`Launcher`（普通类）→ `Elevation`（权限自提升）→ `FanControlApp`。

---

## 管理员权限与提权保障

联想 WMI 提供程序在非管理员上下文下**直接返回「拒绝访问」**，表现为转速与温度读不到、功能按钮不可用。
因此做了**两层提权保障**，无论以哪种方式启动都能拿到权限：

| 层次 | 位置 | 行为 |
|---|---|---|
| 第一层 | `Dist/Bootstrap.cmd` | 检测到未提权时，通过 UAC 以管理员身份重启脚本本身 |
| 第二层 | 程序入口 `Launcher` → `Elevation` | 若仍检测到未提权（例如直接双击 jar），**自动以管理员身份重启自身** |

第二层的安全设计：重启时向新实例传入内部标记 **`--no-elevate`**，带此标记的实例一律不再尝试提权，
从根本上杜绝「提权 → 判定失败 → 再提权」的无限循环；任何一步失败（用户取消 UAC、超时、非 jar 方式运行）
都**不会阻断启动**，程序照常以当前权限进入界面并给出提示。

> 使用者视角的同一问题见 [`Dist/README.md`](../Dist/README.md) 的「为什么必须管理员权限」。

---

## 为什么用 PowerShell 子进程

联想的 WMI 方法返回**内嵌对象**（embedded object）。早期版本尝试用 JNA 直接走 COM，会撞上封送处理的复杂度；当前基线选择**每次调用启动一个短生命周期 `powershell.exe` 子进程**，由 PowerShell 的 `Invoke-CimMethod` 完成调用与取值。这条路径依赖最少、最易验证。

代价是**进程创建开销**：本机 PowerShell 冷启动本身就慢 —— 轻量脚本实测单次约 **2.7 秒**（采样 2766 / 2562 / 2638 / 2834 ms），真实 WMI 查询路径的历史实测更高（≈7.6 秒）。开销的大头在进程创建本身。

> 因此有一个硬性设计结论：**绝不能逐项读取**。逐项会让每轮轮询付出 6~7 次进程创建成本。

---

## 批量快照（性能关键设计）

`WmiBridge.readSnapshot()` 把一轮所需的全部指标（2 个转速 + 2 个温度 + 风扇模式 + 满速状态）合并进**同一个 PowerShell 脚本**，只付一次进程创建成本。

| 方案 | 子进程数/轮 | 采集耗时（按地板成本外推） | 刷新周期（含间隔） |
|---|---|---|---|
| 逐项读取（旧） | 7 | ≈ 18.9 s | ≈ 20.9 s（+2000 ms 固定间隔） |
| **批量快照（现）** | **1** | **≈ 2.7 s** | **≈ 2.95 s（+250 ms 背靠背间隔）** |

进程创建次数 7 → 1，刷新周期下降约 **86%** —— 该比例由进程数直接决定，是确定的量。

> **基准口径说明**：上表见 `Probe/Java/Compare.java`（结果存于 `Script/Evidence/compare-result.txt`）。
> 它用轻量脚本（`Write-Output 'V=1'`）测量**纯进程创建 + PowerShell 启动的地板成本**（2700 ms 均值），
> 再按 7 次 → 1 次外推。这是**保守下限** —— 真实快照脚本还含 WMI 查询时间，实际耗时以界面状态栏显示的「采集耗时」为准。

脚本设计要点：

- 每个指标用统一的 `Get-V` 包装器单独 `try/catch`，单项失败只记 `-1`，**不会丢弃整批数据**；
- 每项内部保留降级链（CapabilityID → FAN_METHOD → 旧通路），逐级回退；
- 输出为单行 `V=f1,f2,ct,gt,sm,fs`，Java 侧只解析一次。

导出的实际脚本可随时查看：`Script/Evidence/snapshot-script.ps1`（由 `Probe/Java/DumpScript.java` 反射导出，与运行时代码同源）。

---

## WMI 类与方法

命名空间：`root\WMI`

| 类 | 用途 |
|---|---|
| `LENOVO_FAN_METHOD` | 风扇控制与读取（主要入口） |
| `LENOVO_OTHER_METHOD` | 通过 CapabilityID 读取温度 / 转速 / 满速（现代机型首选） |
| `LENOVO_GAMEZONE_DATA` | 智能风扇模式等；旧机型兜底通路 |
| `LENOVO_FAN_TABLE_DATA` | 风扇曲线表（每实例带 `Fan_Id`，v2 曲线编辑依据） |

### 能力边界：读能分风扇，写不能分

| 方法 | 参数 | 可分风扇 |
|---|---|---|
| `Fan_GetCurrentFanSpeed` | `FanID` | 是（**读**） |
| `Fan_GetCurrentSensorTemperature` | `SensorID` | 是（**读**） |
| `Fan_Set_FullSpeed` | `Status`(int) | **否 —— 全局开关** |
| `Fan_Set_Table` | `FanTable`(byte[]) | 否 —— 整表下发 |

`Fan_Set_FullSpeed` **没有 `FanID` 参数** —— 固件层面不接受「只让某一个风扇满速」。

> 旁证：业界最成熟的第三方实现 LenovoLegionToolkit 全库中与风扇控制相关的只有 `WMI.LenovoFanMethod.cs` 一个入口文件，其中不存在任何带 `FanID` 的**设定**方法。
> 详见[已知限制](LimitationsAndRoadmap.md#1-满速除尘是全局开关不支持单风扇定向控制)。

---

## 读取通路：CapabilityID 与降级链

现代 Legion 机型统一通过 `LENOVO_OTHER_METHOD.GetFeatureValue(IDs=<CapabilityID>)` 读取，返回内嵌对象的 `Value` 属性。

### CapabilityID 对照表

来源：LenovoLegionToolkit `Enums.cs` 的 `CapabilityID` 枚举（十六进制值已与 LLTK 源码逐条核验）。

| 数据 | CapabilityID | 十进制（脚本中使用） |
|---|---|---|
| CPU 温度 | `0x05040000` | 84148224 |
| GPU 温度 | `0x05050000` | 84213760 |
| CPU 风扇转速 | `0x04030001` | 67305473 |
| GPU 风扇转速 | `0x04030002` | 67305474 |
| 满速开关 | `0x04020000` | 67239936 |

> 十六进制为源码常量（`PowerShellWmiBridge.CAP_*`）；PowerShell 脚本中以十进制传参，两者一一对应。

### 降级链

**转速** `readFanSpeeds()`：

1. `GetFeatureValue(CpuCurrentFanSpeed / GpuCurrentFanSpeed)` —— 现代机型首选；
2. `Fan_GetCurrentFanSpeed(FanID 0/1)`，属性 `CurrentFanSpeed` —— 传感器维度；
3. `GAMEZONE.GetFan1Speed / GetFan2Speed`（WMI v1）—— 老机型兜底。

**温度** `readTemps()`：

1. `GetFeatureValue(CpuCurrentTemperature / GpuCurrentTemperature)`；
2. `Fan_GetCurrentSensorTemperature(SensorID 3/4)`，属性 `CurrentSensorTemperature`；
3. `GAMEZONE.GetCPUTemp / GetGPUTemp` —— 旧机型；**本机（Y9000P IAH7H）验证返回 0，无效通路**。

**满速状态** `getFullSpeed()`：

1. `Fan_Get_FullSpeed` → 属性 `Status`（bool，归一化为 1/0）；
2. 备选：`GetFeatureValue(FanFullSpeed)` → 属性 `Value`。

---

## 写入通路：满速开关

`setFullSpeed(boolean)` 依次尝试三条通路，前一条失败才退到下一条：

1. **`FAN_METHOD.Fan_Set_FullSpeed(Status: int)`** —— 权威首选（LLTK 实证签名）；
2. **`OTHER_METHOD.SetFeatureValue(IDs=0x04020000, value)`** —— 现代能力位；
3. **`GAMEZONE.SetFanCooling(status)`** —— 旧机型兜底（本机 `IsSupportFanCooling=0`，无效）。

`resetToAuto()`（关闭满速）在调用写入后会**读回 `Fan_Get_FullSpeed` 二次确认**，若标志未清除则明确告警 —— 避免"假成功"。

### 两个历史缺陷（已修复）

- **`[bool]$false` 非法拼接**：早期版本用 `"[bool]$" + on` 生成 `[bool]$false`，PowerShell 语法非法导致 try 块静默失败，功能退化为「只能开不能关」。修复：直接用 `$true` / `$false` 字面量。
- **UI 状态未复位**：早期 `stopCooling` 只在成功回调里翻转状态位，失败路径不翻转，按钮永久卡在「停止」。修复：成功/失败两路都复位，并新增硬件状态校正。

---

## 属性名对照（踩坑记录）

早期版本因**猜测属性名**导致读数恒为 `0`：`.Data` / `.Speed` 这些属性根本不存在，PowerShell 中 `[int]$null` 的结果恒为 0，从而伪装成「真实读数为 0」。

以下是经 LenovoLegionToolkit 源码核实的**权威属性名**：

| 方法 | 权威属性名 / 参数 |
|---|---|
| `Fan_GetCurrentFanSpeed` | 属性 `CurrentFanSpeed`，参数 `FanID`(byte) |
| `Fan_GetCurrentSensorTemperature` | 属性 `CurrentSensorTemperature`，参数 `SensorID` |
| `Fan_Get_FullSpeed` | 属性 `Status`（**bool**） |
| `Fan_Set_FullSpeed` | 参数 `Status`（**int，不是 bool**） |
| `GetFeatureValue` | 参数 `IDs`(int)，属性 `Value` |
| `SetFeatureValue` | 参数 `IDs`(int) + `value`(int) |
| `SetFanCooling` | 参数 Bool |

**教训：逆向厂商私有接口时，必须先找权威开源实现（LLTK），绝不猜字段名。** 猜测必然产生「静默返回 0」这类极难定位的 bug。

---

## 风扇方位标注的依据与置信度

界面标签为 `01-左风扇转速[CPU侧]` / `02-右风扇转速[GPU侧]`，标签中的方位为**推断**结论。推理依据两条，如下：

### 证据 A — 本机实测风扇表热特性

`Script/Evidence/probe-result-elevated.txt` 中转储的 `LENOVO_FAN_TABLE_DATA`：

| Fan_Id | Sensor_ID | 最低转速 | 温度点曲线 | 推断归属 |
|---|---|---|---|---|
| 0 | 3 | 1700 | 58,58,58,58,66,74,79,81,84,**92** | CPU 侧（起温低、上限高）|
| 1 | 4 | 1600 | **65,65,65,65,65**,74,78,78,81,88 | GPU 侧（起温高、上限低）|

### 证据 B — 配件市场布局约定

针对本机型（82RF / 16IAH7H）的散热风扇替换件商品页明确标注：**正常使用笔记本时，左侧为 CPU 散热风扇，右侧为 GPU 散热风扇**。
备件号：CPU 风机 `DFSCL12E06486Q`，GPU 风机 `DFSCL12E16486Q`。

### 结论与置信度

**风扇 1（FanID 0）→ 左 / CPU 侧；风扇 2（FanID 1）→ 右 / GPU 侧。**

置信度「中等偏强」—— 来自配件市场通行约定 + 本机热特性推断，**非联想官方逐一标注，也未经拆机核实**。完整证据写在 `FanControlApp.FAN1_LABEL` / `FAN2_LABEL` 的 Javadoc 中；若拆机核实发现不符，只需改这两个常量。

### 一个被证伪的思路

曾尝试用 LLTK 的 `FanTableType` 枚举（`CPU=1, CPUSensor=2, GPU=3, GPU2=4`）做映射，但按它套会得出「两个风扇都在 GPU 侧」，与"一 CPU 一 GPU"的物理事实冲突。
结论：`FanTableType` 是 LLTK 内部的**曲线表类型**枚举，**不能**直接当作物理 FanID/SensorID 的语义映射。最终改走「热特性 + 配件商方位」两条证据交叉推断。

---

## 参考资料

- [LenovoLegionToolkit](https://github.com/BartoszCichecki/LenovoLegionToolkit) —— 本项目 WMI 方法签名、属性名与 CapabilityID 的权威来源
  - `WMI.LenovoFanMethod.cs`、`WMI.LenovoOtherMethod.cs`、`WMI.LenovoFanTableData.cs`
  - `Enums.cs`（`CapabilityID`、`FanTableType`）
- `Script/Evidence/probe-result-elevated.txt` —— 本机 Y9000P IAH7H 的实测接口与曲线表证据
