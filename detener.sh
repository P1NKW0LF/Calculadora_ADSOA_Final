#!/usr/bin/env bash
# Detiene todos los procesos del sistema ADS (nodos, células, clones y gestor).
pkill -f "ads-core.jar" && echo "Procesos del sistema ADS detenidos." || echo "No había procesos del sistema ADS."
