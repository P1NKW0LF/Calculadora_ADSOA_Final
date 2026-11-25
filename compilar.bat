@echo off
rem Compila los programas base (lib\ads-core.jar) y cada microservicio como un .jar independiente (genes\).
setlocal enabledelayedexpansion
cd /d "%~dp0"
where javac >nul 2>nul || (echo No se encontro javac. Instale un JDK 17 o superior y agreguelo al PATH. & pause & exit /b 1)
if exist build rmdir /s /q build
if exist lib rmdir /s /q lib
if exist genes rmdir /s /q genes
mkdir build\core lib genes
echo == Compilando programas base (nodo, celulas, gestor) -^> lib\ads-core.jar
javac --release 17 -encoding UTF-8 -d build\core -sourcepath src\core src\core\ads\nodo\Nodo.java src\core\ads\celula\servidor\CelulaServidor.java src\core\ads\celula\cliente\CelulaCliente.java src\core\ads\gestor\GestorServicios.java src\core\ads\herramientas\FirmarGenes.java src\core\ads\herramientas\Verificar.java src\core\ads\pruebas\Escenarios.java || goto error
jar --create --file lib\ads-core.jar -C build\core . || goto error
echo == Compilando microservicios (genes) -^> genes\*.jar
echo    (el programa base NO los incluye: se cargan desde disco en tiempo de ejecucion)
for /d %%G in (src\genes\*) do (
  set "N=%%~nxG"
  mkdir "build\genes\!N!"
  for /r "%%G" %%F in (*.java) do (
    javac --release 17 -encoding UTF-8 -cp lib\ads-core.jar -sourcepath "%%G" -d "build\genes\!N!" "%%F" || goto error
  )
  copy /y "%%G\gen.properties" "build\genes\!N!\" >nul
  jar --create --file "genes\!N!.jar" -C "build\genes\!N!" . || goto error
)
echo == Registrando huellas de integridad (SHA-256)
java -cp lib\ads-core.jar ads.herramientas.FirmarGenes genes || goto error
echo Listo. Ejecute iniciar-demo.bat
exit /b 0
:error
echo ERROR de compilacion.
pause
exit /b 1
