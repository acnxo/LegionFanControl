# FanWmiV2.ps1
# Follow-up elevated probe for Legion Y9000P IAH7H (Gen 7).
# Corrections vs v1: FanID is 0/1 (not 1/2); full-speed path is
# LENOVO_FAN_METHOD.Fan_Set_FullSpeed (SetFanCooling reported unsupported).
# Captures full JSON of method results so the Java bridge knows exact
# out-parameter names. Triggers full speed once, samples RPM 26s, restores.

$ErrorActionPreference = "Continue"
$outFile = Join-Path $PSScriptRoot "probe-result-elevated-v2.txt"
$L = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { $script:L.Add($s) }
function J([string]$label, $obj) {
    if ($null -eq $obj) { Log ($label + ": <null>"); return }
    try { Log ($label + ": " + ($obj | ConvertTo-Json -Compress -Depth 3)) }
    catch { Log ($label + ": <json err: " + $_.Exception.Message + ">") }
}

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)
Log ("Time=" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Log ("IsAdmin=" + $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
Log ""

$gz = @(Get-WmiObject -Namespace "root\WMI" -Class LENOVO_GAMEZONE_DATA) | Select-Object -First 1
$fm = @(Get-WmiObject -Namespace "root\WMI" -Class LENOVO_FAN_METHOD) | Select-Object -First 1
Log ("gz=" + $gz.InstanceName)
Log ("fm=" + $fm.InstanceName)
Log ""

# --- 1. structure discovery (JSON of every relevant getter) ---
try { J "GetFanCount" ($gz.GetFanCount()) } catch { Log ("GetFanCount ERR: " + $_.Exception.Message) }
try { J "GetFan1Speed" ($gz.GetFan1Speed()) } catch { Log ("GetFan1Speed ERR: " + $_.Exception.Message) }
try { J "GetFan2Speed" ($gz.GetFan2Speed()) } catch { Log ("GetFan2Speed ERR: " + $_.Exception.Message) }
try { J "GetFanMaxSpeed" ($gz.GetFanMaxSpeed()) } catch { Log ("GetFanMaxSpeed ERR: " + $_.Exception.Message) }
try { J "GetFanCoolingStatus" ($gz.GetFanCoolingStatus()) } catch { Log ("GetFanCoolingStatus ERR: " + $_.Exception.Message) }
try { J "GetIRTemp" ($gz.GetIRTemp()) } catch { Log ("GetIRTemp ERR: " + $_.Exception.Message) }
try { J "Fan_Get_FullSpeed(no-arg)" ($fm.Fan_Get_FullSpeed()) } catch { Log ("Fan_Get_FullSpeed(no-arg) ERR: " + $_.Exception.Message) }
foreach ($fid in 0, 1) {
    try { J ("Fan_GetCurrentFanSpeed(" + $fid + ")") ($fm.Fan_GetCurrentFanSpeed([byte]$fid)) } catch { Log ("Fan_GetCurrentFanSpeed($fid) ERR: " + $_.Exception.Message) }
    try { J ("Fan_Get_MaxSpeed(" + $fid + ")") ($fm.Fan_Get_MaxSpeed([byte]$fid)) } catch { Log ("Fan_Get_MaxSpeed($fid) ERR: " + $_.Exception.Message) }
    try { J ("Fan_Get_FullSpeed(" + $fid + ")") ($fm.Fan_Get_FullSpeed([byte]$fid)) } catch { Log ("Fan_Get_FullSpeed($fid) ERR: " + $_.Exception.Message) }
    try { J ("Fan_GetCurrentSensorTemperature(" + $fid + ")") ($fm.Fan_GetCurrentSensorTemperature([byte]$fid)) } catch { Log ("Fan_GetCurrentSensorTemperature($fid) ERR: " + $_.Exception.Message) }
    try { J ("Fan_Get_Table(" + $fid + ")") ($fm.Fan_Get_Table([byte]$fid)) } catch { Log ("Fan_Get_Table($fid) ERR: " + $_.Exception.Message) }
}
Log ""

# --- 2. baseline RPM via both read paths ---
function Read-Rpm {
    $r = [ordered]@{ g1 = -1; g2 = -1; f0 = -1; f1 = -1 }
    try { $r.g1 = [int]$gz.GetFan1Speed().Data } catch {}
    try { $r.g2 = [int]$gz.GetFan2Speed().Data } catch {}
    try { $r.f0 = [int]$fm.Fan_GetCurrentFanSpeed([byte]0).Speed } catch {}
    try { $r.f1 = [int]$fm.Fan_GetCurrentFanSpeed([byte]1).Speed } catch {}
    return $r
}
Log "=== baseline ==="
for ($i = 0; $i -lt 3; $i++) {
    $s = Read-Rpm
    Log ("t=" + ($i * 2) + "s g1=" + $s.g1 + " g2=" + $s.g2 + " f0=" + $s.f0 + " f1=" + $s.f1)
    Start-Sleep -Seconds 2
}
Log ""

# --- 3. full-speed trigger via Fan_Set_FullSpeed (try bool then byte signature) ---
Log "=== Fan_Set_FullSpeed(true) ==="
$setOk = $false
try {
    $r = $fm.Fan_Set_FullSpeed([bool]$true)
    J "Fan_Set_FullSpeed(bool true) ret" $r
    $setOk = $true
} catch {
    Log ("Fan_Set_FullSpeed(bool true) ERR: " + $_.Exception.Message)
}
if (-not $setOk) {
    try {
        $r = $fm.Fan_Set_FullSpeed([byte]1)
        J "Fan_Set_FullSpeed(byte 1) ret" $r
        $setOk = $true
    } catch {
        Log ("Fan_Set_FullSpeed(byte 1) ERR: " + $_.Exception.Message)
    }
}
for ($i = 1; $i -le 13; $i++) {
    Start-Sleep -Seconds 2
    $s = Read-Rpm
    Log ("t=" + ($i * 2) + "s g1=" + $s.g1 + " g2=" + $s.g2 + " f0=" + $s.f0 + " f1=" + $s.f1)
}

# --- 4. restore ---
Log "=== restore: Fan_Set_FullSpeed(false) ==="
$restored = $false
try {
    $r = $fm.Fan_Set_FullSpeed([bool]$false)
    J "Fan_Set_FullSpeed(bool false) ret" $r
    $restored = $true
} catch {
    Log ("Fan_Set_FullSpeed(bool false) ERR: " + $_.Exception.Message)
}
if (-not $restored) {
    try {
        $r = $fm.Fan_Set_FullSpeed([byte]0)
        J "Fan_Set_FullSpeed(byte 0) ret" $r
    } catch {
        Log ("Fan_Set_FullSpeed(byte 0) ERR: " + $_.Exception.Message)
    }
}
Start-Sleep -Seconds 3
$s = Read-Rpm
Log ("post g1=" + $s.g1 + " g2=" + $s.g2 + " f0=" + $s.f0 + " f1=" + $s.f1)

[System.IO.File]::WriteAllLines($outFile, $L, [System.Text.Encoding]::UTF8)
