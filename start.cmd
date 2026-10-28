@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\local-platform.ps1" -Action start %*
set "result=%errorlevel%"
pause
exit /b %result%
