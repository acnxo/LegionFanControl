# 仅做语法解析校验，不执行任何 WMI 调用（无需管理员）
$scripts = @{
  "CIM_GAMEZONE" = '$ErrorActionPreference=''Stop''; $i=@(Get-CimInstance -Namespace ''root\WMI'' -ClassName LENOVO_GAMEZONE_DATA)[0]; $a=Invoke-CimMethod -InputObject $i -MethodName GetFan1Speed; $b=Invoke-CimMethod -InputObject $i -MethodName GetFan2Speed; $x=$a.Data; if ($null -eq $x) { $x=$a.Speed }; $y=$b.Data; if ($null -eq $y) { $y=$b.Speed }; Write-Output (''V='' + [int]$x + '','' + [int]$y)'
  "CIM_FANMETHOD" = '$ErrorActionPreference=''Stop''; $i=@(Get-CimInstance -Namespace ''root\WMI'' -ClassName LENOVO_FAN_METHOD)[0]; $a=Invoke-CimMethod -InputObject $i -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]0}; $b=Invoke-CimMethod -InputObject $i -MethodName Fan_GetCurrentFanSpeed -Arguments @{FanID=[byte]1}; $x=$a.Speed; if ($null -eq $x) { $x=$a.Data }; $y=$b.Speed; if ($null -eq $y) { $y=$b.Data }; Write-Output (''V='' + [int]$x + '','' + [int]$y)'
  "WMI_LEGACY" = '$ErrorActionPreference=''Stop''; $g=(Get-WmiObject -Namespace root\WMI -Class LENOVO_GAMEZONE_DATA | Select-Object -First 1); Write-Output (''V='' + $g.GetFan1Speed().Data.ToString() + '','' + $g.GetFan2Speed().Data.ToString())'
}
foreach ($k in $scripts.Keys) {
  $errs = $null
  [System.Management.Automation.Language.Parser]::ParseInput($scripts[$k], [ref]$null, [ref]$errs) | Out-Null
  if ($errs.Count -eq 0) { Write-Output ($k + " : PARSE OK") }
  else { foreach ($e in $errs) { Write-Output ($k + " : ERR " + $e.Message) } }
}
