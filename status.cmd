@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-platform.ps1" -Action status %*
set "result=%errorlevel%"
pause
exit /b %result%
