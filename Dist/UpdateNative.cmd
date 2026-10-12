@echo off
rem ==========================================================================
rem  Legion 风扇控制工具 —— 原生镜像版「自动升级」脚本（UpdateNative）
rem
rem  本脚本与 LegionFanControl.exe 同放在 Dist 目录下。
rem
rem  作用：从 GitHub 发布页面取「最新一个带 Windows x64 原生镜像包的 release」，
rem        下载其中的 zip、解压，并把 LegionFanControl.exe 覆盖到本目录。
rem        即：双击一下，exe 就升到最新版。
rem
rem  发布包内只有 exe 一个文件 —— JavaFX 的平台原生库已编进 exe（自包含），
rem  所以这里只认包里的 LegionFanControl.exe，不需要额外拷贝 DLL。
rem  机制与 tag 规则详见源码工程 Docs/NativeImageCi.md。
rem
rem  用法：
rem    UpdateNative.cmd             取最新一个带包的 release（含预发行）
rem    UpdateNative.cmd stable      只取正式版（非预发行）
rem
rem  本脚本**不需要管理员权限**（只做下载与覆盖文件）。
rem  若提示写入失败，说明程序正在运行 —— 先关闭它再重试。
rem
rem  编码约定（重要）：本文件必须保存为 CRLF 行尾 + UTF-8 编码，
rem        与 Dist/Bootstrap.cmd 同规 —— 见 Docs/DevelopmentConventions.md 第 8 条。
rem        若被编辑器改成 LF 行尾，cmd 解析含中文的行时会发生字节错位。
rem ==========================================================================

setlocal

rem ------------------------------- 配置区 -----------------------------------
rem 系统工具一律写绝对路径调用，避免被 PATH 中的同名程序遮蔽
rem （与 Bootstrap.cmd 同一约定；这里用 Windows PowerShell 5.1）
set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"

rem 干活的是同目录下的 .ps1：登录脚本、下载、解压、覆盖都在里面
rem 拆成两个文件的原因见 Docs/ProjectStructure.md「依赖自动更新」相邻一节
set "PS1=%~dp0UpdateNative.ps1"

rem 第一个参数为 stable 时只取正式版
set "MODE="
if /i "%~1"=="stable" set "MODE=-Stable"
rem ---------------------------------------------------------------------------

cd /d "%~dp0"

if not exist "%PS1%" (
    echo.
    echo [错误] 未找到升级脚本：
    echo        %PS1%
    echo.
    echo        UpdateNative.cmd 与 UpdateNative.ps1 必须放在同一目录。
    echo        请从发布页重新下载完整的压缩包。
    echo.
    pause
    exit /b 1
)

echo 正在检查 GitHub 上的最新版本，请稍候...
echo.

"%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %MODE%
rem 立刻取退出码，避免后续命令覆盖 errorlevel
set "RC=%errorlevel%"

echo.
if "%RC%"=="0" (
    echo [完成] 升级结束。可直接运行 LegionFanControl.exe。
) else (
    echo [失败] 升级未完成（退出码 %RC%）。具体原因见上方输出。
    echo        常见原因：网络不可达、GitHub API 限流、程序正在运行导致文件被占用。
)
echo.
pause
exit /b %RC%
