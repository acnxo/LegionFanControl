# 原生镜像 CI 与发布

> 返回 [项目主页](../README.md) · 相关文档：[项目结构](ProjectStructure.md) · [开发约定](DevelopmentConventions.md) · [已知限制与路线图](LimitationsAndRoadmap.md)

本文件是 [`.github/workflows/main.BuildReleaseWindowsX64.yml`](../.github/workflows/main.BuildReleaseWindowsX64.yml) 的**权威说明**：
触发方式、作业与步骤、编译工具链、产物形态、tag 与发布规则，以及踩过的坑。

工作流里的注释只写「**为什么**这么写」；机制与规则的全貌收在本文件。**改动该工作流之前先读本文件。**

---

## 1. 全景

### 触发方式

| 事件 | 分支 | 用途 |
|---|---|---|
| `workflow_dispatch` | 任意 ref | 手动试跑、重出包 |
| `push` | `main` | 正式版发布（`dev` → `main` 的 PR 合并）、普通 PR 合并 |
| `push` | `dev` | 开发期预发行 |
| `pull_request` | `main` / `dev` | **只构建、不发布**：改元数据 / 参数时能在 PR 阶段就看到构建结果（`publish` 用 `if` 排除 PR 事件） |

> 上表下方的作业图只画了 `push` 与手动触发两条；`pull_request` 走同一条 `build-native-image`，只是不进 `publish`。

> GitHub Actions **无法在触发层面区分「PR 合并」与「直接 push」**：`push` 事件里两者都会进来。
> 因此 tag 用哪种编号段，由工作流内**反查 PR** 的结果决定 —— 见第 4 节。

原生编译失败**只影响本工作流**：默认的 jar 打包、`Dist/` 导出、本地开发都不受影响。

### 作业图

```
workflow_dispatch ─┐
push(main | dev) ──┴─▶ build-native-image（windows-latest，timeout 90min）
                         ├─ Checkout
                         ├─ SetupGraalvm          GraalVM Community JDK 25
                         ├─ InitMsvcToolchain     MSVC x64 开发环境
                         ├─ PrintEnvironmentInfo  环境自证（排查失败先看这里）
                         ├─ BuildNativeImage      profile native-image 构建
                         ├─ CollectArtifacts      只收 exe（并自证 DLL 已编入镜像）
                         ├─ ReadProjectVersion    pom.xml → 作业 output「version」
                         └─ UploadArtifact        Actions 产物
                                    │ needs
                                    ▼
                         publish（ubuntu-latest）
                         ├─ DownloadArtifact      取回上一步产物（v4 会解压，不是给你 zip）
                         ├─ ComposeTag            反查 PR → 拼 tag
                         ├─ PackageZip            自己打 zip（内含且仅含 exe）
                         └─ PublishRelease        挂到 GitHub Release 页面
```

`publish` 用 `needs:` 依赖构建作业：**构建失败时发布作业被跳过**，不会产生空 release。

### 实测耗时

约 **3 分钟**（2026-10-10 实测 2m50s / 2m54s / 2m58s），大头在原生编译。

---

## 2. 编译工具链

| 组件 | 版本 / 来源 | 作用 |
|---|---|---|
| Runner | `windows-latest` | 原生镜像**不能交叉编译**，Windows x64 产物必须在 Windows 上产出 |
| JDK | **Oracle GraalVM 25.4**（25 系列的 innovation 发行版）—— `graalvm/setup-graalvm@v1`，`version: '25.4'`、`distribution: graalvm`、`cache: maven` | 与 pom 的 `maven.compiler.release=25` 对齐。**注意用 `version` 而不是 `java-version`**：后者只会解析到 25 系列最新的 Community Edition（25.0.2） |
| Maven | Runner 自带 **3.9.16** | 构建驱动 |
| MSVC | `ilammy/msvc-dev-cmd@v1`（`arch: x64`） | **native-image 链接阶段的必备工具链** |
| native 插件 | `org.graalvm.buildtools:native-maven-plugin` **1.1.14** | 原生镜像编译（写在 pom 的 `native-image` profile 内） |

### 为什么需要 MSVC

GraalVM 在 Windows 上把字节码编成原生二进制时，最后一步要调用系统链接器把目标文件链接成 `.exe`，
这一步依赖 MSVC 的 `cl.exe` / `link.exe` 与 Windows SDK 的导入库。
`ilammy/msvc-dev-cmd` 的作用就是把 Visual Studio 的开发环境变量注入当前作业。

`PrintEnvironmentInfo` 会显式检查 `cl.exe` 是否在 `PATH`，缺失时打印
`WARNING: cl.exe 不在 PATH，native-image 链接可能失败` —— **链接类报错，先回看这一步的输出**。

### profile 隔离

原生编译全部收敛在 pom 的 `native-image` profile 内，只有带 `-Pnative-image` 时才生效：

| 构建 | 命令 | 产物 |
|---|---|---|
| 默认（日常开发 / 发布 jar） | `mvn clean package` | `target/LegionFanControl.jar`（fat jar），并导出到 `Dist/` |
| 原生镜像（本工作流） | `mvn -B -ntp -Pnative-image -DskipTests clean package` | 追加 `target/LegionFanControl.exe` |

两套互不影响：原生编译挂掉不会波及 jar 产物。

---

## 3. 步骤详解（编译相关）

### BuildNativeImage

```powershell
mvn -B -ntp -Pnative-image -DskipTests clean package
```

- `-B` 批处理模式、`-ntp` 关闭下载进度条 —— CI 日志必需，否则进度条会淹没日志；
- `-DskipTests`：本工作流只验编译链路，单元测试由其它渠道负责；
- profile 把原生编译绑定在 `package` 阶段，并**先正常打出 fat jar**、再产出原生镜像 ——
  因为下一步要从 fat jar 里提取 JavaFX 原生 DLL（见下）；
- 步末自证产物是否存在并打印大小；**找不到 exe 只告警不失败**，真正的失败判定留给
  `CollectArtifacts` —— 避免在「原因还不明确」时就中断，白丢后面的诊断信息。

### CollectArtifacts

产物收集**只收 exe** —— 发布包是**单文件、自包含**的。步骤做两件事：

```powershell
# 1) exe → stage/（发布的全部内容）
# 2) 解压 target/LegionFanControl.jar 清点 DLL —— 不是要分发它们，而是自证它们存在
```

**为什么包里不放 DLL**：JavaFX 的平台原生库（`glass.dll` / `prism_d3d.dll` / `javafx_font.dll` 等）
已被**编进镜像**（见第 5 节第 6 条）。「DLL 放在 exe 同目录」这条路在原生镜像下**并不成立**：
OpenJFX 的 `NativeLibLoader` 在原生镜像里既拿不到 jar 相对路径、也命中不了 `java.library.path`，
唯一可行通路是 `loadLibraryFromResource()` —— 把类路径根部的 `/<libname>.dll` 解到
`~/.openjfx/cache/<版本>/<架构>/` 再 `System.load`。所以 DLL 必须以**资源**形式编进 exe。

- DLL 的来源是 shade 合并后的 fat jar 根部（`javafx-*-win.jar` 已被合并进去）；
- 资源纳入由 [`resource-config.json`](../src/main/resources/META-INF/native-image/resource-config.json)
  的 `.*\.dll$` 规则完成。

两道自证（因为包里不再带 DLL，漏编就会变成一个「只有用户才发现的坏包」）：

1. **fat jar 里必须有 DLL** —— 它没有，镜像里必然也没有；
2. **exe 里必须能查到 `glass.dll` / `prism_d3d.dll` / `javafx_font.dll` 这几个名字** ——
   直接在 exe 字节流里搜资源名，作为「资源确实进了镜像」的低成本证据。

> 自证②是启发式检查（GraalVM 把资源名当字符串存在镜像里）。若某天它误报，放宽此处即可，
> 但别直接删掉 —— 它是「发布包只带 exe」这一设计唯一的自动守卫。

### ReadProjectVersion

版本号**只有一个来源**：`pom.xml` 顶层的 `<version>`。

```powershell
[xml]$pom = Get-Content -Raw -LiteralPath 'pom.xml'
$node = $pom.SelectSingleNode('/*[local-name()="project"]/*[local-name()="version"]')
```

- 用 XPath 的 `local-name()` 而不用 `$pom.project.version`：pom 带默认命名空间，
  直接点取属性名不可靠；
- **刻意不二次调用 Maven**（曾用 `mvn help:evaluate -Dexpression=project.version -q -DforceStdout`）：
  那一步不仅慢约 5 秒，还把自身报错行吞掉，令 CI 只剩一行 `LifecyclePhaseNotFoundException`
  的收尾提示、根因不可见 —— 踩坑经过见第 5 节第 3 条；
- 结果写入**作业级 output `version`**，供 `publish` 作业拼 tag（不重复解析）。

产物名分两层，别混：

| 对象 | 是否带版本号 | 原因 |
|---|---|---|
| `LegionFanControl.exe` / `.jar` 本体 | **不带** | [开发约定第 9 条](DevelopmentConventions.md#9-交付产物文件名不带版本号) |
| Actions artifact 名 / Release tag | **带** | 归档与发布需要区分版本 |

### PackageJvmZip（JVM 版一起发）

原生包只解决「不用装 JDK」这一种用法；**JVM 版（fat jar）同样需要「下载即用」**：

- 触发场景：① 排查原生镜像与 JVM 模式的差异（如字体渲染，issue #20）；
  ② 原生包出问题时的回退方案；
- 做法：`Compress-Archive -Path 'Dist/*'` 打成 `LegionFanControl-<版本>-jvm-windows-x64.zip`
  （内容与仓库 `Dist/` 一致：`Bootstrap.cmd` / `README.md` / `UpdateNative.*` / `LegionFanControl.jar`）；
- 注意：这里是**未包目录层**的中间产物。`publish` 作业会解开它、按最终资产名（带编号段）
  **重打成自带一层同名目录的包**，并统一改名成 `LegionFanControl-<版本>[.<编号段>]-…zip`（与 tag 同源），
  详见第 4 节「发布内容」与 `DevelopmentConventions.md` 第 10 条；
- **单独一个 artifact**（不进 `stage/`）—— 原生包的 `PackageZip` 有「assets 只能有一个文件」的断言；
- 自证：包里必须有 `LegionFanControl.jar` / `Bootstrap.cmd` / `README.md`，且**不得**含 `*.exe`
  （那是原生包的事）；
- `publish` 作业重打后由 `files: LegionFanControl-*.zip` 通配一起挂到 Release。

> 两个包的**版本号与源码完全相同**，因此可以放心拿来做对照。

### UploadArtifact 与发布的关系

`UploadArtifact` 产出的是 **Actions 产物**（保留 14 天，入口在 workflow run 页的「Artifacts」区）；
`publish` 作业把它取回后**另发一份到 Release 页面**。两者用途不同：前者方便调试与临时取用，
后者是真正的交付口。

注意：`download-artifact` 取回的是**解压后的文件**（v4 没有「保留 zip」选项），
所以发布页上那个 zip 由 `PackageZip` 步骤现场打包 —— 见第 5 节第 8 条。

---

## 4. tag 与发布规则

`publish` 作业先反查「本次提交对应哪个 PR」，再据此决定 tag：

```
                  ┌── push 到 main 且 PR 的 head 分支是 dev ──▶ V<版本>              正式 release
反查 PR ──────────┤
（gh api          ├── 反查到 PR（其它情形）                ──▶ V<版本>.<PR 五位>       Pre-release
 commits/<sha>    │
   /pulls）       └── 反查不到 PR                           ──▶ V<版本>.<yyyyMMddHHmm>   Pre-release
```

完整判定表：

| 事件 | 目标分支 | PR 反查结果 | tag | Release 类型 |
|---|---|---|---|---|
| `push` | `main` | PR，head = `dev` | `V0.2.0` | **正式** |
| `push` | `main` | PR，head ≠ `dev` | `V0.2.0.00042` | Pre-release |
| `push` | `main` | 无 PR（直接 push） | `V0.2.0.202610101530` | Pre-release |
| `push` | `dev` | 有 PR | `V0.2.0.00042` | Pre-release |
| `push` | `dev` | 无 PR | `V0.2.0.202610101530` | Pre-release |
| `workflow_dispatch` | 任意 | 有 PR | `V0.2.0.00042` | Pre-release |
| `workflow_dispatch` | 任意 | 无 PR | `V0.2.0.202610101530` | Pre-release |

> **PR 编号优先取 squash 提交标题里的 `(#NN)`**（最可靠），拿不到才按提交反查（后者可能张冠李戴）。
> **防覆盖守卫**：若算出的 tag 已存在，自动降级为 UTC 时间戳段 —— **绝不重用 tag**，
> 否则会把既有 release 的资产静默换掉，而发布列表上根本看不出新条目（2026-10-10 踩过，见第 5 节第 16 条）。

规则要点：

- **编号段五位、不足左补 0**（PR #1 → `00001`），使同版本内可按编号排序对齐；
- **只有「push 到 `main` 且 PR 的 head 是 `dev`」算正式版**。手动触发即使落在 `main`、
  即使那个提交所属 PR 的 head 是 `dev`，也判为预发行 —— 手动重跑不该产生正式 release；
- **反查不到 PR 时降级为 UTC 时间戳**（`date -u +%Y%m%d%H%M`），**不使用 `00000` 之类的固定占位**：
  固定占位会让两次「无 PR 的直接 push」撞到同一个 tag；
- 时间戳取 **UTC**，避免 runner 时区漂移造成同一分钟内的判断不一致；
- 预发行会同时置上 Release 的 `prerelease` 标记：**不进 `releases/latest`**，不会被误当正式版本。

PR 反查走 `gh api repos/<owner>/<repo>/commits/<sha>/pulls`。**squash 合并同样能反查到**
（实测：squash 提交 `07c13ad` → PR #1，且 `head.ref` 可读）—— 所以判定依据取 PR 的 `head`
分支名，而不去解析提交信息文本。

### 发布内容

| 项 | 值 |
|---|---|
| tag | 见上表 |
| Release 名 | 同 tag（`V<版本>[.<编号段>]`，如 `V0.2.0.00026`）—— 本页同时挂 JVM 包与原生包，不再带 `-Native-Image` 后缀，交付物以资产名区分 |
| 资产 | **三个**：`LegionFanControl-<版本>[.<编号段>]-native-windows-x64.zip`、`LegionFanControl-<版本>[.<编号段>]-jvm-windows-x64.zip` 与 `LegionFanControl-<版本>[.<编号段>]-native-build-report.zip`。<br>**资产名的版本段与 tag 同源**（`publish` 作业里 `asset_ver=${tag#V}`，单一来源、不重复推算）：tag `V0.2.0.00059` ⇒ 资产名 `LegionFanControl-0.2.0.00059-…zip`；正式版 tag 不带编号段，资产名也不带。<br>**包内自带一层与 zip 同名的目录**（目录名 = 资产名去掉 `.zip`，含编号段），包内**文件名保持固定、不带版本号** —— 故「解压到当前目录」只会多出一个同名文件夹，不会散落文件；升级脚本用递归查找定位本体（`DevelopmentConventions.md` 第 10 条）。<br>原生包由 `PackageZip` 现场打包（**内含且仅含 `LegionFanControl.exe`**）；<br>JVM 包由 build 作业的 `PackageJvmZip` 从 `Dist/` 打包（`Bootstrap.cmd` / `README.md` / `UpdateNative.*` / fat jar），`publish` 解开后按最终资产名重打，**下载即用，不必本地构建**；<br>**构建报告包**由 build 作业的 `PackageBuildReport` 打包（`LegionFanControl-build-report.html` 报告本体 + `reports/class_initialization_*.csv`），`publish` 同样重打且加同名目录层，仅供排查/审计 —— 名字里刻意不含 `windows-x64`，因此**不会被 `UpdateNative.cmd` 的 `*native-windows-x64.zip` 规则当成升级包**；报告缺失时该资产不产出，不阻塞发布 |
| 正文 | 版本 / 触发方式 / 用法 / 管理员权限要求，预发行另附提示 |

---

## 5. 踩坑记录（P0，长期有效）

1. **`GITHUB_TOKEN` 创建的 tag 不触发下游工作流**（GitHub 防递归的既定行为）。
   因此本工作流**不**另设一条「tag push → 发布」的工作流，而是在同一工作流内直接建 release；
   否则会形成「tag 打了但发布没跑」的隐式链路，且失败时难以定位。

2. **本仓库 Actions 默认权限是 `read`**（`repos/{owner}/{repo}/actions/permissions/workflow`
   返回 `default_workflow_permissions: "read"`）。建 tag / release 必须在 `publish` 作业上
   **显式申请 `permissions: contents: write`**；漏掉会以「权限不足」失败。

3. **PowerShell 7 会把原生命令的 stderr 一并并入管道**，`Select-Object -Last 1` 取到的可能是
   错误行。`ReadProjectVersion` 首次实跑（run `38036445252`）就是这样挂的：`[ERROR]` 整块输出
   被管道吞掉，日志里只剩 `[Help 1] … LifecyclePhaseNotFoundException` 一行，根因不可见，
   之后格式校验不通过才抛错。
   **教训**：从原生命令取末行做解析是脆的；能不用就不用（此处改为直读 `pom.xml`）。
   不得不解析时，要显式 `2>&1` 并**保留完整输出用于报错**，不要只留末行。

4. **`push` 事件无法区分「PR 合并」与「直接 push」**。想在「合并时」做某事，只能反查 PR 是否
   找得到；找不到时必须有一个**明确、不撞车**的降级行为（此处是 UTC 时间戳）。

5. **pom 的 XML 注释禁止出现双横线**（[开发约定](DevelopmentConventions.md) 第 3 条）。
   改 pom 注释时写成「见 CI 的 `--` 说明」这类句子，pom 会直接解析失败。

6. **JavaFX 的平台原生库必须作为资源编进镜像**。首次实机运行（`S:\Downloads`，2026-10-10）
   报 `Graphics Device initialization failed for : d3d, sw` + `Error initializing QuantumRenderer:
   no suitable pipeline found` —— 当时 DLL 虽与 exe 同目录，但原生镜像里 `NativeLibLoader`
   的搜索路径根本不包含「exe 同目录」。

   读 OpenJFX 源码（`javafx-graphics` 的 `com/sun/glass/utils/NativeLibLoader.java`）得到的
   搜索顺序是：

   1. `loadLibraryFullPath()` —— 按 `NativeLibLoader.class` 的资源 URL 定位。URL 以 `jrt:`（jlink 镜像）
      或 `jar:file:…!`（模块化 jar）开头才有效；**原生镜像里两者都不匹配，直接抛 `UnsatisfiedLinkError`**；
   2. `loadLibraryFromResource()` —— 把**类路径根部**的 `/<libname>.dll` 解到
      `~/.openjfx/cache/<版本>/<架构>/` 再 `System.load`；
   3. 遍历 `java.library.path` 逐个 `System.load`（原生镜像里该路径是**构建期固化**的，指不到用户机器）；
   4. 最后才 `System.loadLibrary(libname)`。

   结论：**第 2 条是原生镜像下唯一可行的通路**，前提是 DLL 作为资源在类路径根部
   （fat jar 根部正是如此）。所以要么把 DLL 编进镜像，要么产物根本不能运行 —— 没有第三条路。

7. **JavaFX 的启动链路全靠反射加载，元数据清单缺一条就「构建成功但一启动就崩」**。
   已实测/读源码确认的反射加载点：

   | 加载点 | 反射目标 |
   |---|---|
   | `PlatformFactory.getPlatformFactory()` | `com.sun.glass.ui.win.WinPlatformFactory`（`Class.forName` + 无参构造） |
   | `GraphicsPipeline.createPipeline()` | `com.sun.prism.<order>.<ORDER>Pipeline`；Windows 的 `PrismSettings.tryOrder` 是 **`d3d, sw`** |
   | `PrismFontFactory.getFontFactory()` | `com.sun.javafx.font.directwrite.DWFactory`（Windows 字体工厂，注意**不是** `dwrite` 包） |
   | `D3DResourceFactory.createStockShader()` | `com.sun.prism.shader.<name>_Loader` 的 `loadShader` 静态方法（**212 个**，名称随渲染动作变化） |
   | `PPSRenderer` / `PrRenderer` | `Pr<name>Peer` / `PPS<name>Peer`（**39 个**） |
   | `ImageStorage.tryCreateJ2DImageLoader()` | `com.sun.javafx.iio.java2d.J2DImageLoaderFactory` |

   这些条目由脚本程序化生成，而不是手写 —— 手写清单漏项时不会报错，只会在用户机器上炸。

   **但「扫 fat jar 的类清单」这个方法本身有一个致命盲区**（2026-10-10 真机踩到，见本节第 9 条）：
   它只能看到**存在于 jar 里**的类，看不到「**只被 native 代码按名字查找**」的类 ——
   即 openjfx 的 C/C++ 源码里以字面量出现的 `FindClass("java/lang/Runnable")`，
   以及平台子类自己的成员（`GetMethodID(WinWindow, "notifyMoving", …)`）。

   现在改用 [`Script/GenerateNativeImageMetadata.py`](../Script/GenerateNativeImageMetadata.py)：
   以「native 源码扫描 + 跨项目已验证清单」为准，再按产物里真实存在的类裁剪
   （应用 / JavaFX 类看 fat jar，JDK 类看 `jmods`）。当前产出：`reflect-config.json` 408 条、
   `jni-config.json` 136 条。**JavaFX 升级后必须重跑这一步。**

8. **`actions/download-artifact` 是解压，不是给你一个 zip；而 `softprops/action-gh-release`
   匹配不到文件只告警不失败**。两者叠加的后果是：**建出一个空 release，而且整条工作流全绿**。
   本仓库的 `V0.1.1.00002` 就是这么来的，日志原话：

   ```
   🤔 Pattern 'assets/*.zip' does not match any files.
   👩‍🏭 Creating new GitHub release for tag V0.1.1.00002...
   🤔 assets/*.zip does not include a valid file.
   ```

   处置：发布页要的 zip 自己打（`PackageZip`：把 exe 放进一层与 zip 同名的目录后打包，见 `DevelopmentConventions.md` 第 10 条），
   并给 `action-gh-release` 加 **`fail_on_unmatched_files: true`** ——
   「发了一个什么都没有的版本」不应该能无声通过。

9. **反射与 JNI 是两套清单：注册了「类可达」不等于允许原生库回调它**（2026-10-10 真机 segfault）。
   补完 DLL 自包含后，真机跑发布包：工具包、Glass 工厂、Prism 都已起来（比上一轮
   `No toolkit found` 进了好几层），却崩在

   ```
   [ WindowsNativeRunloopThread ] WinApplication._runLoop(Native Method) → segfault
   ```

   `RBP` 是 `[Ljava.lang.Object;`（正在打包参数数组）—— 也就是 native 正在**回调 Java 方法**。
   根因：`jni-config.json` 缺 `java.lang.Runnable` / `java.lang.Object` / `com.sun.glass.ui.Window`
   这类「JNI 可达」类，native 按名字查到空引用后继续跑，落点就是 segfault，而且**构建期毫无征兆**。
   同族问题在 AHA 项目踩过三次（#35 反射层 / #37 JNI 类查找层 / #39 平台子类成员层），
   故本轮直接复用其真机验证过的清单，而不是再打一轮地鼠。
   已加**自证③**（`CollectArtifacts`）：关键 JNI 类缺失、条目重复、类名写错，构建当场失败。

10. **只在 `push` 触发构建 = 把验证推到合并之后**。原 `on:` 只有 `workflow_dispatch` 与
    `push: [main, dev]`，于是 PR #3 的产物问题是**合并后**才暴露的。
    现已加 `pull_request: [main, dev]`，并让 `publish` 作业 `if: github.event_name != 'pull_request'`
    把发布挡在 PR 之外（否则每个 PR 都会打 tag、建 release）。

11. **同一份元数据：GraalVM CE 25.0.2 拒绝、Oracle GraalVM 25.4 接受**（2026-10-10）。
    换新清单后，构建在**分析阶段**得 2 条 fatal（PR #13 合并后 `main` 即如此）：

    ```
    Fatal error: com.oracle.svm.core.util.VMError$HostedError:
      Bulk queries can only be set with 'name' which does not allow run-time conditions.
        at ReflectionDataBuilder.registerAllFieldsQuery(ReflectionDataBuilder.java:563)
        at ReflectionDataBuilder.registerClassMetadata(ReflectionDataBuilder.java:237)
    ```

    - **判定条件可从字节码读出**（`guaranteeNotRuntimeConditionForQueries`）：
      `guarantee(!(flag && !condition.isAlwaysTrue() && condition.isRuntimeChecked()), ...)`
      —— 即「**批量查询**（`allDeclared*`）+ **运行期才能判定的条件**」同时出现才抛，且受宿主选项
      `TreatAllTypeReachableConditionsAsTypeReached` 支配。两份 JSON 里**一个 `condition` /
      `typeReachable` 都没有**（已 grep 确认），说明条件由 builder 内部产生。
    - **本地对照**：本机 GraalVM **CE 25.0.2** + JavaFX **25.0.4** 的 fat jar + **同一份清单**
      → **构建成功**（2m33s，`Finished generating`）。即清单本身不是致命点。
    - **CI 对照**：CE 25.0.2 + JavaFX 27 → fatal；改 `version: '25.4'`（Oracle GraalVM）→ **成功**
      （exe 90.3 MB，`自证③通过`）。
    - **机理未验证（诚实口径）**：25.4 到底是改了上述选项的默认值，还是修了元数据处理路径，
      本轮**没有查到**；本节记录的是**现象与可复现对照**，不是“已查明的原因”。
    - **教训**：`java-version: '25'`（Java 主版本，只解析到 25 系列最新的 **CE**）与
      `version: '25.4'`（GraalVM **发行版本号**）**不是一回事**；且同系列 builder 的不同
      发行版对同一份元数据的态度可能不同 —— 所以这两个入参不得互相“简化”。

12. **清单条数变多 ≠ 覆盖更全：合并语义写错会造成静默的覆盖回归**（2026-10-10 真机）。
    换上 408 条的清单后，CI 五步全过、自证③通过、产物 30 MB，真机却停在：

    ```
    Graphics Device initialization failed for :  d3d, sw
    Error initializing QuantumRenderer: no suitable pipeline found
    ... Exception in thread "main" java.lang.RuntimeException: No toolkit found
    ```

    根因：生成脚本对「同一个类同时出现在已有清单与 AHA 清单」采用了**首个条目生效**
    （`setdefault`），而两侧写法不同 —— 已有清单用 `allDeclared*`（全量），AHA 清单用逐条
    `methods`（精确）。于是**窄条目覆盖了宽条目**：253 个类丢掉全量标志，含
    `D3DPipeline` / `SWPipeline` / `WinPlatformFactory` / `QuantumToolkit` / `DWFactory` ——
    正是第 7 条那张表里「`Class.forName` + 取构造器」的那几个；构造器没登记，取到的就是 null。
    **条数 259 → 408 看上去更全，实际有些类反而变差**。

    处置：
    ① 生成脚本改为**逐类求并集**（标志取或；已有 bulk 标志时丢弃冗余的显式成员），并内置
       **「不得覆盖回归」断言** —— 逐类核对旧文件，发现任何一项丢失就拒绝写出；
    ② `CollectArtifacts` 的自证③ 增加**关键反射点必须登记构造器**的检查；
    ③ 教训：`BuildNativeImage` 全绿只说明**构建**没问题，「跑得起来」必须真机走查 ——
       构建成功不是验收标准。

13. **Windows 上的非 UTF-8 字符集未编进镜像 → 连累原生库加载**（2026-10-10 真机 + 本机复现）。
    症状与第 12 条一模一样（很容易误判成同一个原因）：

    ```
    Graphics Device initialization failed for :  d3d, sw
    Error initializing QuantumRenderer: no suitable pipeline found
    ```

    `-Dprism.verbose=true` 直接把真因打了出来 —— 顺带确认了一件事：
    **运行期 `-D` 在原生镜像里是生效的**，所以这类问题不用重新构建就能查：

    ```
    java.lang.NoClassDefFoundError: Could not initialize class sun.nio.fs.Util
        at .../sun.nio.fs.WindowsFileSystemProvider.getFileAttributeView(WindowsFileSystemProvider.java:164)
        at .../java.nio.file.Files.readAttributes(Files.java:1702)
        at .../jdk.internal.module.ModulePath.scan(ModulePath.java:220)
        ...ResourceBasedModuleReaderSupport.findRuntimeModuleReference(...)
        at .../java.lang.Class.getResource(DynamicHub.java:2672)
        at com.sun.glass.utils.NativeLibLoader.loadLibraryFullPath(NativeLibLoader.java:397)
        at com.sun.glass.utils.NativeLibLoader.loadLibraryInternal(NativeLibLoader.java:127)
        at com.sun.prism.d3d.D3DPipeline.<clinit>(D3DPipeline.java:49)
    ```

    **因果链**（四步，环环相扣）：

    1. `D3DPipeline.<clinit>` 调 `NativeLibLoader.loadLibrary("prism_d3d")`；
    2. 第①条路径 `loadLibraryFullPath()` 先做 `NativeLibLoader.class.getResource(...)`；
       该资源在镜像里没命中 → JDK 回退到**模块扫描**（`BuiltinClassLoader.findMiscResource`）
       → `Files.readAttributes` → `sun.nio.fs.Util`；
    3. 而 `sun.nio.fs.Util` 的静态初始化是
       `private static final Charset jnuEncoding = Charset.forName(StaticProperty.jnuEncoding());`
       —— Windows 上 `sun.jnu.encoding` 是**代码页**（中文 Windows 为 `GBK`，英文为 `Cp1252`），
       而镜像**默认只带少量字符集** → 招 `UnsupportedCharsetException` →
       `NoClassDefFoundError: Could not initialize class sun.nio.fs.Util`；
    4. 这个 `Error` 不是 `UnsatisfiedLinkError`，**逃出了 `loadLibrary` 里只捕获
       `UnsatisfiedLinkError` 的 catch** → 整个 `loadLibrary` 中断 →
       **永远走不到第②条「从资源加载」那条真正可行的路** → 两条管线全废（DLL 其实好端端编在镜像里）。

    **本机复现与验证**（Linux + 本机 GraalVM CE 25.0.2；平台无关的那部分完全一致）：

    | 运行 | `-Dsun.jnu.encoding=GBK` |
    |---|---|
    | 修复前 | 立刻死在 `UnsupportedCharsetException: GBK`（`java.lang.ProcessImpl.<clinit>`），连 JavaFX 都没到 |
    | 修复后（加 `-H:+AddAllCharsets`） | 通过该阶段，正常进入 Prism 管线初始化 |

    **处置**：
    ① pom 的 `native-image` profile 增加 **`-H:+AddAllCharsets`**（本机实测无需
       `-H:+UnlockExperimentalVMOptions`）；
    ② `resource-config.json` 显式纳入 `com/sun/glass/utils/NativeLibLoader\.class` —— 让第 2 步的
       资源查找直接命中，**不再触发那条会读磁盘的模块扫描兜底路径**；
    ③ 工作流新增**运行期冒烟** `SmokeTestCharsets`：用 `-Dsun.jnu.encoding=GBK` 真跑一次 exe，
       输出里出现字符集 / 类初始化失败即失败 —— 这是本项目第一条**运行期**守卫。

    > 两条参数与 AHA 项目的做法一致（其参数文件对 `-H:+AddAllCharsets` 的注解正是
    > 「非 UTF-8 字符集也要带上：Windows 控制台与文件编码的探测依赖它」）。
    > **教训**：同一句报错（`no suitable pipeline found`）背后可能有完全不同的根因，
    > 必须先拿到 `prism.verbose` 的真实异常再动手。

14. **CSS / 属性系统「按名字加载」的类没登记 → 界面刚建就拓**（2026-10-10 真机，字符集修完后的下一层）。
    字符集补上后，真机终于走到了 Prism 起来、D3D 设备建木、GPU 也认出来了：

    ```
    Prism pipeline name = com.sun.prism.d3d.D3DPipeline
    Loading D3D native library ... succeeded.
    D3DPipelineManager: Created D3D9Ex device
    Initialized prism pipeline: com.sun.prism.d3d.D3DPipeline
    D3D Driver Information: Intel(R) UHD Graphics ...
    Exception in Application start method
    Caused by: java.lang.AssertionError: java.lang.ClassNotFoundException: javafx.css.StyleableFloatProperty
        at com.sun.javafx.util.Utils.forceInit(Utils.java:874)
        at com.sun.javafx.css.StyleablePropertyHelper.<clinit>(StyleablePropertyHelper.java:46)
        at javafx.scene.layout.Region.<init>(Region.java:659)
        at io.github.acnxo.legion.fan.FanControlApp.start(FanControlApp.java:101)
    ```

    **机制**：`Utils.forceInit(Class)` 的实现就是

    ```java
    Class.forName(classToInit.getName(), true, classToInit.getClassLoader());
    ```

    于是**只被 forceInit 引用**的类（它不以字面量出现在任何地方）会被封闭世界分析丢掉，
    运行时再按名字找就是 `ClassNotFoundException` —— **构建期毫无征兆**。

    **处置：这次不再一个个补，改全量静态审计。** 扫 JavaFX 各模块源码里的两类判据：
    ① 名字拼接 / 字符串加载（`com.sun.prism.shader.<name>_Loader`、`PPS<name>Peer`）；
    ② `Utils.forceInit(...)` 与 `Class.forName("字面量")` 的目标。把结果做成三份来源写进
    `Script/GenerateNativeImageMetadata.py`（`JAVAFX_NAME_LOADED_FAMILIES` /
    `JAVAFX_FORCE_INIT_TARGETS` / `JAVAFX_CLASS_FOR_NAME`），**整族登记**。

    本次审计出的缺口（其余族已完整）：

    | 类族 | 产内数量 | 审计前未登记 |
    |---|---|---|
    | `com.sun.prism.shader.*_Loader`（着色器） | 212 | 0 |
    | `com.sun.scenario.effect.impl.*Peer`（效果 peer） | 106 | **3**（`EffectPeer` / `JSWEffectPeer` / `SSEEffectPeer`） |
    | `com.sun.scenario.effect.impl.*Delegate` | 3 | **1**（`RendererDelegate`） |
    | `com.sun.prism.*.*Pipeline` / Glass `*PlatformFactory` | 4 / 1 | 0 |
    | **`javafx.css.Styleable*Property`** | **8** | **8（本次崩点）** |

    `reflect-config.json` **408 → 467 条**。生成脚本新增**类族完整性硬校验**（任一族缺成员即退出码 7），
    `CollectArtifacts` 的自证③也加了同款检查（从 fat jar 数出族成员，逐个对照清单）。

    > **教训：「类族」才是审计单元。** 只盯报错里那一个类，就会变成一轮一轮地拓；
    > 按族对齐后一次补齐，且将来 Dependabot 升 JavaFX 时由守卫自动拦下。
    > `XxxHelper` 这类尽管名字像，但它们是被**直接引用**的（不是按名字加载），故不入清单。

15. **exe 没有 Windows 清单 → 被 Windows 判为「DPI 不感知」→ 字体发虚**（2026-10-10 真机）。
    现象：**原生包界面能开，但字发虚、笔画偏细；JVM 模式锐利**（与 AHA 的 issue #48 同族）。

    **① 先把字体侧排除干净** —— 两条 flag 各跑一次，字体侧**逐字相同**：

    ```
    -Dprism.verbose=true -Dprism.debugfonts=true   # 原生包：直接跟在 exe 后
    ```

    | 观测 | 原生包 | JVM 模式 |
    |---|---|---|
    | `Loading FontFactory` | `com.sun.javafx.font.directwrite.DWFactory` | 同 |
    | `Subpixel` | `enabled` | 同 |
    | Locale / 字体枚举 | `2052`，完整（含 simsun / 幼圆 / 雅黑） | 同 |

    即字形枢格化走的是**同一条路、同一个 `javafx_font.dll`**，字体无罪。
    （顺带更正一条流传的推断：JavaFX 25 在 Windows 上**没有静默回退到 `FTFactory`** ——
    `PrismFontFactory.getFontFactory()` 在工厂加载失败时直接
    `throw new InternalError("cannot load font factory: …")`，是响亮报错，不会悄悄换路。）

    **② 真正的差异只有一行** —— 两次启动日志里的 `OS Information:`：

    ```
    原生包  OS Information:  Windows 8.0 build 9200
    JVM    OS Information:  Windows version 10.0 build 26300
    ```

    `Windows 8.0 build 9200` 不是机器版本（本机是 Windows 11 build 26300），而是
    **无清单进程的既定谎报**：Windows 自 8.1 起，对没有清单声明支持系统版本的进程
    统一返回 6.2 / build 9200。`java.exe` 自带清单，所以报真版本。

    **③ 因果**：无清单 → 进程**默认「DPI 不感知」** → 程序按 96 DPI 枢格化字形，
    Windows 再按显示缩放把整窗**位图放大** → 发虚、笔画偏细；同时版本谎报会让
    「Win8.1+ 才用的 DPI 接口」走旧分支。
    （`glass.dll` 确实会调 `SetProcessDPIAware()`——导入符号已实测——但它能否在
    窗口 / D3D 设备建立之前生效取决于初始化时序，JVM 与原生镜像不同，这正是差异的来源。）

    **④ 修法**：仓库根新增 `exe.manifest`（`dpiAware=true` 系统级 + `supportedOS` + `asInvoker`），
    pom 用 `-H:NativeLinkerOption` 交给 MSVC 链接器（清单必须是 UTF-8 **无 BOM**，否则嵌入会失败）：

    ```xml
    <arg>-H:NativeLinkerOption=/MANIFEST:EMBED</arg>
    <arg>-H:NativeLinkerOption=/MANIFESTINPUT:${project.basedir}/exe.manifest</arg>
    ```

    **⑤ 验收断言（一眼可判）**：修好后原生包这行应变成
    `Windows version 10.0 build 26300`（与 JVM 模式一致），字体随之锐利。
    工作流新增**自证④**从 exe 字节里复查 `dpiAware` / `supportedOS`（ASCII 与 UTF-16 都查），
    缺了即构建失败 —— 「产物缺东西但构建全绿」这类问题终于有了构建期守卫。

    > **教训**：Windows 上「程序自己报的系统版本」是一条极便宜、极强的清单判据；
    > 而字体发虚这类现象，**先把字体侧排除干净（两条 flag 即可）再往外看**，
    > 比一上来就调渲染参数靠谱得多。

    **后续实测（2026-10-10 真机）——本条在「字体清晰度」这一层已被证伪**：
    清单嵌上后，原生包日志这行确实变成了 `Windows version 10.0 build 26300`（与 JVM 一致，
    证明清单已被读到），**但字体仍然发虚**。即：缺清单确实存在且已修（属真实缺陷），
    但它**不是**发虚的原因。剩下的排查转入 **issue #20**，方法是先拿数字（`RenderDiagnostics`
    打出的 **LCD 对比度** / `dpi` / `outputScale` / 字形度量），再决定改什么 —— 不猜着改。

16. **tag 被复用 → 既有 release 的资产被静默覆盖**（2026-10-10 发现）。
    现象：`dev` 上连续合了两个 PR，发布列表却**停在同一个 tag**（`V0.2.0.00021`），
    下载到的却是「新构建的内容」—— 也就是说同一 tag 的资产被后一次构建静默替换掉了。
    日志里能直接看到：

    ```
    tag = V0.2.0.00021（prerelease=true）
    ```

    **根因**：`ComposeTag` 用 `gh api repos/<repo>/commits/<sha>/pulls` 反查 PR 编号，
    而在本仓库这条 squash 合并链路上，**它返回的并不一定是引入本次提交的那个 PR**
    （PR #24 合入 `dev` 后，这里返回的是 #21）→ 编号段相同 → tag 相同 → `action-gh-release`
    对已存在的 tag 走「更新 release」而非新建 → 旧资产被换掉。
    危害是**静默**：发布列表、tag 列表都看不出任何异常。

    **处置**（两层）：
    ① **PR 编号改为优先取 squash 提交标题里的 `(#NN)`**（GitHub 自动追加，最可靠），
       拿不到才回退到按提交反查；`head` 分支也改为由「已确定的 PR」反查（同一原因）；
    ② **加防覆盖守卫**：算出的 tag 若已存在，自动降级为 UTC 时间戳段 —— 绝不重用 tag。

    > **教训**：用「可变的远端状态」做唯一标识时必须同时考虑**冲突与重复**。
    > 「同一个 tag 被两次构建写入」这种问题不会报错、不会变色，只能靠主动对账
    > （本次就是核对「合了两个 PR，为什么没有两个 release」发现的）。

17. **离屏快照诊断看不出「屏幕上的」渲染差异**（2026-10-10 真机 + 截图量化）。
    现象：`RenderDiagnostics` 的「字形像素」在**两个构建里逐像素一致** ——
    `V0.2.0.00030`：1.0 倍 `249x19 墨量=225594`、1.5 倍 `373x28 墨量=498320`，两边同值；
    可**屏幕截图**上两者明显不同。

    **根因**：`Node.snapshot` 走的是**离屏路径**，两个构建在离屏路径上都带**灰度抗锯齿**；
    屏幕上走的是另一条路径。两张同界面截图逐像素对齐（OS 标题栏区域差值 **0.000**，证明
    截图本身可比）后，`Script/CompareScreenshots.py` 给出的判据是：

    | 指标（文字边缘像素） | 原生包 | JVM |
    | --- | --- | --- |
    | 中间调像素（80~200，抗锯齿存在的证据） | **0** | 677 |
    | 次像素色边 max(\|R-G\|,\|G-B\|) | **0.00** | 49.15 |
    | 深核像素（<80，笔画实心） | 1222 | 501 |
    | 判定 | **无抗锯齿（纯二值栅格化）** | **ClearType 次像素** |

    **教训**：
    ① **「快照一致」不等于「屏幕一致」** —— 用离屏诊断验收屏幕观感会得出错误结论；
    ② 形如 `墨量 = 非空像素 × 255` 的指标是**退化指标**（不透明背景会把它顶满）：早前那条
       `墨量=1206405` 恰好 `= 4731×255`（249×19 的全部像素），即当时量的是**背景**而非字形，
       那次「两模式一致」的结论因此不成立；改设 `fill=TRANSPARENT` 后才有意义；
    ③ 屏幕差异必须**从截图量**：`Script/CompareScreenshots.py`（纯标准库、自动识别文字
       边缘像素）给出 `AA 比 = 中间调像素 / 深核像素` 与 `色边均值`，原生包
       `0.003 / 0.20`、JVM `0.960 / 44.55`；放大对比图见
       `Docs/Asset/font-antialias-native-vs-jvm.png`（6 倍最近邻）。

    **处置**：`RenderDiagnostics` 增加「**屏幕字形像素**」一栏 —— 用
    `javafx.scene.robot.Robot.getScreenCapture(...)` 抓**窗口在屏幕上的真实像素**，就地算出与
    `CompareScreenshots.py` **完全同口径**的 `深核像素 / 中间调像素 / AA 比 / 色边均值` 与判定。
    两种模式各跑一次即可直接对照，不必再依赖人工截图（截图仍可作交叉验证）。

    > **抓法有个坑（第一版就栽在这）**：不要按逻辑坐标去抓「窗口内一小块」—— `Robot` 对入参
    > 是逻辑还是物理像素并不确定，偏移一个 1.5 倍就整块落空，而且**不报错**（原生与 JVM 会
    > 同时打出「没抓到文字」，极易误判成 native-image 的问题）。正确做法是**先抓整屏**
    > （原点 (0,0) 在两种解释下都指向同一点），用返回图尺寸**反推比例**，再把窗口矩形映射成
    > 像素矩形。输出里的 `均灰度` 就是给这件事兜底的：抓到窗口约 240、空白接近 255。

    > **换算口径也有个坑（第二版又栽了）**：JavaFX 的 `Color#getRed()` 返回的是 **0~1 的 double**，
    > 而 `Script/CompareScreenshots.py` 里读的是 **0~255 的字节** —— 两边写法不能照搬。
    > 真机（`V0.2.0.00032`）曾报出 `均灰度=864.4 深核像素=13 中间调像素=5` —— 值域跑到 0~1000，
    > 数字一眼假，但它**照样给出了一个判定**（「次像素抗锯齿」），很容易被当结论采信。
    > 现已统一走 `luminance()`（0~255），并加**越界自检**：均灰度 > 255 时直接报
    > 「不可用(灰度换算越界)」而不是给假数字 —— 诊断代码自身的错误必须**显式失败**。

    > **还有一个没查清的**：修好换算后，应用内抓屏对「原生（截图测得纯二值）」与
    > 「JVM（截图测得 ClearType，色边≈45）」给出了**几乎相同**的数字（AA 比 0.17 / 0.09、
    > 色边均≈16），且深核像素比同区域的截图**少约 9 倍** —— 说明 `Robot.getScreenCapture`
    > 拿到的**不是屏幕的忠实拷贝**（疑似被重采样）。故又加了**灰度直方图 + 逐像素 ASCII 打印**
    > 用于自证：抓到的到底是自家界面文字还是桌面/空白、字形是硬边还是被抹糊。
    > 在查清之前，**验收以「真实截图 + `CompareScreenshots.py`」为准**。
    >
    > **结论（2026-10-11）**：应用内抓屏**抓到的不是本窗口** —— 直方图显示 **99.7% 的像素挤在
    > 208-223 一个桶、224-255 一个都没有**（本窗口背景是白的，不可能没有亮像素），ASCII 也只
    > 显出右边框、无字形。坐标映射仍有一处没查清，而它已耗掉三轮 ⇒ **该探针已停用**
    > （`FanControlApp` 不再调用，代码保留），**验收一律以「真实截图 + `CompareScreenshots.py`」为准**。
    > 根因追查改走「**字形掩码探针**」（`RenderDiagnostics.probeGlyphMasks`）：直接重放
    > `getLCDMask` 的两步 DWrite 查询，打印**调用输入 / 边界（`null` 调用失败 vs 空矩形）/
    > 掩码性质与掩码 ASCII**，两个构建各跑一次即可判定原因归属。
    >
    > **关键教训（2026-10-11，`V0.2.0.00036` 真机）**：此前所有「两个构建离屏逐像素一致」的结论
    > **都只覆盖灰度路径** —— 诊断里那句 `new Text(SAMPLE)` **从未设置平滑类型**，用的是 JavaFX
    > 默认的 `FontSmoothingType.GRAY`；而真机上出问题的是 **LCD 合成**（modena CSS 给 `Label`
    > 设了 `-fx-font-smoothing-type: lcd`）。同一轮真机**字形掩码探针**进一步证明：原生镜像里
    > DWrite 的 `CLEARTYPE_3x1` 掩码**完全正常**（7 档灰度、`R=G=B 占比` 仅 37.1%，与 JVM
    > **逐字节相同**）⇒ 先前「原生取掩码失败」的结论**作废**。
    > 现新增 **LCD 合成探针**（`RenderDiagnostics.logLcdComposition`）：强制 `FontSmoothingType.LCD`
    > 离屏渲染 `{GRAY, LCD} × {白底, 透明底}` 以及一个走 CSS 的 `Label`，量化
    > 「深核 / 中间调 / 墨量 / 色边像素 / 最大通道差」—— 目标是**在离屏复现**该差异，
    > 不再依赖屏幕截图，并为后续二分提供可复现的最小场景。

    抓屏幕需要 `com.sun.glass.ui.GlassRobot` / `WinRobot` 在 `jni-config.json` 里 ——
    已补登并加入工作流自证③。

    **处置（2026-10-11，issue #48）**：原生包**默认改走灰度文本平滑**。
    `NativeImageRenderDefaults.applyIfNativeImage()` 在 `Launcher.main` 与 `FanControlApp.main`
    的**第一条语句**执行：仅当 `org.graalvm.nativeimage.imagecode != null`（确在原生镜像内）
    **且**用户未显式设置 `prism.lcdtext` **且**总开关未关时，才写入 `prism.lcdtext=false`。

    > **定位：这是临时缓解措施（workaround），不是最终方案** —— 代码级根因**尚未定位**，
    > 它只是绕开退化的 LCD 路径。上游（GraalVM / OpenJFX）修复后应**删除本类与两处调用**，
    > 回到 JavaFX 默认行为。

    完整证据链、已限定的代码范围与**上游修复进展**（含上游 issue 状态与待办）见
    [TS-2610-001 故障档案与修复跟踪](TroubleShooting/TS-2610-001-LCD-Subpixel-Text-Composition-Native-Image-Fix-And-Upstream-Tracking.md)。

    > **时机是硬约束**：`lcdEnabled` 只在 `PrismFontFactory` 静态初始化时读取一次，
    > 晚于工具包启动再设即失效。pom 已把 `com.sun.javafx.font` / `com.sun.prism` 设为
    > `--initialize-at-run-time`，所以运行期写入的属性会被真正读取 ——
    > 若这两包改为构建期初始化，本方案会**静默失效**。

    **开关与回退（都不需要改代码）**：

    | 命令行参数（交给 exe） | 效果 |
    |---|---|
    | `-Dprism.lcdtext=true` | 用户直接决定，程序不覆盖（恢复 LCD 次像素） |
    | `-Dlegion.render.nativeTextGreyscale=false` | 关闭本缓解措施本身（回到 JavaFX 默认） |

    永久回退：删掉 `Launcher.main` / `FanControlApp.main` 里的 `applyIfNativeImage()` 两行，
    或对合入本改动的提交执行 `git revert`。

    **验收**：启动日志出现 `渲染诊断：属性 … prism.lcdtext=false` 与
    `渲染诊断：原生文本灰度默认 …`（后者直接写明本次是「已写入」还是「跳过」及原因）；
    原生 exe 与 JVM 版的 `Script/CompareScreenshots.py` 判据（AA 比 / 色边均值）
    不再出现断层；显式传 `-Dprism.lcdtext=true` 时仍走 LCD。

18. **原生包“先弹黑框再出界面”**（2026-10-12）。
    现象：双击或右键「以管理员身份运行」`LegionFanControl.exe` 时，先出现一个控制台窗口（日志打在那里），界面才出来。

    **根因**：native-image 默认按 **控制台子系统**（`/SUBSYSTEM:CONSOLE`）链接，Windows 在进程启动时就会为它创建控制台窗口。
    首次实现用「启动后再隐藏」（`GetConsoleWindow` + `GetConsoleProcessList` + `ShowWindow`）在真机上**未达预期**：
    提权启动后黑框依旧，且即使判定成功也有一闪 —— 事后补救本质上受时序与判定条件摆布。

    **处置**：改为 **GUI 子系统** 链接，**直接不创建**控制台窗口：

    ```xml
    <arg>-H:NativeLinkerOption=/SUBSYSTEM:WINDOWS</arg>
    <arg>-H:NativeLinkerOption=/ENTRY:mainCRTStartup</arg>
    ```

    > 为何必须写 `/ENTRY:mainCRTStartup`：镜像的 C 入口是 `main`，而 `/SUBSYSTEM:WINDOWS` 默认去找 `WinMain`，
    > 不指定入口点会直接链接失败。
    >
    > 副作用（已知、可接受）：从命令行启动时 cmd 不再“等”这个 GUI 程序结束（需要等待用 `start /wait`）；
    > stdout 句柄仍由启动者提供，所以 `渲染诊断：…` 照旧打在那条命令行窗口里。CI 的 `SmokeTestCharsets` 用
    > `Start-Process -RedirectStandardOutput`，重定向句柄与子系统无关，照旧生效。
    >
    > 日志诉求由开关承担：`--console` / `-Dlegion.console=true` ⇒ 先 `AttachConsole`（接管父控制台，
    > 适用于从命令行启动），否则 `AllocConsole`（新开一个），再用 `CONOUT$` 把 `System.out` / `System.err`
    > 重定向进去。该路径失败只记状态 —— **不影响默认的“无控制台”行为，因为它是链接期定下的，不依赖任何运行时调用**。

19. **原生 exe 的程序图标（2026-10-12）**。
    目标：exe 在资源管理器 / 任务栏 / 快捷方式里不再是默认白纸图标，应用窗口也用同一张图。

    | 位置 | 做什么 | 怎么做 |
    |---|---|---|
    | 版本库 | 存图标源 | `exe.ico`（单条 200×200 **PNG-in-ICO**：6+16 字节 ICO 头 + 原始 PNG 载荷）与 `exe.rc`（**纯 ASCII**，`IDI_APP_ICON ICON "exe.ico"`）入仓库根；`exe.res` 不入库（构建产物，见 `.gitignore`） |
    | CI（`CompileAppIconResource`） | 编译资源 | `rc.exe /nologo /fo exe.res exe.rc` —— `rc.exe` 随 Windows SDK 提供，`InitMsvcToolchain`（ilammy/msvc-dev-cmd）注入 VS 环境后即在 PATH；失败即报错退出 |
    | pom（native-image profile） | 嵌入 exe | `-H:NativeLinkerOption=${project.basedir}/exe.res` —— `.res` 作为普通链接输入交给 MSVC 链接器 |
    | 运行期（`FanControlApp`） | 窗口 / 任务栏图标 | `stage.getIcons().add(new Image(getResourceAsStream("/Asset/Logo.png")))`；`resource-config.json` 必须加 `Asset/.*\\.png$`，否则原生镜像里取不到该资源 |
    | CI 自证（`CheckExeIcon`） | 确认“真嵌进去了” | 在 exe 字节里找 `exe.ico` 载荷开头 32 字节的签名（资源节按原样存放，命中即证明已嵌入）—— “rc 编译成功”不等于“链接进了 exe” |

    为何 `rc` 这一步不做成“宽容”：pom 的链接参数是**无条件**引用的 —— `exe.res` 缺失会直接让链接失败；
    与其让下游报一句难懂的 link error，不如在 `CompileAppIconResource` 里给出可照做的提示。

    为何 `exe.res` 必须写在仓库根（不能放 `target/`）：构建命令是 `mvn ... clean package`，`clean` 只清 `target/`，
    放进里面会在链接前就被删掉。

    为何 `exe.rc` 用英文注释：`rc.exe` 对非 ASCII 源文件的代码页处理依赖环境，纯 ASCII 最稳（本仓库其余文件仍用中文注释）。

    图标的用途与替换步骤见 `Docs/Asset/README.md`。

---

## 6. 排查手册

| 现象 | 先看哪里 | 常见原因 |
|---|---|---|
| `BuildNativeImage` 失败、日志有链接错误 | `PrintEnvironmentInfo` 输出 | `cl.exe` 不在 `PATH`（MSVC 步骤失效）、Windows SDK 缺失 |
| 构建成功但找不到 `target/LegionFanControl.exe` | `BuildNativeImage` 步末 WARNING | profile 没生效（漏 `-Pnative-image`）、插件输出被改动 |
| `CollectArtifacts` 报「未找到 exe」 | 上一步的完整日志 | 原生编译实际失败，但退出码被吞 |
| `CollectArtifacts` 报「未提取到任何 DLL」 | fat jar 内容（`jar tf`） | JavaFX 依赖分类器不对（应为 `-win`）、shade 配置变动 |
| `CollectArtifacts` 报「exe 内未找到嵌入的原生库名」 | `resource-config.json` 的 `.*\.dll$` 规则 | 资源规则被删/被改，DLL 没进镜像 |
| `CollectArtifacts` 报「缺少 JNI 可达类」/「有重复条目」/「含不存在的包名」 | 两份元数据清单 | 自证③拦下；重跑 `Script/GenerateNativeImageMetadata.py` 生成 |
| 运行 exe **无任何 Java 异常直接 segfault**（线程 `WindowsNativeRunloopThread`，栈顶 `WinApplication._runLoop`） | `jni-config.json` | 缺 JNI 可达类 —— 构建期无征兆，见第 5 节第 9 条 |
| 窗口能开但控件画不出 / 报 `Could not create peer …` | `resource-config.json` 的 `com/sun/scenario/.*` 规则 | D3D 效果着色器（`.obj`）未进镜像，属**静默坏掉** |
| 改了元数据 / 参数，想先验证再合 | PR 页的 Checks | 已挂 `pull_request` 触发（发布作业用 `if` 挡在 PR 之外） |
| 运行 exe 报 `no suitable pipeline found` / `Graphics Device initialization failed for : d3d, sw` | 先跑 `LegionFanControl.exe -Dprism.verbose=true` 看真实异常 | ① 镜像缺字符集（`sun.nio.fs.Util` 初始化失败 → `NativeLibLoader` 第①条路径中断，**第 5 节第 13 条**）；② 管线类未登记反射（第 12 条）；③ 原生库未编进镜像 |
| 运行 exe 报 `Failed to load Glass factory class` | `reflect-config.json` | `WinPlatformFactory` 未登记 |
| 运行 exe 报 `cannot load font factory` | `reflect-config.json` | `com.sun.javafx.font.directwrite.DWFactory` 未登记 |
| 原生包界面能开但**字体发虚 / 笔画偏细**（JVM 模式锐利） | ① 先看启动日志的 `OS Information:` 行；② 再看 `渲染诊断：` 各行（`RenderDiagnostics`；重点看 **`屏幕字形像素`** 一栏的 `AA 比 / 色边均值`，它量的是**屏幕真实像素**） | ① 打的是 `Windows 8.0 build 9200` → exe 缺 Windows 清单（第 5 节第 15 条）——**但该条已实测证伪其在清晰度上的作用**；② 清单正常仍发虚 → 对照两模式的 **LCD 对比度 / dpi / outputScale / 字形度量**，见 issue #20 |
| 运行 exe 报 `ClassNotFoundException: com.sun.prism.shader.*_Loader` | 同上 | 着色器 loader 清单缺失（JavaFX 升级后需重生成） |
| `ReadProjectVersion` 报解析失败 | `pom.xml` 顶层 `<version>` | 版本改由 `<parent>` 或属性提供，需同步改读取方式 |
| `publish` 报权限不足 | 工作流的 `permissions` | `contents: write` 被删，或仓库策略收紧 |
| Release 建出来了但**没有资产** | `PublishRelease` 步骤日志里的 `does not match any files` | 文件通配没匹配上（现已开 `fail_on_unmatched_files`，正常情况下不会再复现） |
| tag 出现时间戳段而非 PR 号 | `ComposeTag` 的 `::warning::` 与 step summary | 反查不到 PR（直接 push / API 限流），属**预期**降级 |
| 发布包在本机跑不起来 | 解压方式 / 管理员权限 | 未以管理员身份运行（联想 WMI 会拒绝访问）；或上表中的原生库/元数据问题 |

---

## 7. 与其它文档的关系

- 构建机制（`Dist/` 自动导出、产物名固定、jar 打包） → [项目结构](ProjectStructure.md)；
- 硬性约定（`module-info` 排除、CRLF 行尾、产物名不带版本号等） → [开发约定](DevelopmentConventions.md)；
- 提权行为在 exe 形态下的降级 → [技术原理](TechnicalPrinciples.md)；
- 原生镜像的能力边界与后续迭代 → [已知限制与路线图](LimitationsAndRoadmap.md)（T4）。
