<#
    LegionFanControl —— 原生镜像版「自动升级」脚本（由 UpdateNative.cmd 调用）

    做三件事：
      1. 查 GitHub 发布页，找出最新的、带 Windows x64 原生镜像包的 release；
      2. 下载该 zip 并解压；
      3. 把其中的 LegionFanControl.exe 覆盖到本目录（Dist）。

    为什么包里只解出一个 exe：JavaFX 的平台原生库已编进 exe（自包含），
    发布 zip 内只有 LegionFanControl.exe 一个文件 —— 机制见 Docs/NativeImageCi.md。

    参数：
      -Stable   只考虑正式版（非预发行）。默认取「最新一个带包的 release」并**包含预发行** ——
                本仓库目前的发布流以预发行为主，只认正式版会永远取不到包。
      -Repo     覆盖仓库，默认 acnxo/LegionFanControl（fork 后自用时可改）。

    退出码：0 成功；1 失败（UpdateNative.cmd 据此给出不同结尾提示）。
#>

[CmdletBinding()]
param(
    [switch]$Stable,
    [string]$Repo = 'acnxo/LegionFanControl'
)

$ErrorActionPreference = 'Stop'
# 20MB 级别的下载，进度条会明显拖慢速度，且输出对日志无意义
$ProgressPreference = 'SilentlyContinue'
# Windows PowerShell 5.1 的默认 SecurityProtocol 可能不含 TLS 1.2，GitHub 会直接拒绝连接
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$DistDir = $PSScriptRoot
$TargetExe = Join-Path $DistDir 'LegionFanControl.exe'

Write-Host '===== LegionFanControl 原生镜像版 · 自动升级 ====='
Write-Host "仓库：$Repo"
Write-Host ("模式：" + $(if ($Stable) { '仅正式版' } else { '最新（含预发行）' }))
Write-Host ''

$headers = @{
    'User-Agent' = 'LegionFanControl-UpdateNative'
    'Accept'     = 'application/vnd.github+json'
}

# ---------- 第 1 步：找最新的、带原生镜像包的 release ----------
# 默认不走 /releases/latest —— 那个接口只认「非预发行」，本仓库目前几乎全是预发行，会 404。
# 改为拉一页列表，按发布时间从新到旧扫，取第一个带匹配资产的。
$api = "https://api.github.com/repos/$Repo/releases?per_page=30"
Write-Host "查询发布页：$api"
$releases = Invoke-RestMethod -Uri $api -Headers $headers

$pick = $null
foreach ($r in $releases) {
    if ($r.draft) { continue }
    if ($Stable -and $r.prerelease) { continue }
    $asset = $r.assets |
        Where-Object { $_.name -like '*native-windows-x64.zip' } |
        Select-Object -First 1
    if ($asset) {
        $pick = @{ Release = $r; Asset = $asset }
        break
    }
}

if (-not $pick) {
    throw '没有找到任何带原生镜像包的 release。若已指定 stable，可去掉该参数（改取含预发行的最新版）再试。'
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

    # 解压到 $work 的**子目录**：$work 里同时还放着刚下载的 zip 本身
    # （与 UpdateJvm.ps1 同规，保持两个脚本结构一致，便于对照维护）。
    $extract = Join-Path $work 'extract'
    Expand-Archive -LiteralPath $zip -DestinationPath $extract -Force

    # 包内自带一层**与 zip 同名**的目录（名字 = 资产名，含编号段；见 Docs/DevelopmentConventions.md 第 9 条），
    # 所以**递归**找 exe：这样新包（带同名目录）与旧包（平铺）都能升级。包内文件名固定，不带版本号。
    $src = Get-ChildItem -LiteralPath $extract -Recurse -File -Filter 'LegionFanControl.exe' | Select-Object -First 1 -ExpandProperty FullName
    if (-not $src) {
        throw "zip 内未找到 LegionFanControl.exe：$($pick.Asset.name)"
    }
}
catch {
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    throw
}

# ---------- 第 3 步：覆盖到 Dist ----------
# 程序正在运行时 exe 被占用，覆盖会失败 —— 这里单独接住，给一句能照做的提示
try {
    Copy-Item -LiteralPath $src -Destination $TargetExe -Force
}
catch {
    Write-Host ''
    Write-Host '[错误] 无法写入 LegionFanControl.exe —— 程序大概率正在运行。' -ForegroundColor Red
    Write-Host '        请先关闭它（包括后台/托盘进程），再重新运行本脚本。'
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
    exit 1
}

$fi = Get-Item -LiteralPath $TargetExe
Write-Host ''
Write-Host "已升级到 $tag" -ForegroundColor Green
Write-Host ("文件：" + $fi.FullName)
Write-Host ("大小：" + [math]::Round($fi.Length / 1MB, 1) + " MB     时间：" + $fi.LastWriteTime)

Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
exit 0
