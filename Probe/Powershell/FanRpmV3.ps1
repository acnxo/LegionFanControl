# FanRpmV3.ps1
# 目的：定位 GetFan1Speed/GetFan2Speed 返回 0 的根因。
# 假设：Get-WmiObject（WMI v1）无法正确解析 Lenovo 内嵌对象；
#       改用 Get-CimInstance（WMI v2/CIM）与直接调用 Win32 方法后应能取到真实值。
# 需要管理员权限。

$ErrorActionPreference = "Continue"
$outFile = Join-Path $PSScriptRoot "probe-result-rpm-v3.txt"
$L = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { $script:L.Add($s) }

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)
Log ("Time=" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Log ("IsAdmin=" + $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
Log ""

# ---------- A. Get-CimInstance 路径（WMI v2） ----------
Log "=== A. Get-CimInstance (WMI v2) ==="
try {
    $cimGz = Get-CimInstance -Namespace "root\WMI" -ClassName LENOVO_GAMEZONE_DATA -ErrorAction Stop
    Log ("cim instance count=" + @($cimGz).Count)
    $cimGz = @($cimGz)[0]
    Log ("cim InstanceName=" + $cimGz.InstanceName)
    $r1 = Invoke-CimMethod -InputObject $cimGz -MethodName GetFan1Speed -ErrorAction Stop
    Log ("CIM GetFan1Speed raw=" + ($r1 | ConvertTo-Json -Compress -Depth 4))
    $r2 = Invoke-CimMethod -InputObject $cimGz -MethodName GetFan2Speed -ErrorAction Stop
    Log ("CIM GetFan2Speed raw=" + ($r2 | ConvertTo-Json -Compress -Depth 4))
} catch { Log ("A ERR: " + $_.Exception.Message) }
Log ""

# ---------- B. Get-WmiObject 对照（v1，probe 原路径） ----------
Log "=== B. Get-WmiObject (WMI v1, 对照) ==="
try {
    $gz = @(Get-WmiObject -Namespace "root\WMI" -Class LENOVO_GAMEZONE_DATA -ErrorAction Stop)
    Log ("wmi instance count=" + $gz.Count)
    for ($i = 0; $i -lt [Math]::Min($gz.Count, 4); $i++) {
        $ins = $gz[$i]
        try { $v1 = [int]$ins.GetFan1Speed().Data } catch { $v1 = "ERR" }
        try { $v2 = [int]$ins.GetFan2Speed().Data } catch { $v2 = "ERR" }
        Log ("  wmi[$i] " + $ins.InstanceName + " fan1=" + $v1 + " fan2=" + $v2)
    }
} catch { Log ("B ERR: " + $_.Exception.Message) }
Log ""

# ---------- C. FAN_METHOD: Fan_GetCurrentFanSpeed(0/1) ----------
Log "=== C. FAN_METHOD.Fan_GetCurrentFanSpeed(FanID) ==="
foreach ($useCim in $true, $false) {
    $tag = if ($useCim) { "CIM" } else { "WMI" }
    try {
        if ($useCim) {
            $fm = @(Get-CimInstance -Namespace "root\WMI" -ClassName LENOVO_FAN_METHOD -ErrorAction Stop)[0]
            foreach ($fid in 0, 1) {
                $r = Invoke-CimMethod -InputObject $fm -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID = [byte]$fid } -ErrorAction Stop
                Log ("  [" + $tag + "] FanID=" + $fid + " raw=" + ($r | ConvertTo-Json -Compress -Depth 4))
            }
        } else {
            $fm2 = @(Get-WmiObject -Namespace "root\WMI" -Class LENOVO_FAN_METHOD -ErrorAction Stop)[0]
            foreach ($fid in 0, 1) {
                try { $r = $fm2.Fan_GetCurrentFanSpeed([byte]$fid) } catch { $r = $null }
                Log ("  [" + $tag + "] FanID=" + $fid + " raw=" + ($r | ConvertTo-Json -Compress -Depth 4))
            }
        }
    } catch { Log ("  [" + $tag + "] ERR: " + $_.Exception.Message) }
}
Log ""

# ---------- D. Fan_GetCurrentSensorTemperature + 传感器温度 ----------
Log "=== D. Fan_GetCurrentSensorTemperature ==="
try {
    $fm = @(Get-CimInstance -Namespace "root\WMI" -ClassName LENOVO_FAN_METHOD)[0]
    foreach ($fid in 0, 1) {
        $r = Invoke-CimMethod -InputObject $fm -MethodName Fan_GetCurrentSensorTemperature -Arguments @{FanID = [byte]$fid }
        Log ("  FanID=" + $fid + " raw=" + ($r | ConvertTo-Json -Compress -Depth 4))
    }
} catch { Log ("D ERR: " + $_.Exception.Message) }
Log ""

# ---------- E. 3 次采样确认稳定性（CIM 路径） ----------
Log "=== E. 3 次采样 (CIM) ==="
$cimGz = @(Get-CimInstance -Namespace "root\WMI" -ClassName LENOVO_GAMEZONE_DATA)[0]
for ($i = 0; $i -lt 3; $i++) {
    $a = (Invoke-CimMethod -InputObject $cimGz -MethodName GetFan1Speed).Data
    $b = (Invoke-CimMethod -InputObject $cimGz -MethodName GetFan2Speed).Data
    Log ("  t=" + ($i * 2) + "s fan1=" + $a + " fan2=" + $b)
    Start-Sleep -Seconds 2
}

[System.IO.File]::WriteAllLines($outFile, $L, [System.Text.Encoding]::UTF8)
