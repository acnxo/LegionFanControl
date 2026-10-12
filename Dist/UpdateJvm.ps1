<#
    LegionFanControl —— JVM 版「自动升级」脚本（由 UpdateJvm.cmd 调用）

    做三件事：
      1. 查 GitHub 发布页，找出最新的、带 JVM 包（…-jvm-windows-x64.zip）的 release；
      2. 下载该 zip 并解压；
      3. 把其中的 LegionFanControl.jar 覆盖到本目录（Dist）。

    为什么不整包覆盖：包内还有 Bootstrap.cmd / README.md / UpdateNative.* / UpdateJvm.* 等脚本，
    而 .cmd 正被 cmd.exe 逐行读取 —— 覆盖运行中的批处理有实际风险。
    因此本脚本只替换「本体」jar；包内其它文件若与本目录不同，会在结尾列出并提示重新下载整包
    （一次性动作，不常用）。

    与原生版的对称关系：UpdateNative.ps1 升级 LegionFanControl.exe，本脚本升级
    LegionFanControl.jar；两者的取值规则（含/不含预发行）、错误提示、退出码含义完全一致。

    参数：
      -Stable   只考虑正式版（非预发行）。默认取「最新一个带包的 release」并**包含预发行** ——
                本仓库目前的发布流以预发行为主，只认正式版会永远取不到包。
      -Repo     覆盖仓库，默认 acnxo/LegionFanControl（fork 后自用时可改）。

    退出码：0 成功；1 失败（UpdateJvm.cmd 据此给出不同结尾提示）。
#>

[CmdletBinding()]
param(
    [switch]$Stable,
    [string]$Repo = 'acnxo/LegionFanControl'
)

$ErrorActionPreference = 'Stop'
# 30MB 级别的下载，进度条会明显拖慢速度，且输出对日志无意义
$ProgressPreference = 'SilentlyContinue'
# Windows PowerShell 5.1 的默认 SecurityProtocol 可能不含 TLS 1.2，GitHub 会直接拒绝连接
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$DistDir = $PSScriptRoot
$TargetJar = Join-Path $DistDir 'LegionFanControl.jar'

Write-Host '===== LegionFanControl JVM 版 · 自动升级 ====='
Write-Host "仓库：$Repo"
Write-Host ("模式：" + $(if ($Stable) { '仅正式版' } else { '最新（含预发行）' }))
Write-Host ''

$headers = @{
    'User-Agent' = 'LegionFanControl-UpdateJvm'
    'Accept'     = 'application/vnd.github+json'
}

# ---------- 第 1 步：找最新的、带 JVM 包的 release ----------
# 与 UpdateNative.ps1 同规：不走 /releases/latest —— 那个接口只认「非预发行」，
# 而本仓库发布流以预发行为主，直接调会取不到。改为拉一页列表，从新到旧扫第一个带匹配资产的。
$api = "https://api.github.com/repos/$Repo/releases?per_page=30"
Write-Host "查询发布页：$api"
$releases = Invoke-RestMethod -Uri $api -Headers $headers

$pick = $null
foreach ($r in $releases) {
    if ($r.draft) { continue }
    if ($Stable -and $r.prerelease) { continue }
    $asset = $r.assets |
        Where-Object { $_.name -like '*jvm-windows-x64.zip' } |
        Select-Object -First 1
    if ($asset) {
        $pick = @{ Release = $r; Asset = $asset }
        break
    }
}

if (-not $pick) {
    throw '没有找到任何带 JVM 包（…-jvm-windows-x64.zip）的 release。若已指定 stable，可去掉该参数（改取含预发行的最新版）再试。'
}

$tag = $pick.Release.tag_name
$kind = '预发行版'
if (-not $pick.Release.prerelease) { $kind = '正式版' }
Write-Host "最新发布：$tag（$kind）"
Write-Host ("资产：" + $pick.Asset.name + "（" + [math]::Round($pick.Asset.size / 1MB, 1) + " MB）")
Write-Host ''

# ---------- 第 2 步：下载并解压 ----------
$work = Join-Path $env:TEMP ('lfc-update-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $work | Out-Null

$zip = Join-Path $work $pick.Asset.name
try {
    Write-Host "下载中：$($pick.Asset.browser_download_url)"
    Invoke-WebRequest -Uri $pick.Asset.browser_download_url -Headers $headers -OutFile $zip
    Write-Host ("已下载：" + [math]::Round((Get-Item -LiteralPath $zip).Length / 1MB, 1) + " MB")

    # 解压到 $work 的**子目录**：$work 里同时还放着刚下载的 zip 本身，
    # 若直接解压到 $work，结尾「比对包内其它文件」就会把那个 zip 也算进去（2026-10-10 误报过）。
    $extract = Join-Path $work 'extract'
    Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force

    # 包内自带一层**与 zip 同名**的目录（名字 = 资产名，含编号段；见 Docs/DevelopmentConventions.md 第 9 条），
    # 所以**递归**找 jar：这样新包（带同名目录）与旧包（平铺）都能升级。包内文件名固定，不带版本号。
    $src = Get-ChildItem -LiteralPath $extract -Recurse -File -Filter 'LegionFanControl.jar' | Select-Object -First 1 -ExpandProperty FullName
    if (-not $src) {
        throw "zip 内未找到 LegionFanControl.jar：$($pick.Asset.name)"
    }
}
catch {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    throw
}

# ---------- 第 3 步：覆盖本体 jar ----------
# 程序正在运行时（java -jar）jar 被占用，覆盖会失败 —— 单独接住，给一句能照做的提示
try {
    Copy-Item -LiteralPath $src -Destination $TargetJar -Force
}
catch {
    Write-Host ''
    Write-Host '[错误] 无法写入 LegionFanControl.jar —— 程序大概率正在运行。' -ForegroundColor Red
    Write-Host '        请先关闭它（用 Bootstrap.cmd 启动的窗口 / 相关 java 进程），再重新运行本脚本。'
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$fi = Get-Item -LiteralPath $TargetJar
Write-Host ''
Write-Host "已升级到 $tag" -ForegroundColor Green
Write-Host ("文件：" + $fi.FullName)
Write-Host ("大小：" + [math]::Round($fi.Length / 1MB, 1) + " MB     时间：" + $fi.LastWriteTime)

# ---------- 顺带提示：包内其它文件若与此目录不同 ----------
# 只提示不改写：.cmd 正被 cmd.exe 逐行读取，覆盖运行中的脚本有风险。
# 递归取：包内自带一层与 zip 同名的目录（故不能只看解压根目录）。
$others = Get-ChildItem -LiteralPath $extract -Recurse -File |
    Where-Object { $_.Name -ne 'LegionFanControl.jar' }
$differs = @()
foreach ($f in $others) {
    $local = Join-Path $DistDir $f.Name
    if (-not (Test-Path -LiteralPath $local)) {
        $differs += ($f.Name + '（本地缺失）')
        continue
    }
    if ((Get-Item -LiteralPath $local).Length -ne $f.Length) {
        $differs += ($f.Name + '（内容不同）')
    }
}
if ($differs.Count -gt 0) {
    Write-Host ''
    Write-Host '[提示] 本次发布包里的这些文件与本目录不一致：' -ForegroundColor Yellow
    foreach ($d in $differs) { Write-Host ("       - " + $d) }
    Write-Host '       本脚本刻意不覆盖它们（运行中的 .cmd 被改写有风险）。'
    Write-Host '       如需一并更新，请从发布页重新下载整包并解压覆盖。'
}

Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
exit 0
