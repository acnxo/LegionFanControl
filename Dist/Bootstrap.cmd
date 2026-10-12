@echo off
rem ==========================================================================
rem  Legion 风扇控制工具 —— 一键启动脚本（Bootstrap）
rem
rem  本脚本与 LegionFanControl.jar 同放在 Dist 目录下。
rem  Dist 即完整的「可分发文件夹」：整个 Dist 目录拷贝到任何位置都能直接运行。
rem
rem  作用：把运行所需的全部参数封装在本脚本中，双击即可启动，
rem        用户无需记忆或输入任何命令。
rem
rem  本脚本封装了以下内容：
rem    1. JVM 参数 --enable-native-access=ALL-UNNAMED
rem       本程序以非模块化 classpath 方式加载 JavaFX，该开关用于消除
rem       JDK 25 加载 JavaFX native 库时的 restricted method 警告；
rem       缺少它不影响功能，但控制台会出现警告输出。
rem    2. jar 路径（与本脚本同目录，文件名固定、不含版本号）与工作目录。
rem    3. 管理员权限：自动检测，不足时自动请求提权。
rem       联想 WMI 在非管理员上下文下会返回「拒绝访问」，
rem       转速与温度将无法读取，因此提权是必需步骤。
rem
rem  使用方法：双击本文件，在 UAC 弹窗中点击「是」即可。
rem            也可以在已提权的终端中直接执行：Bootstrap.cmd
rem
rem  编码约定（重要）：本文件必须保存为 CRLF 行尾 + UTF-8 编码。
rem        若被编辑器改成 LF 行尾，cmd 解析含中文的行时会发生字节错位，
rem        导致脚本行为异常。
rem ==========================================================================

setlocal

rem ------------------------------- 配置区 -----------------------------------
rem 以下配置一般无需修改。
rem 注意：产物名固定为 LegionFanControl.jar（pom 的 finalName 不带版本号），
rem       因此升级版本后本脚本无需任何改动，也不存在版本号解析。
set "JAVA_OPTS=--enable-native-access=ALL-UNNAMED"

rem 如需指定特定 JDK，取消下一行注释并填入 java.exe 的完整路径
rem set "JAVA_EXE=D:\Dev\Java\JDK\25\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java"

rem 静默启动用 javaw.exe（GUI 子系统、无控制台）：与 java.exe 同目录；
rem JAVA_EXE 是裸 "java"（走 PATH）时，直接用 PATH 里的 javaw。
set "JAVAW_EXE=javaw"
if /i not "%JAVA_EXE%"=="java" (
    for %%I in ("%JAVA_EXE%") do set "JAVAW_EXE=%%~dpIjavaw.exe"
)

rem 系统工具一律用绝对路径调用，避免被 PATH 中的同名程序遮蔽
set "PS_EXE=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
rem ---------------------------------------------------------------------------

rem 切换到脚本所在目录（即 Dist 目录）
cd /d "%~dp0"

rem ---------- 定位 jar（产物名固定，无需版本匹配）----------
rem 首选与本脚本同目录的 LegionFanControl.jar —— Dist 即分发包，自包含。
rem 若不存在（例如 Dist 被手动清理过），回退到源码工程的构建目录 ..\target\，
rem 方便开发期直接运行；该回退只在源码目录结构下有效，拷贝走的 Dist 不依赖它。
set "JAR_PATH=%~dp0LegionFanControl.jar"
if not exist "%JAR_PATH%" set "JAR_PATH=%~dp0..\target\LegionFanControl.jar"

rem ---------- 第 1 步：确认 jar 已构建 ----------
if not exist "%JAR_PATH%" (
    echo.
    echo [错误] 未找到可运行的 jar：
    echo        %JAR_PATH%
    echo.
    echo        请先构建，产物会自动导出到 Dist 目录：mvn clean package
    echo.
    pause
    exit /b 1
)

rem ---------- 第 2 步：确认 java 可用 ----------
"%JAVA_EXE%" -version >nul 2>&1
if errorlevel 1 (
    echo.
    echo [错误] 无法运行 Java：%JAVA_EXE%
    echo        请确认已安装 JDK 25 且 java 已加入 PATH，
    echo        或在本脚本配置区把 JAVA_EXE 设为 java.exe 的完整路径。
    echo.
    pause
    exit /b 1
)

rem ---------- 第 3 步：确认管理员权限，不足则自我提权重启 ----------
rem 判定方式：whoami 输出里的 Mandatory Label 含 S-1-16-12288 即「高完整性」，
rem          也就是已提权。这里用绝对路径调用系统工具，避免被 PATH 中
rem          同名程序遮蔽（例如 Git Bash 也提供 whoami）。
%SystemRoot%\System32\whoami.exe /groups | %SystemRoot%\System32\findstr.exe /C:"S-1-16-12288" >nul 2>&1
if errorlevel 1 (
    echo 正在请求管理员权限，请在 UAC 弹窗中点击「是」...
    "%PS_EXE%" -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs -ErrorAction Stop"
    if errorlevel 1 (
        echo.
        echo [错误] 提权未成功（通常是 UAC 弹窗被取消或拒绝）。
        echo        本程序必须管理员权限，否则读不到风扇转速与温度、按钮不可用。
        echo        请右键本脚本，选择「以管理员身份运行」再试一次。
        echo.
        pause
    )
    rem 提权成功时，新窗口会重新执行本脚本并继续；当前窗口使命完成，直接退出
    exit /b 0
)

rem ---------- 第 4 步：启动程序（启动成功即退出，不留窗口）----------
rem 用 javaw 后台启动：GUI 子系统、没有控制台，所以脚本结束后不会留下任何窗口，
rem 也不再需要「按任意键继续」。
rem 注意：这样一来程序日志不再打在窗口里 —— 需要看 渲染诊断：… 时，
rem       请在已提权的终端里手动执行：
rem         java --enable-native-access=ALL-UNNAMED -jar LegionFanControl.jar
rem 出错提示由前面的检查负责（jar 缺失 / java 不可用 / 提权失败都会停在窗口里）。
rem %%* 用于透传额外命令行参数（一般用不到，保留以便扩展）
echo 正在启动 Legion 风扇控制工具...
start "" "%JAVAW_EXE%" %JAVA_OPTS% -jar "%JAR_PATH%" %*

endlocal
