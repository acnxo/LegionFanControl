# FanWmiV1.ps1
# Elevated probe of Lenovo WMI fan interfaces on Legion Y9000P IAH7H (Gen 7).
# What it does:
#   1) Enumerate methods of LENOVO_* WMI classes (planning data for v1/v2)
#   2) Dump fan-table data class instances (for v2 curve evaluation)
#   3) Capability checks: IsSupportFanCooling / GetSmartFanMode / temps
#   4) Baseline RPM -> SetFanCooling(1) -> sample RPM for 26s -> SetFanCooling(0)
# SetFanCooling(1) triggers the firmware dust-removal waveform (fans at high
# speed in pulses, auto-resets after ~2 min). This is the same call Lenovo
# PC Manager uses. No persistent changes are made.

$ErrorActionPreference = "Continue"
$outFile = Join-Path $PSScriptRoot "probe-result-elevated.txt"
$L = New-Object System.Collections.Generic.List[string]
function Log([string]$s) { $script:L.Add($s) }
function Fmt($v) { if ($null -eq $v) { return "null" } if ($v -is [Array]) { return ($v -join ",") } return [string]$v }

$id = [Security.Principal.WindowsIdentity]::GetCurrent()
$pr = New-Object Security.Principal.WindowsPrincipal($id)
Log ("Time=" + (Get-Date -Format "yyyy-MM-dd HH:mm:ss"))
Log ("User=" + $id.Name)
Log ("IsAdmin=" + $pr.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator))
Log ""

# --- 1. Method inventory ---
foreach ($cn in @("LENOVO_GAMEZONE_DATA","LENOVO_FAN_METHOD","LENOVO_OTHER_METHOD","LENOVO_CPU_METHOD","LENOVO_GPU_METHOD")) {
    Log ("=== methods: " + $cn + " ===")
    try {
        $cls = New-Object System.Management.ManagementClass("\\.\root\WMI:$cn")
        $names = @($cls.Methods | ForEach-Object { $_.Name })
        if ($names.Count -gt 0) { foreach ($n in $names) { Log $n } } else { Log "(no methods exposed)" }
    } catch { Log ("ERR: " + $_.Exception.Message) }
    Log ""
}

# --- 2. Data class instances ---
foreach ($cn in @("LENOVO_FAN_TABLE_DATA","LENOVO_FAN_MAX_SPEED_DATA","LENOVO_DEFAULT_VALUE_IN_DIFFERENT_MODE_DATA","LENOVO_UTILITY_DATA")) {
    Log ("=== instances: " + $cn + " ===")
    try {
        $inst = @(Get-WmiObject -Namespace "root\WMI" -Class $cn -ErrorAction Stop)
        if ($inst.Count -gt 0) {
            foreach ($o in $inst) {
                $props = @($o.Properties | ForEach-Object { $_.Name + "=" + (Fmt $_.Value) })
                Log ($props -join "; ")
            }
        } else { Log "(no instances)" }
    } catch { Log ("ERR: " + $_.Exception.Message) }
    Log ""
}

# --- 3. Bind method-class instances ---
$gz = $null; $fm = $null
try { $gz = @(Get-WmiObject -Namespace "root\WMI" -Class LENOVO_GAMEZONE_DATA -ErrorAction Stop) | Select-Object -First 1 } catch { Log ("GAMEZONE bind ERR: " + $_.Exception.Message) }
try { $fm = @(Get-WmiObject -Namespace "root\WMI" -Class LENOVO_FAN_METHOD -ErrorAction Stop) | Select-Object -First 1 } catch { Log ("FAN_METHOD bind ERR: " + $_.Exception.Message) }
Log ("GAMEZONE instance=" + $(if ($gz) { $gz.InstanceName } else { "null" }))
Log ("FAN_METHOD instance=" + $(if ($fm) { $fm.InstanceName } else { "null" }))
Log ""

function Read-Fans {
    $r = [ordered]@{ f1 = -1; f2 = -1 }
    if ($fm) {
        try { $r.f1 = [int]($fm.Fan_GetCurrentFanSpeed([byte]1).Speed) } catch { Log ("Fan1 read ERR: " + $_.Exception.Message) }
        try { $r.f2 = [int]($fm.Fan_GetCurrentFanSpeed([byte]2).Speed) } catch { Log ("Fan2 read ERR: " + $_.Exception.Message) }
    }
    return $r
}

# --- 4. Capability checks ---
if ($gz) {
    try { $r = $gz.IsSupportFanCooling(); Log ("IsSupportFanCooling: Data=" + (Fmt $r.Data) + " ReturnValue=" + $r.ReturnValue) } catch { Log ("IsSupportFanCooling ERR: " + $_.Exception.Message) }
    try { $r = $gz.GetSmartFanMode(); Log ("GetSmartFanMode: Data=" + (Fmt $r.Data) + " (1=quiet 2=balance 3=perf 255=custom)") } catch { Log ("GetSmartFanMode ERR: " + $_.Exception.Message) }
    try { $r = $gz.GetCPUTemp(); Log ("GetCPUTemp: Data=" + (Fmt $r.Data)) } catch { Log ("GetCPUTemp ERR: " + $_.Exception.Message) }
    try { $r = $gz.GetGPUTemp(); Log ("GetGPUTemp: Data=" + (Fmt $r.Data)) } catch { Log ("GetGPUTemp ERR: " + $_.Exception.Message) }
} else {
    Log "GAMEZONE not bound, capability checks skipped"
}
Log ""

# --- 5. Baseline RPM ---
Log "=== baseline (3 samples, 2s apart) ==="
for ($i = 0; $i -lt 3; $i++) {
    $s = Read-Fans
    Log ("t=" + ($i * 2) + "s fan1=" + $s.f1 + " fan2=" + $s.f2)
    Start-Sleep -Seconds 2
}
Log ""

# --- 6. Trigger full-speed / dust-removal ---
Log "=== SetFanCooling(1) ==="
if ($gz) {
    try { $r = $gz.SetFanCooling(1); Log ("SetFanCooling(1) ReturnValue=" + $r.ReturnValue) } catch { Log ("SetFanCooling(1) ERR: " + $_.Exception.Message) }
} else {
    Log "GAMEZONE not bound, trigger skipped"
}
for ($i = 1; $i -le 13; $i++) {
    Start-Sleep -Seconds 2
    $s = Read-Fans
    Log ("t=" + ($i * 2) + "s fan1=" + $s.f1 + " fan2=" + $s.f2)
}
Log ""

# --- 7. Cleanup ---
Log "=== SetFanCooling(0) ==="
if ($gz) {
    try { $r = $gz.SetFanCooling(0); Log ("SetFanCooling(0) ReturnValue=" + $r.ReturnValue) } catch { Log ("SetFanCooling(0) ERR: " + $_.Exception.Message) }
}
Start-Sleep -Seconds 3
$s = Read-Fans
Log ("post fan1=" + $s.f1 + " fan2=" + $s.f2)

[System.IO.File]::WriteAllLines($outFile, $L, [System.Text.Encoding]::UTF8)
