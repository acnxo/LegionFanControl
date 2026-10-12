# TS-2610-002 · `.cmd` 退化成 LF 行尾导致 cmd.exe 行读取位置漂移（修复与预防）

| 项目 | 内容 |
| --- | --- |
| 编号 | TS-2610-002 |
| 标题 | `.cmd` 退化成 LF 行尾导致 cmd.exe **行读取位置漂移**（ParsingDrift）——修复与预防 |
| 类型 | 交付物缺陷（脚本行尾 / 编码约定）+ 工程约定缺失 |
| 首次发现 | 2026-10-12（由本仓库行尾取证确认，非估计值） |
| 影响范围 | `Dist/Bootstrap.cmd`（JVM 版双击入口，**会进 JVM 发布包**）+ 4 个提权探针脚本 |
| 当前状态 | **已修复 + 已机器强制**（`.gitattributes` + CI 自证）；真机复现细节待现场记录补全 |
| 修复提交 | 见 PR：「fix(scripts): `.cmd` 行尾漂移修复 + `.gitattributes` + CI 自证」 |
| 相关文档 | `Docs/DevelopmentConventions.md` 第 8 条；仓库根 `.gitattributes`；`.github/workflows/main.BuildReleaseWindowsX64.yml`（`CheckScriptLineEndings`） |

---

## 1. 现象

含中文的批处理文件若以 **LF** 行尾保存，用 cmd.exe 执行时会发生**行读取位置漂移**：中文行被字节级切错，后续行被当作命令交给解析器，控制台喷出碎片报错，脚本行为异常（提权、回跳、暂停等步骤可能错位或跳过）。

典型表现（本项目约定文档早已记录）：

```
'xxx' is not recognized as an internal or external command,
operable program or batch file.
```

本档案的触发点是一个非常直接的事实：**仓库自己早就写明了这条要求，但文件本身就是违规的**。

`Dist/Bootstrap.cmd` 第 20–22 行原文：

```
rem  编码约定（重要）：本文件必须保存为 CRLF 行尾 + UTF-8 编码。
rem        若被编辑器改成 LF 行尾，cmd 解析含中文的行时会发生字节错位，
rem        导致脚本行为异常。
```

而该文件在仓库里的实际状态是 **LF-only**（第 4 节给出字节级证据）。

---

## 2. 影响范围

### 2.1 违规文件（本次修复对象）

| 文件 | 定位 | 行尾（修复前） | 是否会随发布包分发 |
| --- | --- | --- | --- |
| `Dist/Bootstrap.cmd` | **JVM 版双击入口**（含大量中文；负责校验 jar、校验 java、UAC 自我提权、启动程序） | LF-only（CR=0，LF=112） | **会** —— `PackageJvmZip` 把整个 `Dist/` 打进 `…-jvm-windows-x64.zip` |
| `Script/RunProbeElevated.cmd` | 提权探针入口（英文注释） | LF-only（CR=0，LF=6） | 否 |
| `Script/RunProbe2Elevated.cmd` | 提权探针入口（英文注释） | LF-only（CR=0，LF=6） | 否 |
| `Script/RunProbe3Elevated.cmd` | 提权探针入口（**含中文**） | LF-only（CR=0，LF=8） | 否 |
| `Script/RunShapeProbeElevated.cmd` | 形状探针入口（**含中文**） | LF-only（CR=0，LF=8） | 否 |

### 2.2 合规对照（同一目录、同一约定）

| 文件 | 行尾 | 说明 |
| --- | --- | --- |
| `Dist/UpdateNative.cmd` | CRLF（CR=73，LF=73） | 一键升级脚本，合规 |
| `Dist/UpdateJvm.cmd` | CRLF（CR=77，LF=77） | 一键升级脚本，合规 |

`Dist/` 里三个 `.cmd` 只有 `Bootstrap.cmd` 违规 —— 而它恰好是**唯一一个用户双击就必须成功**的那个（提权走不通，程序就取不到风扇转速与温度）。

### 2.3 不受影响

- `.ps1`：全部为 LF，符合既有约定；
- Java 源码、Markdown、工作流 YAML：与本缺陷无关。

---

## 3. 结论分层

| 层次 | 结论 | 依据 |
| --- | --- | --- |
| 已观测事实 | 5 个 `.cmd` 在仓库索引里是 **LF-only**；`Dist/` 另两个 `.cmd` 是 CRLF；仓库**没有** `.gitattributes` | 第 4 节字节级取证 |
| 机制（项目已实测） | 根因是 **LF**，与编码无关：cmd.exe 按系统代码页逐字节读取批处理，UTF-8 中文（3 字节）在按「双字节/单字节」切分时对不上，导致行读取位置漂移 | `Docs/DevelopmentConventions.md` 第 8 条的对照表（4 组合实测） |
| 工程层面 | 真正的缺口不是「某个文件被改坏」，而是**约定只写在文件注释与文档里，没有任何机器约束** | 第 7、9 节 |
| 未在本机复现 | 具体到「哪一行先炸、报什么碎片」的现场输出 —— 本机是 WSL，无 cmd.exe 环境 | 第 10 节列为待补 |

> 本档案不把「机制」写成「已在本机复现」：机制来自项目既有的 4 组合实测（第 8 节引用），
> 而本次新增的是**仓库状态取证**与**机器强制手段**。

---

## 4. 观测数据

### 4.1 修复前的索引（blob）字节与行尾

命令：`git show HEAD:<文件> | tr -cd '\r' | wc -c`（CR 数）与 `tr -cd '\n' | wc -c`（LF 数）。

| 文件 | blob CR | blob LF | `git ls-files --eol` | 判定 |
| --- | --- | --- | --- | --- |
| `Dist/Bootstrap.cmd` | 0 | 112 | `i/lf w/lf attr/` | **LF-only ✗** |
| `Script/RunProbe2Elevated.cmd` | 0 | 6 | `i/lf w/lf attr/` | **LF-only ✗** |
| `Script/RunProbe3Elevated.cmd` | 0 | 8 | `i/lf w/lf attr/` | **LF-only ✗** |
| `Script/RunProbeElevated.cmd` | 0 | 6 | `i/lf w/lf attr/` | **LF-only ✗** |
| `Script/RunShapeProbeElevated.cmd` | 0 | 8 | `i/lf w/lf attr/` | **LF-only ✗** |
| `Dist/UpdateJvm.cmd` | 77 | 77 | `i/crlf w/crlf attr/` | CRLF ✓ |
| `Dist/UpdateNative.cmd` | 73 | 73 | `i/crlf w/crlf attr/` | CRLF ✓ |

其中 `attr/` 一列为空 ⇒ **没有任何属性在管行尾**。

### 4.2 `Dist/Bootstrap.cmd` 的编码画像

| 指标 | 值 | 含义 |
| --- | --- | --- |
| 字节数 | 5318 | — |
| 裸 LF 个数 | 112 | 全部换行都是裸 LF |
| CRLF 个数 | 0 | 一个都没有 |
| UTF-8 BOM | 否 | 排除 BOM 干扰（BOM 是另一类 `@echo off` 失效原因） |
| 纯 ASCII | **否** | 文件内含中文 ⇒ 正是「含中文的行」会漂移的场景 |

### 4.3 仓库缺少行尾约束

| 检查项 | 修复前 | 修复后 |
| --- | --- | --- |
| 仓库根 `.gitattributes` | **不存在** | 存在（`.cmd`/`.bat` ⇒ CRLF，`.ps1` ⇒ LF） |
| CI 是否检查行尾 | 无 | `CheckScriptLineEndings`（构建作业） |

### 4.4 修复后的索引状态（关键：索引存 LF、签出还原 CRLF）

```
i/lf  w/crlf  attr/text eol=crlf    Dist/Bootstrap.cmd
i/lf  w/crlf  attr/text eol=crlf    Dist/UpdateJvm.cmd
i/lf  w/crlf  attr/text eol=crlf    Dist/UpdateNative.cmd
i/lf  w/crlf  attr/text eol=crlf    Script/RunProbe2Elevated.cmd
i/lf  w/crlf  attr/text eol=crlf    Script/RunProbe3Elevated.cmd
i/lf  w/crlf  attr/text eol=crlf    Script/RunProbeElevated.cmd
i/lf  w/crlf  attr/text eol=crlf    Script/RunShapeProbeElevated.cmd
```

`i/lf`（索引里是 LF）是**正常且期望**的状态 —— 行尾由属性在**签出时**还原，因此任何平台、任何 `core.autocrlf` 配置下拿到的都是 CRLF。

---

## 5. 定位过程

1. **起点**：`Dist/Bootstrap.cmd` 的注释明确要求 CRLF，而发布包里它却是坏的 ⇒ 先确认「注释要求的」与「仓库里实际的」是否一致。
2. **逐文件字节取证**：不用编辑器看（编辑器会隐藏行尾差异），直接统计 CR / LF 数量与 `git ls-files --eol`（4.1 节）。
3. **确认索引与工作区一致**：`i/lf w/lf` ⇒ 不是「本地被改坏、仓库是好的」，而是**仓库里存的就是 LF**，任何人签出都是坏的。
4. **确认影响路径**：`PackageJvmZip` 的打包源是 `Dist/`（工作区），所以坏行尾会**原样进入发布 zip**。
5. **排除编码**：检查 BOM（无）与纯 ASCII（含中文 ⇒ 触发条件成立），并对照 `DevelopmentConventions.md` 第 8 条的 4 组合实测 ⇒ **根因是 LF，与 UTF-8/GBK 无关**。
6. **查兜底是否存在**：`.gitattributes` 不存在、CI 无行尾检查 ⇒ 结论是「约定无机器保障」，于是本轮同时补上（第 7 节）。

---

## 6. 排除项

| 排除项 | 依据 |
| --- | --- |
| 不是编码问题（UTF-8 vs GBK） | 第 8 节 4 组合对照：LF+UTF-8 与 LF+GBK 均失败，CRLF+UTF-8 与 CRLF+GBK 均正常 ⇒ 变量是 LF |
| 不是 BOM 引起的 `@echo off` 失效 | 实测 `Dist/Bootstrap.cmd` 前 3 字节不是 `EF BB BF`；另在 CI 里新增 BOM 检查防复发 |
| 不是「只有本地工作区被改坏」 | 索引里就是 LF（`i/lf`）⇒ 与本地编辑器无关 |
| 不是 `.cmd` 内容写错 | 文件内容未改一个字节；本次只改「签出/打包时的行尾」与「属性」 |
| 不是 `.ps1` 的问题 | 全部 `.ps1` 均为 LF，符合约定 |
| 不是 PowerShell 脚本的换行问题 | PowerShell 对 CR/LF 均可解析；本缺陷专指 cmd.exe 读取批处理 |
| 与另两个 `.cmd` 无关 | `Dist/UpdateJvm.cmd` / `Dist/UpdateNative.cmd` 是 CRLF，合规 |

---

## 7. 修复

### 7.1 仓库根新增 `.gitattributes`（**根因修复**）

```gitattributes
*.cmd text eol=crlf
*.bat text eol=crlf
*.ps1 text eol=lf
```

为什么写 `eol=crlf` 而不是依赖 `core.autocrlf`：

| 做法 | 效果 |
| --- | --- |
| 只靠 `core.autocrlf` | 每个开发者的本地配置不同、CI 机器与 Windows 机器的设置也可能不同 ⇒ **同一份提交在不同机器上得到不同字节** |
| **`text eol=crlf`** | 强制所有平台、所有 `core.autocrlf` 配置下签出都是 CRLF ⇒ 发布包里必然是 CRLF |

### 7.2 归一化索引（blob）

```bash
git add --renormalize -- '*.cmd' '*.ps1'
```

归一化让 7 个 `.cmd` 的 blob 统一为 LF（含原先为 CRLF 的那两个），签出时由属性还原 CRLF。
**索引里存 LF 是正确状态**，不要用「blob 是 LF」去判断发布包是否有问题。

### 7.3 工作区行尾修正

把 5 个 LF-only 的 `.cmd` 就地转成 CRLF（按字节替换，不重编码、不改内容）：

| 文件 | 转换前 | 转换后 |
| --- | --- | --- |
| `Dist/Bootstrap.cmd` | LF=112，CR=0 | CRLF=112 |
| `Script/RunProbeElevated.cmd` | LF=6，CR=0 | CRLF=6 |
| `Script/RunProbe2Elevated.cmd` | LF=6，CR=0 | CRLF=6 |
| `Script/RunProbe3Elevated.cmd` | LF=8，CR=0 | CRLF=8 |
| `Script/RunShapeProbeElevated.cmd` | LF=8，CR=0 | CRLF=8 |

### 7.4 CI 自证 `CheckScriptLineEndings`（**防复发**）

位置：构建作业，紧跟 `CheckExeSubsystem`。它检查的正是**会被打进 `Dist/` 发布 zip 的那份工作区文件**：

| 检查 | 对象 | 判定 |
| --- | --- | --- |
| 无裸 LF（每个 `0x0A` 前必须是 `0x0D`） | 全部 `.cmd` / `.bat` | 违反即失败 |
| 无 UTF-8 BOM | 全部 `.cmd` / `.bat` | 违反即失败 |
| 无 CR | 全部 `.ps1` | 违反即失败 |
| 工作区行尾 `w/crlf` + 属性 `eol=crlf` | 全部 `.cmd` / `.bat` | 违反即失败 |
| 工作区行尾 `w/lf` | 文本类 `.ps1` | 违反即失败 |

> 为什么要「查字节」+「查属性」两遍：属性保证**将来**在任何机器上签出都正确；
> 字节检查保证**这一次**进包的确实是 CRLF。两者缺一都可能被绕过。

---

## 8. 验证

### 8.1 本地：从索引导出的字节（提交前即可验证）

命令：`git checkout-index -a --prefix=/tmp/idx/`（按属性还原行尾），再统计 CR/LF。

| 文件 | 导出后 CR | 导出后 LF | 结果 |
| --- | --- | --- | --- |
| `Dist/Bootstrap.cmd` | 112 | 112 | **CRLF ✓** |
| `Script/RunProbe3Elevated.cmd` | 8 | 8 | **CRLF ✓** |
| `Dist/UpdateNative.cmd` | 73 | 73 | CRLF ✓ |
| `Dist/UpdateJvm.cmd` | 77 | 77 | CRLF ✓ |

这是本档案的**决定性证据**：修复前同一命门导出的是 LF，修复后导出的是 CRLF，
而且与本地 git 配置无关。

### 8.2 CI

- `CheckScriptLineEndings`：本 PR 的构建作业即会执行（构建作业在 PR 上照常运行）；
- `PackageJvmZip`：打包源就是被检查过的 `Dist/` 工作区 ⇒ JVM 包内 `Bootstrap.cmd` 为 CRLF。

### 8.3 真机（待确认）

| 项 | 期望 |
| --- | --- |
| 解压最新 JVM 包，双击 `Bootstrap.cmd` | UAC 弹窗 → 提权 → 程序启动，控制台无碎片报错 |
| 非管理员终端直接运行 `Bootstrap.cmd` | 正常发起提权，退出码 0 |
| 4 个提权探针（`Script/Run*Elevated.cmd`） | 正常拉起对应 `.ps1`，中文提示正常显示 |

### 8.4 已下载的旧包如何就地修

本修复只对**修复之后构建出来的包**生效；已经下载的旧包里 `Bootstrap.cmd` 仍是 LF。

推荐做法：直接用 `UpdateJvm.cmd` 拉一份修复后的新包（或重新下载）。若确实要就地修，在该目录里开一个
**PowerShell 窗口**执行下面三行（注意：不要在 cmd 里手打，避免引号转义出错）：

```powershell
$p = 'Bootstrap.cmd'
$t = [IO.File]::ReadAllText($p) -replace "(?<!`r)`n", "`r`n"
[IO.File]::WriteAllText($p, $t)
```

校验（两个数字应相等；前者是 CR 数，后者是 LF 数）：

```powershell
$b = [IO.File]::ReadAllBytes('Bootstrap.cmd')
($b | Where-Object { $_ -eq 13 }).Count
($b | Where-Object { $_ -eq 10 }).Count
```

---

## 9. 预防（改 `.cmd` 的规矩）

| 场景 | 做法 |
| --- | --- |
| 用编辑器改 `.cmd` | 明确设成 CRLF + UTF-8（无 BOM）；VS Code 右下角可切换 |
| 用 `sed` / `awk` 批量改写 | 之后必须补回 CR，并**立刻**复查：`tr -cd '\r' < 文件` 的计数应等于 LF 数（`wc -c`） |
| 统一批量转换 | `git add --renormalize -- '*.cmd'` + 确认 `git ls-files --eol` 显示 `w/crlf attr/text eol=crlf` |
| 新建 `.cmd` | 不要复制粘贴「看起来一样」的文本 —— 直接写文件并确认行尾 |
| 提交前 | 依赖 CI 自证 `CheckScriptLineEndings` 兜底；它失败说明行尾或属性被破坏 |
| 判断发布包是否有问题 | 看签出/打包的工作区字节（`git checkout-index` / 实际解压），**不要**看 `git show HEAD:文件` |

---

## 10. 未决问题

| 编号 | 问题 | 现状 |
| --- | --- | --- |
| Q1 | 真机现场的现象细节（哪一行先炸、完整碎片报错原文、是否与 UAC 环节相关） | 待以现场记录补全；机制已由第 8 节的 4 组合实测确定 |
| Q2 | 是否给复现仓库 `acnxo/LCDSubpixelMinimalReproduction` 与 `acnxo/LegionFanControl` 也加同样的 `.gitattributes` | 待定（本档案只覆盖主仓库） |
| Q3 | 是否把行尾检查也用于 `LICENSE`、`Script/Evidence/*` 等历史上行尾不一致的文件 | 待定（这些文件的 CRLF/LF 差异长期存在且刻意不提交） |

---

## 11. 相关文档

| 文档 / 文件 | 关系 |
| --- | --- |
| `Docs/DevelopmentConventions.md` 第 8 条 | 本缺陷的约定与 4 组合实测来源；已补充「机器强制」小节 |
| `.gitattributes`（仓库根） | 本修复的核心：行尾由属性强制 |
| `.github/workflows/main.BuildReleaseWindowsX64.yml` | 新增 `CheckScriptLineEndings`（自证⑥） |
| `Docs/TroubleShooting/TS-2610-001-LCD-Subpixel-Text-Composition-Native-Image-Fix-And-Upstream-Tracking.md` | 同目录的另一份故障档案（原生包文本渲染退化） |
| `Docs/DevSpec/TroubleShootingDocSpec.md` | 本目录的命名与格式规范（本档案即按此规范命名） |
| `Dist/Bootstrap.cmd` | 受影响文件，其文件头注释即本约定的原始出处 |
