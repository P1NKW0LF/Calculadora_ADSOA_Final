@echo off
rem Pruebas automatizadas de los 8 escenarios (sin ventanas). La evidencia queda en logs\pruebas.txt
cd /d "%~dp0"
if not exist lib\ads-core.jar call compilar.bat
call detener.bat >nul 2>nul
java -Dads.consola=false -cp lib\ads-core.jar ads.pruebas.Escenarios
pause
