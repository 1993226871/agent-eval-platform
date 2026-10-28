@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-platform.ps1" -Action stop %*
set "result=%errorlevel%"
pause
exit /b %result%
