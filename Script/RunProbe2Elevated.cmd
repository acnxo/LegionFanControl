@echo off
rem Launcher: runs Probe/Powershell/FanWmiV2.ps1 with elevation (triggers one UAC prompt).
setlocal
set "DIR=%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath powershell.exe -Verb RunAs -ArgumentList '-NoProfile -ExecutionPolicy Bypass -File \"%DIR%..\Probe\Powershell\FanWmiV2.ps1\"'"
endlocal
