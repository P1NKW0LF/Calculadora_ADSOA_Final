#!/usr/bin/env bash
# Compila los programas base (lib/ads-core.jar) y cada microservicio como un .jar independiente (genes/).
set -euo pipefail
cd "$(dirname "$0")"

command -v javac >/dev/null || { echo "No se encontró javac. Instale un JDK 17 o superior."; exit 1; }

rm -rf build lib genes
mkdir -p build/core lib genes

echo "== Compilando programas base (nodo, células, gestor) -> lib/ads-core.jar"
find src/core -name '*.java' > build/fuentes-core.txt
javac --release 17 -encoding UTF-8 -d build/core @build/fuentes-core.txt
jar --create --file lib/ads-core.jar -C build/core .

echo "== Compilando microservicios (genes) -> genes/*.jar"
echo "   (el programa base NO los incluye: se cargan desde disco en tiempo de ejecución)"
for dir in src/genes/*/; do
  nombre="$(basename "$dir")"
  mkdir -p "build/genes/$nombre"
  find "$dir" -name '*.java' > "build/fuentes-$nombre.txt"
  javac --release 17 -encoding UTF-8 -cp lib/ads-core.jar -d "build/genes/$nombre" @"build/fuentes-$nombre.txt"
  cp "$dir/gen.properties" "build/genes/$nombre/"
  jar --create --file "genes/$nombre.jar" -C "build/genes/$nombre" .
done

echo "== Registrando huellas de integridad (SHA-256)"
java -cp lib/ads-core.jar ads.herramientas.FirmarGenes genes
echo "Listo. Ejecute ./iniciar-demo.sh"
