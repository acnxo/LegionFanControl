# 项目结构

> 返回 [项目主页](../README.md) · 相关文档：[开发约定](DevelopmentConventions.md) · [技术原理](TechnicalPrinciples.md) · [原生镜像 CI 与发布](NativeImageCi.md)

目录自身即 Maven 工程根。

---

## 项目概况

| 项目 | 说明 |
|---|---|
| 目标机型 | **Legion Y9000P IAH7H / 82RF**（开发与验证机器） |
| 技术栈 | **Java 25 + OpenJFX 25.0.4**，经 PowerShell 桥接联想 WMI 接口 |
| 交付形态 | **可直接运行的 fat jar**，归档于自包含的 [`Dist/`](../Dist/README.md) 目录 |
| 许可证 | **GPL-3.0**（见 [`LICENSE`](../LICENSE)） |

> 联想 WMI 接口同族，其他 Legion 机型**理论上可用**，但只在上述机型上实测验证过。
> 实现层面的细节见 [技术原理](TechnicalPrinciples.md)。

---

## 目录结构

```
.
├── README.md                    项目主页（是什么 / 怎么用）
├── LICENSE                      GPL-3.0
├── .gitignore                   Java 通用忽略规则（含 *.jar —— 见下方说明）
├── pom.xml                      Maven 构建配置（`finalName` 决定产物名，不含版本号）
├── exe.manifest                 Windows exe 清单（DPI 感知 / supportedOS）；由 native-image profile
│                                经 `-H:NativeLinkerOption` 交给 MSVC 链接器，构建后由自证④复查
├── exe.rc                       程序图标资源脚本（纯 ASCII；由 rc.exe 编译为 exe.res）
├── exe.ico                      exe 文件图标本体（单条 200×200 PNG-in-ICO；来源见 Docs/Asset/README.md）
├── .github/
│   ├── dependabot.yml           依赖自动升级（每周；PR 指向 dependa 分支）
│   └── workflows/
│       └── main.BuildReleaseWindowsX64.yml    CI + 发布工作流（原生镜像 + JVM 包，Windows x64；push main/dev + 手动触发）
├── Dist/                        **可分发文件夹**（自包含，整个目录拷走即可运行）
│   ├── README.md                   面向使用者的说明（快速开始 / 环境要求 / 常见问题）
│   ├── README.md                   面向使用者的说明（快速开始 / 环境要求 / 原生镜像版与自动升级）
│   ├── Bootstrap.cmd               一键启动脚本（JVM 版；双击即运行，自动提权）
│   ├── LegionFanControl.jar        应用的 fat jar（mvn package 后自动导出；不入库）
│   ├── UpdateJvm.cmd               JVM 版「自动升级」入口（双击；覆盖 jar 本体）
│   ├── UpdateJvm.ps1               ↑ 的实现（PowerShell：查发布页 / 下载 / 解压 / 覆盖）
│   ├── UpdateNative.cmd            原生镜像版「自动升级」入口（双击；转调同目录的 .ps1）
│   ├── UpdateNative.ps1            ↑ 的实现（PowerShell：查发布页 / 下载 / 解压 / 覆盖 exe）
│   └── LegionFanControl.exe        原生镜像版本体（单文件自包含；由 Updater 下载；不入库）
├── src/main/
│   ├── java/io/github/acnxo/legion/fan/
│   │   ├── Launcher.java                入口（普通类，转发到 FanControlApp；顺带触发提权）
│   │   ├── Elevation.java               管理员权限自提升（UAC 重启自身，失败不阻断启动）
│   │   ├── FanControlApp.java           组装层：加载 FXML 视图 + 装配控制器与业务层（不写布局 / 业务）
│   │   ├── FanControlController.java    视图控制器：@FXML 控件注入、事件处理、显示格式化
│   │   ├── FanControlService.java       业务逻辑：轮询 / 倒计时状态机 / 复位（不依赖 JavaFX）
│   │   ├── ConsoleWindow.java           控制台窗口开关（--console / -Dlegion.console）
│   │   ├── NativeImageRenderDefaults.java  原生镜像内的渲染默认值（issue #48 临时缓解，可关可退）
│   │   ├── RenderDiagnostics.java       渲染诊断：只打日志，用于原生 / JVM 对照（issue #20）
│   │   └── wmi/
│   │       ├── WmiBridge.java                 接口契约 + Snapshot 记录
│   │       ├── PowerShellWmiBridge.java       PowerShell 桥接实现（当前基线）
│   │       ├── JnaComWmiBridge.java           JNA/COM 实现（桩，已后置）
│   │       └── WmiException.java
│   ├── resources/Asset/Logo.png         窗口 / 任务栏图标（JavaFX；原生镜像由 resource-config 收录）
│   ├── resources/io/github/acnxo/legion/fan/FanControlView.fxml   界面结构（FXML；原生镜像需登记资源）
│   ├── resources/io/github/acnxo/legion/fan/FanControlView.css    界面样式（CSS：间距 / 配色）
│   └── resources/META-INF/native-image/ native-image 配置（反射 / JNI / 资源，类路径自动发现）
├── Probe/                       开发期探查与辅助工具（不参与打包）
│   ├── README.md                    各探查脚本与工具逐一说明（含硬件影响标注）
│   ├── Java/                        工具源码
│   │   ├── DumpScript.java              反射导出生成的快照脚本，供语法校验
│   │   ├── Compare.java                 量化进程创建开销与刷新周期
│   │   └── Bench.java                   单次子进程调用耗时实测
│   └── Powershell/                  硬件探查脚本（需提权运行一次）
│       ├── FanWmiV1.ps1                 首次接口全量探查（方法清单 + 风扇表转储）
│       ├── FanWmiV2.ps1                 修正 FanID 后的复探与签名校准
│       ├── FanRpmV3.ps1                 转速通路对比（CIM vs WMI v1）
│       └── FanShape.ps1                 按权威属性名取值结构确认
├── Script/                      提权启动器、诊断工具与产出归档
│   ├── run-*-elevated.cmd              UAC 提权启动器（调用 Probe/Powershell 下的脚本）
│   ├── ValidateScripts.ps1            脚本语法校验（范围见「开发约定」第 4 条）
│   ├── GenerateNativeImageMetadata.py 生成 native-image 元数据（反射 / JNI / 资源，按产物存在性裁剪）
│   ├── CompareScreenshots.py          对比两张截图判定文字抗锯齿类型（issue #20 的验收仪器）
│   └── Evidence/                       探查与验证产出（归档）
└── Docs/                        技术文档（本目录）
    ├── README.md                     文档索引（导读与建议阅读路径）
    ├── TechnicalPrinciples.md        技术原理（架构 / 性能 / WMI 接口 / 踩坑）
    ├── DevelopmentConventions.md     开发约定（9 条硬性约定）
    ├── UserInterface.md              界面说明（显示项 / 按钮状态 / 读数约定）
    ├── ProjectStructure.md           项目结构（本文件）
    ├── LimitationsAndRoadmap.md      已知限制与路线图
    ├── NativeImageCi.md              原生镜像 CI 与发布（触发 / 工具链 / tag 规则 / 踩坑）
    ├── DevLog/                       开发过程日志（内部工作记忆脱敏导出）
    └── Asset/                        文档配图
        └── IMG/UI-Main.png               界面截图
```

> `target/` 为构建产物，不纳入版本管理；`Probe/` 下的工具不参与打包（不在 `src/` 下）。
> `Probe/Powershell/` 下的探查脚本为硬件接口探查，**其中 V1/V2 会短暂触发风扇动作并自动复位**，其余为纯读取 —— 详见 [Probe/README.md](../Probe/README.md)。

---

## 各目录职责

| 目录 | 职责 | 是否参与打包 |
|---|---|---|
| `src/` | 应用源码（唯一进入 jar 的代码） | ✅ |
| `Dist/` | 交付物：可分发文件夹（脚本 + fat jar） | —（承载产物）|
| `Probe/` | 开发期探查与辅助工具（一次性排查 / 基准 / 校验） | ❌ |
| `Script/` | 提权启动器 + 诊断工具（元数据生成 / 截图对比）+ 探查产出归档 | ❌ |
| `Docs/` | 技术文档（[索引](README.md)） | ❌ |

---

## 构建与打包机制

### 从源码构建

前置要求：**JDK 25** + **Maven 3.x**（`maven.compiler.release=25`）。在项目根目录执行：

```bash
mvn clean package
```

**开发调试**：`mvn javafx:run`（需在**管理员终端**中执行 —— 联想 WMI 会拒绝非管理员访问）。

> 使用者无需构建 —— 双击 `Dist/Bootstrap.cmd` 直接运行已打包的 jar 即可；本节面向开发者。

### 产物名固定

pom 中 `<finalName>LegionFanControl</finalName>` 固定产物名（**不含版本号**），理由见[开发约定第 9 条](DevelopmentConventions.md#9-交付产物文件名不带版本号)。

```
Dist/LegionFanControl.jar      # 可分发位置（打包后自动导出）
target/LegionFanControl.jar    # 构建目录副本
```

### Dist 自动导出

`package` 阶段由 `maven-resources-plugin`（execution `copy-fat-jar-to-dist`，声明在 shade 插件之后）把合并后的 fat jar 复制到 `Dist/`。

- execution 位于 shade 之后 + 同阶段按声明顺序执行 → **保证复制的是合并后的 fat jar**，不会拿到 shade 前的原始 jar；
- `mvn clean package` **不会**删除 `Dist/` 中的 `Bootstrap.cmd` 与 `README.md`（clean 只清 `target/`，resources 插件只往该目录复制 jar、不清理该目录）。

### 构建注意

若 `mvn clean` 因 `target` 目录被占用而失败（程序仍在运行），关闭程序后重试，或直接执行 `mvn package`。

---

## 依赖自动更新

依赖升级由 **Dependabot** 自动提 PR，配置见 [`.github/dependabot.yml`](../.github/dependabot.yml)。

| 生态 | 覆盖范围 | 检测频率 |
|---|---|---|
| `maven` | `pom.xml`（版本集中在 `<properties>`） | 每周一 09:00（Asia/Shanghai） |
| `github-actions` | `.github/workflows/*.yml` 中 `uses:` 引用的 action | 每周一 09:00（Asia/Shanghai） |

**合入路径**：Dependabot 的 PR **只指向 `dependa` 分支**（配置里的 `target-branch`），
不会直接打到默认分支：

```
dependabot/xxx ──PR──▶ dependa ──PR（人工发起）──▶ 默认分支
```

**处理方式**：

1. `minor` / `patch` 已按生态合并为单个 PR，确认 CI 全绿即可合入 `dependa`；
2. `major` 不并入分组，逐个单独提 PR —— 先读上游变更说明再决定；
3. `dependa` 上积累若干升级并验证稳定后，再按正常流程提 PR 合入默认分支；
4. 手动触发检测：仓库 **Insights → Dependency graph → Dependabot** → **Check for updates**。

**首次启用前需准备**（缺任一条 Dependabot 都不工作）：

1. `.github/dependabot.yml` 必须位于**默认分支** —— Dependabot 只从默认分支读取配置；
2. `dependa` 分支必须已存在，且其中的 `pom.xml` 与 `.github/workflows/` 与默认分支一致 ——
   指定 `target-branch` 后，读取的是**该分支**上的清单文件；
3. `dependa` 须长期保留，不要随版本发布删除。

```bash
git switch -c dependa          # 从默认分支创建
git push -u origin dependa
```

**不在覆盖范围内**：pom 的 `maven.compiler.release` 与工作流里的 GraalVM 发行版
（`version: '25.4'`）属工具链基线，须手工升级 —— Dependabot 不会动它们。

---

## 原生镜像构建（GraalVM native image）

- **工具链**：官方 GraalVM（`org.graalvm.buildtools:native-maven-plugin` 0.10.6），**不引入 Gluon / gluonfx**（2026-10-10 用户决策：禁止 gluonfx）。
- **入口**：Maven profile `native-image` —— 仅 `mvn -Pnative-image package` 时生效，与默认 jar 打包完全区隔；原生编译失败只影响本次 `-P` 构建，**不影响 jar 产物**。
- **CI 与发布**：[`.github/workflows/main.BuildReleaseWindowsX64.yml`](../.github/workflows/main.BuildReleaseWindowsX64.yml) —— `windows-latest` + GraalVM JDK 25（community）+ MSVC 工具链；触发方式为 `push`（`main` / `dev`）+ `workflow_dispatch`，构建产物挂到 GitHub Release。完整的触发方式、工具链与 tag 规则见 [原生镜像 CI 与发布](NativeImageCi.md)。
- **产物**：`target/LegionFanControl.exe`（**不带版本号**，与 jar 同规则），**单文件、自包含**。JavaFX 的平台原生 DLL（`glass.dll` / `prism_d3d.dll` / `javafx_font.dll` 等）**作为资源编进镜像**（`resource-config.json` 的 `.*\.dll$` 规则），运行期由 OpenJFX 的 `NativeLibLoader` 从镜像内资源解到 `~/.openjfx/cache/` 后加载 —— 发布包因此只需一个 exe。不能改成「DLL 与 exe 同目录分发」：原生镜像下该路径不在 `NativeLibLoader` 的搜索范围内，详见 [原生镜像 CI 与发布](NativeImageCi.md) 第 5 节第 6 条。
- **配置**：`src/main/resources/META-INF/native-image/` 下三份 JSON（`reflect-config.json` / `jni-config.json` / `resource-config.json`），native-image 从类路径自动发现。首版为**静态手工清单**（JavaFX 的反射 / JNI / 资源覆盖面有限），预计需按 CI 日志迭代；必要时在实机跑 tracing agent 生成精确配置。
- **已知形态差异**：exe 形态下 `Elevation` 探测不到 jar 路径，会**降级为不提权直接启动**（不阻断），需以管理员身份运行 exe 本体；说明见[技术原理 · 管理员权限与提权保障](TechnicalPrinciples.md)。

---

## 版本管理说明

### .gitignore 与「不入库」的产物

`.gitignore` 含 `*.jar` 与 `*.exe` 规则，因此 `Dist/LegionFanControl.jar` 与
`Dist/LegionFanControl.exe` **都不会**被提交；若希望仓库自带可运行产物，需在该文件中加白名单。

两个产物的来源不同：`*.jar` 由 `mvn package` 自动导出，`*.exe` 由原生镜像工作流出包后
再经 `Dist/UpdateNative.cmd`（exe）或 `Dist/UpdateJvm.cmd`（jar）升级到本地 —— 两者都是构建产物，不入库。

### 入库范围

- **入库**：全部文档（根 `README.md`、`Dist/README.md`、`Probe/README.md`、`Docs/*` 含 `Docs/Asset/` 配图）与探查产出（`Script/Evidence/*`）；
- **不入库**：`target/`（由 `.gitignore` 覆盖）、`Dist/LegionFanControl.jar`（由 `*.jar` 规则排除）
  与 `Dist/LegionFanControl.exe`（由 `*.exe` 规则排除），见上。
