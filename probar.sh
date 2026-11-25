#!/usr/bin/env bash
# Pruebas automatizadas de los 8 escenarios (sin ventanas). La evidencia queda en logs/pruebas.txt
cd "$(dirname "$0")"
[ -f lib/ads-core.jar ] || ./compilar.sh || exit 1
./detener.sh >/dev/null 2>&1
java -Dads.consola=false -cp lib/ads-core.jar ads.pruebas.Escenarios
