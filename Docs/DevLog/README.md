# 开发日志（DevLog）

> 由内部工作记忆**脱敏精编**而成：只保留有复用价值的决策、实测结论与踩坑教训，
> 过程性流水账（逐次编译记录、行数/字节数核对、重复确认清单）已略去。
> 日期按 `month` 级模糊化；技术结论的**权威版本**在 `Docs/` 各正式文档，本文只承担决策溯源与过程留档。

## 时间线概览

| 时段 | 里程碑 |
|---|---|
| 2026-09 | 可行性调研（三条硬件通道对比）→ 选型定案（Java 25 + OpenJFX + WMI/CIM 桥）→ v1「降温除尘」功能闭环；期间修复三轮关键缺陷（详见踩坑） |
| 2026-10 | 项目迁移与扁平化 / 全线命名收敛 / 文档体系拆分（`Docs/`）/ 管理员权限自提升 / 原生镜像 CI 落地（T4 重启，官方 GraalVM 路线） |

## 关键技术决策及依据

| 决策 | 依据 |
|---|---|
| 硬件通路选 WMI（CIM），不做 IOCTL / EC 直写 | `root\WMI` 下 36 个 `LENOVO_*` 类完整暴露；EnergyDrv 未安装（Legion 除尘原生走 WMI）；WinRing0 已进微软易受攻击驱动阻止列表 |
| 满速主通路 = `Fan_Set_FullSpeed`（而非 `SetFanCooling`） | 实测本机 `IsSupportFanCooling=0`、调用无效；`Fan_Set_FullSpeed` 是 Legion 时代的语义迁移 |
| 温度/转速走 CapabilityID（`LENOVO_OTHER_METHOD.GetFeatureValue`） | 旧通路 `GetCPUTemp`/`GetGPUTemp` 本机恒 0；与 LenovoLegionToolkit（LLTK）实现一致 |
| 语言栈 = Java 25 + OpenJFX（JVM 基线优先） | 四语言对比：Python 无真原生编译、Go GUI 弱、TS 硬件桥接脆、C# 用户未选；接受探索性风险 |
| 原生镜像走官方 `native-maven-plugin` + GitHub Actions CI | gluonfx 只兼容 Gluon 自家 GraalVM（22.1.0.1 / JDK17），与 Java 25 基线冲突；用户决策禁用 gluonfx |
| 交付形态默认 = fat jar，产物名不带版本号 | 不发布 Maven 仓库，固定名让归档与启动脚本零版本匹配逻辑，升级版本零改动 |

## 核心实测结论（证据见 `Script/Evidence/` 与 [技术原理](../TechnicalPrinciples.md)）

- **接口事实（IAH7H / BIOS J2CN57WW）**：FanID 取 0/1（早期误用 1/2 曾致读数全 0）；
  `Fan_Set_FullSpeed` 只有 `Status` 参数 —— 满速除尘是**全局开关**，带 FanID 的全是读取方法，
  写不分风扇 → **单风扇定向控制在固件层不支持**；v2 唯一路径是 `Fan_Set_Table` 整块 `byte[]` 曲线
  （需逆向二进制布局，LLTK 亦为整体封装）。
- **权威属性名（以 LLTK 源码为准，此前全部猜错）**：`CurrentFanSpeed` / `CurrentSensorTemperature` /
  `Fan_Get_FullSpeed` 返回属性 `Status`(bool) / `Fan_Set_FullSpeed` 参数 `Status`(int)。
  机理：属性名不存在 → `$null` → `[int]$null = 0`，表现为"恒为 0"。
- **CapabilityID 十进制对照**（程序化换算核验，曾手算整表全错）：CPU 温度 84148224；
  GPU 温度 84213760；CPU 转速 67305473；GPU 转速 67305474；满速开关 67239936。
- **性能**：进程创建是主要开销（单次 ≈2.7s 地板成本）；7 子进程/轮 ≈20.9s → 批量快照 1 次/轮 ≈2.95s，
  **降幅 86%**。
- **风扇方位**：风扇 1 = 左 / CPU 侧、风扇 2 = 右 / GPU 侧（依据本机风扇表热特性 +
  配件商布局约定**推断**，非拆机核实；LLTK 的 FanTableType 枚举是曲线表类型，不可套用为物理方位）。

## 踩坑教训（P0 级，长期有效）

**构建 / 打包**

- 非模块化 fat jar 的 Main-Class 不能继承 `javafx.application.Application`（报"缺少 JavaFX 运行时组件"）→ `Launcher` 普通类转发模式；
- `module-info.class` 排除必须含 `META-INF/versions/*/module-info.class`；
- pom 的 XML 注释禁止出现 `--`（两次踩坑，已形成肌肉记忆）。
- 原生镜像的**反射与 JNI 是两套清单**：注册了「类可达」不等于允许原生库回调它。漏 JNI 可达类时
  构建毫无征兆，真机表现为 native 拿到空引用继续跑 → `WinApplication._runLoop` 处 segfault；
  同族问题在 AHA 踩过三次（#35 反射 / #37 JNI 类查找 / #39 平台子类成员）；
- 「扫 fat jar 的类清单」生成元数据有一个**根本盲区**：只能看到 jar 里存在的类，看不到
  只被 native 源码按字面量查找的类（`FindClass("java/lang/Runnable")`、平台子类的 `Get*ID` 目标）。
  这类清单只能来自 native 源码扫描 + 跨项目已验证清单 → 已改为脚本生成 + 构建期守卫；
- 元数据清单会**静默失效**：类名写错既不报错也不生效（`font.dwrite` 的真实包是
  `font.directwrite`）→ 守卫里加反向检查；
- 只在 `push` 触发 CI 等于把验证推到合并之后（PR #3 的产物问题就这么漏过去的）→
  已加 `pull_request` 触发，发布作业用 `if` 挡在 PR 之外。
- **Windows exe 缺清单会导致「界面能开但字体发虚」**：无清单的进程被 Windows 判为
  DPI 不感知，整窗被位图放大（字形按 96 DPI 枢格化再拉大）。判据便宜且强：看启动日志的
  系统版本行 —— 原生包报 `Windows 8.0 build 9200`，JVM 模式报真实的
  `Windows version 10.0 build 26300`。已用 `-H:NativeLinkerOption` 嵌 `exe.manifest`，
  并加自证④从 exe 字节里复查。

**Windows 批处理**

- `.cmd` 含非 ASCII 时必须 **CRLF + UTF-8**：根因是 LF 行尾导致的字节级读取漂移（与编码无关，`chcp` 非解法）；
- `sed -i` 会剥掉 CRLF 的 CR —— 批量替换 `.cmd` 后必须复查行尾并补回；改 `.cmd` 优先用编辑器工具。

**桥接层**

- WMI v1（`Get-WmiObject`）读不到 Lenovo 内嵌对象，必须用 CIM（`Get-CimInstance` / `Invoke-CimMethod`）；
- 子进程输出解析不得只取第一行（前置警告会静默吞掉真实结果）；取 `V=` 前缀行或最后非空行。

**验证方法论**

- 拼接生成的 PowerShell 必须过 `Parser::ParseInput` 语法校验 + 反射导出肉眼核对；
- 逆向厂商私有接口先找权威开源实现（LLTK），不猜字段名；
- 基准 / 测试脚本禁止静默吞异常（否则产出"0ms"假数据）；
- 判断程序启动成功 = 日志无报错 + PID 匹配（只看进程存在会误判）；
- 文档中的数值对照表必须程序化换算核验，不能手算后凭印象写入。

## 流程教训

- 交付形态（jar / 安装包 / exe / 原生镜像）属**用户决策**，不得自行推定并动手；改构建配置前必须确认；
- AI 开发环境「完成后自动打开文件」为硬编码行为（四层核验：配置层 → 程序包关键字扫描 → 实现代码 → 文档与社区 issue 交叉验证）→ 交付统一改静默（回复中给路径），已固化为跨项目偏好；
- 内部记忆 → 开发日志的脱敏导出已沉淀为可复用技能（规则引擎 + 残留扫描 + 日期模糊化），各项目规则按需现配。

## 待办状态快照

| 编号 | 事项 | 状态 |
|---|---|---|
| T1 | `Fan_Set_Table` 签名探查（v2 单风扇曲线可行性） | 待提权运行 |
| T2 | v1 功能实机确认清单 | 待实测 |
| T3 | 历史探查脚本补跑 | 待提权运行 |
| T4 | GraalVM 原生镜像 CI | 进行中（工作流已落地，待实跑调通） |

> 现行待办与能力边界的权威版本：[已知限制与路线图](../LimitationsAndRoadmap.md)。
