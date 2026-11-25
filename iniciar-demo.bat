@echo off
rem Configuracion de demostracion: 4 nodos en malla completa, 16 celulas servidor (una por operacion
rem en cada nodo), 4 calculadoras (una por nodo) y el Gestor de Servicios. Cada uno es un proceso aparte.
rem   iniciar-demo.bat          celulas servidor con su gen ya cargado desde disco
rem   iniciar-demo.bat vacio    celulas servidor SIN genes (use "Despliegue inicial" en el Gestor)
setlocal
cd /d "%~dp0"
if not exist lib\ads-core.jar call compilar.bat
if not exist lib\ads-core.jar exit /b 1
java -cp lib\ads-core.jar ads.herramientas.Verificar >nul 2>nul || (echo Se requiere Java 17 o superior en el PATH. Version encontrada: & java -version & pause & exit /b 1)
if not exist logs mkdir logs
set "MODO=%~1"
set "JW=javaw"
where javaw >nul 2>nul || set "JW=java"
set "J=%JW% -Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8 -Dads.consola=false -cp lib\ads-core.jar"
echo Nodos del Campo de Datos N1..N4
start "" %J% ads.nodo.Nodo --id N1 --puerto 5001 --pos "0.40,0.77"
start "" %J% ads.nodo.Nodo --id N2 --puerto 5002 --pos "0.55,0.77"
start "" %J% ads.nodo.Nodo --id N3 --puerto 5003 --pos "0.70,0.77"
start "" %J% ads.nodo.Nodo --id N4 --puerto 5004 --pos "0.85,0.77"
ping -n 3 127.0.0.1 >nul
echo Celulas servidor
call :servidor N1 5001 1 SUMA:1.0 0.40 0.00
call :servidor N1 5001 2 RESTA:1.0 0.40 0.19
call :servidor N1 5001 3 MULTIPLICACION:1.0 0.40 0.38
call :servidor N1 5001 4 DIVISION:1.0 0.40 0.57
call :servidor N2 5002 1 SUMA:1.0 0.55 0.00
call :servidor N2 5002 2 RESTA:1.0 0.55 0.19
call :servidor N2 5002 3 MULTIPLICACION:1.0 0.55 0.38
call :servidor N2 5002 4 DIVISION:1.0 0.55 0.57
call :servidor N3 5003 1 SUMA:1.0 0.70 0.00
call :servidor N3 5003 2 RESTA:1.0 0.70 0.19
call :servidor N3 5003 3 MULTIPLICACION:1.0 0.70 0.38
call :servidor N3 5003 4 DIVISION:1.0 0.70 0.57
call :servidor N4 5004 1 SUMA:1.0 0.85 0.00
call :servidor N4 5004 2 RESTA:1.0 0.85 0.19
call :servidor N4 5004 3 MULTIPLICACION:1.0 0.85 0.38
call :servidor N4 5004 4 DIVISION:1.0 0.85 0.57
ping -n 3 127.0.0.1 >nul
echo Calculadoras GUI-1..GUI-4
start "" %J% ads.celula.cliente.CelulaCliente --id GUI-1 --nodo localhost:5001 --pos "0.00,0.00"
start "" %J% ads.celula.cliente.CelulaCliente --id GUI-2 --nodo localhost:5002 --pos "0.03,0.06"
start "" %J% ads.celula.cliente.CelulaCliente --id GUI-3 --nodo localhost:5003 --pos "0.06,0.12"
start "" %J% ads.celula.cliente.CelulaCliente --id GUI-4 --nodo localhost:5004 --pos "0.09,0.18"
ping -n 2 127.0.0.1 >nul
echo Gestor de Servicios
start "" %JW% -Xmx128m -Dfile.encoding=UTF-8 -Dads.consola=false -cp lib\ads-core.jar ads.gestor.GestorServicios --nodo localhost:5001 --pos "0.02,0.03"
echo Listo. Para detener todo: detener.bat  (o "Apagar sistema" en el Gestor)
exit /b 0

:servidor
if /i "%MODO%"=="vacio" (
  start "" %J% ads.celula.servidor.CelulaServidor --id S-%1-%3 --nodo localhost:%2 --pos "%5,%6"
) else (
  start "" %J% ads.celula.servidor.CelulaServidor --id S-%1-%3 --nodo localhost:%2 --genes %4 --pos "%5,%6"
)
exit /b 0
