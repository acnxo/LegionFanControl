# FanShape.ps1  （已按 LenovoLegionToolkit 权威规格重写）
# 目的：在本机确认「正确的属性名」能取到真实值。
# 权威来源：
#   LLTK WMI.LenovoFanMethod.cs / WMI.LenovoOtherMethod.cs / Enums.cs(CapabilityID)
# 纯读取，不修改任何硬件状态。需要管理员权限。

$ErrorActionPreference = "Continue"
$outFile = Join-Path $PSScriptRoot "probe-result-shape.txt"
$L = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { $script:L.Add($s) }

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)
Log ("Time=" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Log ("IsAdmin=" + $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
Log ""

$OM = @(Get-CimInstance -Namespace 'root\WMI' -ClassName LENOVO_OTHER_METHOD -ErrorAction SilentlyContinue)
$FM = @(Get-CimInstance -Namespace 'root\WMI' -ClassName LENOVO_FAN_METHOD -ErrorAction SilentlyContinue)
Log ("OTHER_METHOD instances=" + $OM.Count + "  FAN_METHOD instances=" + $FM.Count)
Log ""

# ---------- 1. CapabilityID 通路（现代机型首选）----------
Log "########## 1. LENOVO_OTHER_METHOD.GetFeatureValue ##########"
$caps = [ordered]@{
  'CpuCurrentTemperature(0x05040000)' = 0x05040000
  'GpuCurrentTemperature(0x05050000)' = 0x05050000
  'CpuCurrentFanSpeed(0x04030001)'    = 0x04030001
  'GpuCurrentFanSpeed(0x04030002)'    = 0x04030002
  'FanFullSpeed(0x04020000)'          = 0x04020000
}
if ($OM.Count -gt 0) {
  foreach ($k in $caps.Keys) {
    try {
      $r = Invoke-CimMethod -InputObject $OM[0] -MethodName GetFeatureValue -Arguments @{IDs=[int]$caps[$k]} -ErrorAction Stop
      Log ("  " + $k + " => Value=" + $r.Value)
    } catch { Log ("  " + $k + " ERR: " + $_.Exception.Message) }
  }
} else { Log "  <no LENOVO_OTHER_METHOD instance>" }
Log ""

# ---------- 2. FAN_METHOD 正确属性名 ----------
Log "########## 2. LENOVO_FAN_METHOD (属性名 CurrentFanSpeed / CurrentSensorTemperature) ##########"
if ($FM.Count -gt 0) {
  foreach ($fid in 0, 1) {
    try {
      $r = Invoke-CimMethod -InputObject $FM[0] -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]$fid} -ErrorAction Stop
      Log ("  Fan_GetCurrentFanSpeed(FanID=" + $fid + ") => CurrentFanSpeed=" + $r.CurrentFanSpeed)
    } catch { Log ("  Fan_GetCurrentFanSpeed(" + $fid + ") ERR: " + $_.Exception.Message) }
  }
  foreach ($sid in 1, 3, 4, 5) {
    try {
      $r = Invoke-CimMethod -InputObject $FM[0] -MethodName Fan_GetCurrentSensorTemperature -Arguments @{SensorID=[int]$sid} -ErrorAction Stop
      Log ("  Fan_GetCurrentSensorTemperature(SensorID=" + $sid + ") => CurrentSensorTemperature=" + $r.CurrentSensorTemperature)
    } catch { Log ("  SensorTemp(" + $sid + ") ERR: " + $_.Exception.Message) }
  }
  try {
    $r = Invoke-CimMethod -InputObject $FM[0] -MethodName Fan_Get_FullSpeed -ErrorAction Stop
    $tn = if ($null -ne $r.Status) { $r.Status.GetType().Name } else { "null" }
    Log ("  Fan_Get_FullSpeed => Status=" + $r.Status + "  (type=" + $tn + ")")
  } catch { Log ("  Fan_Get_FullSpeed ERR: " + $_.Exception.Message) }
} else { Log "  <no LENOVO_FAN_METHOD instance>" }
Log ""

# ---------- 3. 3 次采样（验证稳定性）----------
Log "########## 3. 3 次采样 ##########"
for ($i = 0; $i -lt 3; $i++) {
  $line = "  t=" + ($i * 2) + "s "
  try {
    $a = Invoke-CimMethod -InputObject $FM[0] -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]0}
    $b = Invoke-CimMethod -InputObject $FM[0] -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]1}
    $line += "fan(0)=" + $a.CurrentFanSpeed + " fan(1)=" + $b.CurrentFanSpeed
  } catch { $line += "fan ERR: " + $_.Exception.Message }
  if ($OM.Count -gt 0) {
    try {
      $t = Invoke-CimMethod -InputObject $OM[0] -MethodName GetFeatureValue -Arguments @{IDs=[int]0x05040000}
      $line += " cpuT=" + $t.Value
    } catch { $line += " cpuT ERR" }
  }
  Log $line
  Start-Sleep -Seconds 2
}

[System.IO.File]::WriteAllLines($outFile, $L, [System.Text.Encoding]::UTF8)
