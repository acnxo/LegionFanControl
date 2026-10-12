# 技术文档

> 返回 [项目主页](../README.md) · 相关目录：[`Dist/`](../Dist/README.md)（使用者指南） · [`Probe/`](../Probe/README.md)（探查工具）

本目录存放 LegionFanControl 的**技术实现文档**；本页同时承担**全项目文档的总索引**。

项目主页（根 `README.md`）刻意只保留「是什么 / 怎么用」，所有实现层面的内容与文档索引都收敛到这里，避免主页过长。

```
Docs/
├── README.md                     本文件（文档索引与阅读路径）
├── TechnicalPrinciples.md        技术原理（架构 / 性能 / WMI 接口 / 踩坑）
├── DevelopmentConventions.md     开发约定（11 条硬性约定）
├── UserInterface.md              界面说明（显示项 / 按钮状态 / 状态栏 / 读数约定）
├── ProjectStructure.md           项目结构（项目概况 / 目录职责 / 构建机制）
├── LimitationsAndRoadmap.md      已知限制与路线图（能力边界 / 待办）
├── NativeImageCi.md              原生镜像 CI 与发布（触发 / 工具链 / tag 规则 / 踩坑）
├── TroubleShooting/              故障档案与修复跟踪（TS-2610-001 文本渲染退化 / TS-2610-002 .cmd 行尾解析漂移）
├── DevSpec/                      开发规范（文档命名与格式规范）
├── DevLog/                       开发过程日志（内部工作记忆脱敏导出，append-only）
└── Asset/                        文档配图
    └── IMG/UI-Main.png               界面截图
```

---

## 文档索引

| 文档 | 内容 | 什么时候看 |
|---|---|---|
| [TechnicalPrinciples.md](TechnicalPrinciples.md) | **技术原理** —— 整体架构、**管理员权限与两层提权保障**、为何用 PowerShell 子进程、批量快照（性能关键设计）、WMI 类与方法清单、CapabilityID 读取通路与降级链、满速写入三通路、属性名踩坑记录、风扇方位标注的证据链与置信度 | 想搞清某个数值从哪来；准备改桥接层 |
| [DevelopmentConventions.md](DevelopmentConventions.md) | **开发约定** —— 11 条全部由实际踩坑换来的硬性约定（Launcher 入口、`module-info` 排除、XML 注释禁双横线、脚本语法校验、**`.cmd` 必须 CRLF（已由 `.gitattributes` + CI 自证机器强制）**、产物名不带版本号、发布 zip 内自带同名目录、**第三方构件版本集中在 `<properties>`** 等） | **动手改代码或构建配置之前——必读** |
| [UserInterface.md](UserInterface.md) | **界面说明** —— 各显示项含义、操作按钮的四种状态、状态栏各场景文案、读数约定（含界面截图） | 想了解某个显示项/文案的含义；改界面时 |
| [ProjectStructure.md](ProjectStructure.md) | **项目结构** —— **项目概况**（目标机型 / 技术栈 / 交付形态 / 许可证）、完整目录树与各目录职责、**从源码构建**与打包机制（shade 合并 + 自动导出 `Dist/`）、**原生镜像构建**（GraalVM native image / CI）、产物名与版本管理说明 | 刚接手项目；不确定东西该放哪个目录 |
| [LimitationsAndRoadmap.md](LimitationsAndRoadmap.md) | **已知限制与路线图** —— 四项能力边界的展开说明（如为何不支持单风扇定向控制）、后续待办 T1–T4 | 评估「某功能能不能做」；规划下一步 |
| [NativeImageCi.md](NativeImageCi.md) | **原生镜像 CI 与发布** —— 触发方式与作业图、编译工具链（GraalVM / MSVC / Maven）、各步骤为何这么写、产物形态、tag 与 Release 发布规则、P0 踩坑、排查手册 | 改这个工作流之前；排查原生编译或发布失败 |
| [TroubleShooting/](TroubleShooting/) | **故障档案与修复跟踪** —— TS-2610-001：原生包文本渲染退化（GraalVM native-image 下 LCD 次像素文本合成）的现象、证据分层、已限定代码范围、本仓库缓解措施的开关与回退、**上游 issue 的修复进展与待办**；<br>TS-2610-002：`.cmd` 退化成 LF 行尾导致 cmd.exe **行读取位置漂移**（含中文的批处理被错误切分）—— 字节级取证、`.gitattributes` + CI 自证的机器兜底、已下载旧包的就地修复。<br>**命名与格式见 [DevSpec/TroubleShootingDocSpec.md](DevSpec/TroubleShootingDocSpec.md)** | 想了解这两个缺陷查到了哪一步；改脚本行尾 / 换编辑器 / 批量改写 `.cmd` 之前 |
| [DevSpec/](DevSpec/) | **开发规范** —— [TroubleShooting 文档命名与格式规范](DevSpec/TroubleShootingDocSpec.md)：TS 档案的文件名结构（`TS-<yyMM>-<序号>-<英文词序>`）、**一个单词一个单词用 `-` 连接（禁止连写；`LCD`/`CMD`/`JVM`/`CRLF` 等缩写全大写）**、非 TS 文档用大驼峰（`TroubleShootingDocSpec.md`）、正文骨架与各节要求、结论分层与证据写法、重命名操作流程、提交前自查清单 | **新建或重命名故障档案之前——必读**；整理文档结构时 |

---

## 全项目文档一览

本目录之外的文档入口 —— 连同上方[文档索引](#文档索引)，即**全项目文档的完整地图**：

| 文档 | 面向 | 内容 |
|---|---|---|
| [根 README.md](../README.md) | 所有人 | **是什么 / 怎么用** —— 功能特性、快速开始、环境要求、界面说明 |
| [Dist/README.md](../Dist/README.md) | 使用者 | **使用者指南** —— 快速开始、环境要求、**为何需要管理员权限**、界面说明、常见问题、卸载 |
| [Probe/README.md](../Probe/README.md) | 开发者 | **探查工具说明** —— 各脚本用途、**硬件影响标注**（哪些会短暂触发风扇动作）、历史结论 |
| [DevLog/](DevLog/README.md) | 开发者 | **开发过程日志** —— 可行性调研、决策时间线、踩坑记录（内部工作记忆脱敏导出，append-only，历史命名以当时为准） |
| 本目录七份文档 | 开发者 | 技术原理 / 开发约定 / 界面说明 / 项目结构 / 已知限制与路线图 / 原生镜像 CI 与发布 / 故障档案与修复跟踪 —— 见上方[文档索引](#文档索引) |

> 开发期探查脚本位于 `Probe/Powershell/`，由 `Script/` 下的 `.cmd` 启动器（UAC 提权）调用 —— 详见 [Probe/README.md](../Probe/README.md)。

---

## 建议阅读路径

**第一次接触本项目**

1. [根 README](../README.md) —— 先知道它是什么、怎么跑起来
2. [ProjectStructure.md](ProjectStructure.md) —— 建立目录与构建的整体印象
3. [TechnicalPrinciples.md](TechnicalPrinciples.md) —— 理解核心机制

**要动手改代码**

1. [DevelopmentConventions.md](DevelopmentConventions.md) —— **必读**，每条都是踩坑换来的
2. [ProjectStructure.md](ProjectStructure.md) 的「构建与打包机制」
3. 具体实现细节 → [TechnicalPrinciples.md](TechnicalPrinciples.md)

**只是想用这个工具**

→ 看 [`Dist/README.md`](../Dist/README.md) 即可，**无需阅读本目录**。

---

## 文档维护约定

- **技术细节只写在本目录**，根 README 保持精简，两者不重复；
- **文档索引与内容说明统一在本页维护**（含「全项目文档一览」），根 README 只保留指向本页的路由入口 —— 新增文档时改这里，不改主页；
- 改动桥接层 / 性能设计 / WMI 通路 → 同步更新 `TechnicalPrinciples.md`；
- 新增硬性约定（踩坑换来的）→ 追加到 `DevelopmentConventions.md`，并保持编号连续；
- 目录增删、构建机制变化 → 更新 `ProjectStructure.md`；
- 工作流（原生镜像 CI / tag / 发布）变化 → 更新 `NativeImageCi.md`；
- 完成路线图中某项、或发现新的能力限制 → 更新 `LimitationsAndRoadmap.md`；
- 文档中引用的**具体数值**（如 CapabilityID 十进制）**必须程序化换算核验，不能手算** —— 曾因此写错过一整张对照表。
