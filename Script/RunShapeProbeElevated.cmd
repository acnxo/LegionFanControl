@echo off
rem 以管理员身份运行「取值结构确认」探查脚本
rem 双击本文件并在 UAC 弹窗中点击「是」
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','%~dp0..\Probe\Powershell\FanShape.ps1' -Wait"
echo.
echo 探查完成，结果见 Probe\Powershell\probe-result-shape.txt
pause
