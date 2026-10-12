@echo off
rem 以管理员身份运行转速根因探查脚本 v3
rem 双击本文件并在 UAC 弹窗中点击「是」
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell -Verb RunAs -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-File','%~dp0..\Probe\Powershell\FanRpmV3.ps1' -Wait"
echo.
echo 探查完成，结果见 Probe\Powershell\probe-result-rpm-v3.txt
pause
