# 开发约定

> 返回 [项目主页](../README.md) · 相关文档：[技术原理](TechnicalPrinciples.md) · [项目结构](ProjectStructure.md)

以下约定全部来自实际踩坑，**改动代码时务必遵守**。

---

## 1. 主类不得继承 `javafx.application.Application`

若把继承 `Application` 的类直接作为 `Main-Class`，Java 启动器会强制走模块化 JavaFX 检查，非模块化 fat jar 将报「缺少 JavaFX 运行时组件」。

**做法**：入口必须是普通类（当前为 `Launcher`）再转发到 `FanControlApp.main(args)`。

---

## 2. shade 时必须排除 `module-info.class`，且要带上 multi-release 副本

仅写裸路径 `module-info.class` 会漏掉 `META-INF/versions/*/module-info.class`，导致启动失败。当前 pom 的正确写法：

```xml
<excludes>
    <exclude>module-info.class</exclude>
    <exclude>META-INF/versions/*/module-info.class</exclude>
    <exclude>META-INF/*.SF</exclude>
    <exclude>META-INF/*.DSA</exclude>
    <exclude>META-INF/*.RSA</exclude>
</excludes>
```

---

## 3. XML（pom.xml）注释中禁止出现双横线 `--`

会导致 POM 解析失败。描述命令行参数时写成「java（空格）enable-native-access」。

---

## 4. 任何拼接生成的 PowerShell 脚本，交付前必须过语法校验 + 导出内容核对

用 `Parser::ParseInput` 做纯解析（不执行、不需要管理员权限），并用反射导出实际字符串肉眼确认。

完整校验流程（以下命令在项目根目录执行）：

```bash
# 1. 编译 Probe 工具（首次或源码变更后）
javac -encoding UTF-8 -cp "target/classes" -d Probe/Java Probe/Java/*.java

# 2. 反射导出运行时实际生成的脚本
java -cp "target/classes;Probe/Java" DumpScript     # 产出 snapshot-script.ps1
```

```powershell
# 3. 对导出的脚本做纯语法解析
$e = $null
[System.Management.Automation.Language.Parser]::ParseInput(
    (Get-Content -Raw snapshot-script.ps1), [ref]$null, [ref]$e) | Out-Null
if ($e.Count -eq 0) { 'PARSE OK' } else { $e | ForEach-Object { $_.Message } }
```

> 注意：`Script/ValidateScripts.ps1` 是更早一轮的校验脚本，覆盖的是**重写前的脚本集合**（CIM GAMEZONE / FAN_METHOD / WMI v1 三条转速通路），可作为该技术的用法示例运行，但**不覆盖**当前的 CapabilityID 脚本与批量快照脚本。当前脚本的校验以 `DumpScript` 导出 + `ParseInput` 为准。

---

## 5. 基准 / 测试脚本绝不能静默吞异常

曾因此产出「0 ms」这类假数据 —— 根因是反射调用**实例方法**时传了 `null`（`invoke(null, ...)`）抛 NPE，被 `catch (Exception ignored)` 吞掉，输出稳定但完全虚假的 0 毫秒。

**做法**：基准脚本的 catch 块至少要打印异常类型与消息；能断言的地方加断言。

---

## 6. 判断进程启动成功，必须同时核对「日志无报错」+「PID 匹配」

只看 `tasklist` 里有没有 `java.exe` 会把**残留进程**误判为启动成功 —— 这个坑真实发生过：启动测试显示「进程存活 14 秒」，实际是上一个 Maven 进程残留，程序本体早已因「缺少 JavaFX 运行时组件」崩溃。

---

## 7. Java 公开 API 注释使用简体中文

技术标识符、方法名、API 名保留英文。

---

## 8. Windows 批处理文件（`.cmd`）必须使用 CRLF 行尾

本项目的 `.cmd` 含中文内容。若用 LF 行尾保存，cmd.exe 会按系统代码页逐字节读取文件，UTF-8 中文（3 字节）被当作 1.5 个双字节字符，导致**行读取位置漂移**，后续行被错误切分并当作命令执行。

现象：控制台喷出 `'xxx' is not recognized as an internal or external command` 之类的碎片报错。

已实测确认的对照：

| 组合 | 结果 |
|---|---|
| LF + UTF-8 | ✗ 失败 |
| LF + GBK | ✗ 失败 |
| **CRLF + UTF-8** | **✓ 成功（本机代码页 65001，中文正常显示）** |
| CRLF + GBK | ✓ 成功 |

**根因是 LF，与编码无关。**

**做法与注意**：

- 改 `.cmd` 优先用编辑器工具（能保留 CRLF）；
- 非要用 `sed -i` 时必须补回：`sed 's/$/\r/'`；
- **批量替换 `.cmd` 内容后必须立刻复查 CR 数**（`tr -cd '\r' < file | wc -c`）；
- 用 `sed` / `awk` 生成测试副本会剥掉 CR —— 验证 CRLF 行为必须用 `[System.IO.File]::WriteAllText` 之类能精确控制行尾的方式。

### 机器强制（2026-10-12 起，详见 TS-2610-002）

上面这些办法全靠人工 —— 实测证明**靠不住**：仓库里的 `Dist/Bootstrap.cmd`（JVM 版双击入口）
与 4 个提权探针都曾在一次批量改写后退化成 LF，而没有任何一步拦它。现在由两处兜底：

| 位置 | 干什么 |
|---|---|
| 仓库根 `.gitattributes` | `*.cmd text eol=crlf`、`*.bat text eol=crlf`、`*.ps1 text eol=lf` —— 对**所有平台、所有 `core.autocrlf` 配置**生效：任何人签出、以及 CI 打包进 JVM 发布 zip，拿到的都是 CRLF |
| CI 自证 `CheckScriptLineEndings`（构建作业） | 逐个 `.cmd` / `.bat` 查「无裸 LF、无 BOM」，逐个 `.ps1` 查「无 CR」，并核对 `git ls-files --eol` 的属性与工作区行尾；不符即**失败** |

两个常见误解：

- 索引（blob）里存的是 **LF**、签出时才还原 CRLF —— 用 `git show HEAD:文件` 看到 LF **不代表**发布包是 LF（发布包来自签出/打包的工作区）；
- 批量改写后用 `git add --renormalize -- '*.cmd'` 归一化索引；若改完在 `git status` 里看不到差异，那通常是正常的（内容未变，只是签出行尾由属性控制）。

---

## 9. 交付产物文件名不带版本号

产物只在本机使用、不发布到 Maven 仓库；带版本号会让归档（`Dist/`）与启动脚本（`Bootstrap.cmd`）被迫做版本号匹配。

**做法**：pom 用 `<finalName>LegionFanControl</finalName>` 固定产物名，`version` 仅作标识、不进入文件名。升级 `version` 时，产物名与脚本均零改动。

---

## 10. 发布的 zip 内自带一层与 zip 同名的目录

文件名固定（第 9 条），但**包结构**不能平铺：否则用 WinRAR / 资源管理器「解压到当前目录」时，文件会散落到当前目录（多个版本互相覆盖、也难以归档）。

**做法**：每个资产**内部自带一层与 zip 同名（去掉 `.zip`）的目录**，包内文件名照旧不变：

```
LegionFanControl-0.2.0.00057-native-windows-x64.zip
└── LegionFanControl-0.2.0.00057-native-windows-x64/
    └── LegionFanControl.exe
```

- 目录名跟资产名（也就是 tag）**同源**，正式版不带编号段时目录名也不带；
- 包内文件名**不带**版本号 / 编号段 —— 升级脚本（`UpdateNative.ps1` / `UpdateJvm.ps1`）与 `Bootstrap.cmd` 都按固定名定位；
- 升级脚本用**递归查找**（`Get-ChildItem -Recurse -Filter`）定位本体，因此对「带目录的新包」与「平铺的旧包」都能升级；
- 由 `publish` 作业的 `PackageZip` 现场重打（JVM 包在 build 作业里先按纯版本号打好，编号段只有 `publish` 才知道 ⇒ 那里解开后重打），并有自证：**顶层有且只有一个目录、且与 zip 同名**、关键文件名必须是固定名。
