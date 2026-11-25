#!/usr/bin/env bash
# Configuración de demostración: 4 nodos en malla completa, 16 células servidor (una por operación
# en cada nodo), 4 calculadoras (una por nodo) y el Gestor de Servicios. Cada uno es un proceso aparte.
#   ./iniciar-demo.sh          células servidor con su gen ya cargado desde disco
#   ./iniciar-demo.sh vacio    células servidor SIN genes (use "Despliegue inicial" en el Gestor)
cd "$(dirname "$0")"
[ -f lib/ads-core.jar ] || ./compilar.sh || exit 1
java -cp lib/ads-core.jar ads.herramientas.Verificar >/dev/null 2>&1 || { echo "Se requiere Java 17 o superior."; java -version; exit 1; }
mkdir -p logs
MODO="${1:-con-genes}"
J=(java -Xmx64m -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8 -Dads.consola=false -cp lib/ads-core.jar)
X=(0.40 0.55 0.70 0.85)
Y=(0.00 0.19 0.38 0.57)
OPS=(SUMA:1.0 RESTA:1.0 MULTIPLICACION:1.0 DIVISION:1.0)

echo "Nodos del Campo de Datos N1..N4"
for i in 0 1 2 3; do
  n=$((i+1))
  "${J[@]}" ads.nodo.Nodo --id "N$n" --puerto "500$n" --pos "${X[$i]},0.77" > "logs/N$n.salida.log" 2>&1 &
done
sleep 2

echo "Células servidor (modo: $MODO)"
for i in 0 1 2 3; do
  n=$((i+1))
  for k in 0 1 2 3; do
    id="S-N$n-$((k+1))"
    if [ "$MODO" = "vacio" ]; then
      "${J[@]}" ads.celula.servidor.CelulaServidor --id "$id" --nodo "localhost:500$n" --pos "${X[$i]},${Y[$k]}" > "logs/$id.salida.log" 2>&1 &
    else
      "${J[@]}" ads.celula.servidor.CelulaServidor --id "$id" --nodo "localhost:500$n" --genes "${OPS[$k]}" --pos "${X[$i]},${Y[$k]}" > "logs/$id.salida.log" 2>&1 &
    fi
  done
done
sleep 2

echo "Calculadoras GUI-1..GUI-4"
GX=(0.00 0.03 0.06 0.09)
GY=(0.00 0.06 0.12 0.18)
for i in 0 1 2 3; do
  n=$((i+1))
  "${J[@]}" ads.celula.cliente.CelulaCliente --id "GUI-$n" --nodo "localhost:500$n" --pos "${GX[$i]},${GY[$i]}" > "logs/GUI-$n.salida.log" 2>&1 &
done
sleep 1

echo "Gestor de Servicios"
java -Xmx128m -Dfile.encoding=UTF-8 -Dads.consola=false -cp lib/ads-core.jar ads.gestor.GestorServicios --nodo localhost:5001 --pos 0.02,0.03 > logs/GESTOR.salida.log 2>&1 &
echo "Listo. Para detener todo: ./detener.sh  (o 'Apagar sistema' en el Gestor)"
