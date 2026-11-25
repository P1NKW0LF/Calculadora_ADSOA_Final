@echo off
rem Detiene todos los procesos del sistema ADS (nodos, celulas, clones y gestor).
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.CommandLine -like '*ads-core.jar*' -and $_.ProcessId -ne $PID } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }"
echo Procesos del sistema ADS detenidos.
ping -n 3 127.0.0.1 >nul
