# Calculadora distribuida ADS — Proyecto del Tercer Parcial (Java)

El sistema no tiene unidad central, nodo maestro ni registro único. Lo forman:

- **Campo de Datos**: nodos enlazados en malla que difunden cada mensaje a todos, con protección contra ciclos.
- **Células cliente**: calculadoras con interfaz gráfica.
- **Células servidor**: su funcionalidad vive en **genes** (microservicios `.jar` en disco que se cargan en tiempo de ejecución).
- **Gestor de Servicios**: herramienta administrativa opcional.

---

## Requisitos

- **JDK 17 o superior** (`javac` y `java` en el PATH). Probado con OpenJDK 21.
- La demostración completa son 25 procesos Java (4 nodos, 16 células servidor, 4 calculadoras y el gestor).

## Inicio rápido

| Paso                                    | Windows                  | Linux / macOS             |
| --------------------------------------- | ------------------------ | ------------------------- |
| 1. Compilar (base + genes)              | `compilar.bat`           | `./compilar.sh`           |
| 2. Arrancar la demostración             | `iniciar-demo.bat`       | `./iniciar-demo.sh`       |
| 2b. Arrancar con células **sin genes**  | `iniciar-demo-vacio.bat` | `./iniciar-demo.sh vacio` |
| 3. Detener todo (incluye clones)        | `detener.bat`            | `./detener.sh`            |
| Pruebas automáticas de los 8 escenarios | `probar.bat`             | `./probar.sh`             |

En modo **vacío** las 16 células servidor arrancan sin microservicios. En el Gestor de Servicios se oprime
**Despliegue inicial** y el gestor asigna, según la topología, SUMA, RESTA, MULTIPLICACION y DIVISION a las
cuatro células de cada nodo.

Otras formas de detener: **Apagar sistema** en el Gestor (apagado ordenado que no provoca clonaciones) o `detener`.

## Qué ventanas aparecen

| Ventana                    | Para qué sirve en la demostración                                                                                                                                                                                                                                                       |
| -------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Nodo N1…N4**             | Enlaces con los demás nodos, células conectadas y contadores.                                                                                                                                                                                                                           |
| **Célula servidor S-Nx-k** | Genes activos (CC y versión), ACK enviados y bitácora de carga. Botones **Silenciar ACK**, **Simular falla** y **Retirar**. **Cerrar la ventana equivale a una falla abrupta.** Los clones aparecen con fondo amarillo en la posición de la célula que reemplazan.                      |
| **Calculadora GUI-k**      | Teclado, **Min. # de ACK**, **Ráfaga (5)**, **Ráfaga en todas las GUI**, tabla _Mis eventos_ y el detalle de cada evento (folio, firma, CC, ACK, ausentes, ejecutora, huella de ida y vuelta, fases con tiempos). Cada tecla de operación muestra cuántas células vivas tienen ese gen. |
| **Gestor de Servicios**    | Mapa de la topología, alta/baja/sustitución de genes, disponibilidad por servicio, deudas de recuperación, catálogo de genes en disco, comandos con folio y bitácora del Campo de Datos.                                                                                                |

## Estructura del proyecto

```
config/ads.properties          configuración (nodos, tiempos, límites anti-cáncer)
src/core/ads/                  programas base (lib/ads-core.jar)
  api/Microservicio.java         contrato que implementa cada gen
  comun/                         Mensaje, Json, CC (Códigos de Contenido), Config, Bitacora, Procesos, Ventanas
  red/Enlace.java                enlace TCP: una línea JSON por mensaje
  nodo/Nodo.java                 nodo del Campo de Datos (malla, broadcast, anti-ciclos)
  celula/Celula.java             base de toda célula: conexión con tolerancia a fallas, latidos
  celula/Directorio.java         vista distribuida de la topología y detección de fallas
  celula/servidor/               CelulaServidor, GestorGenes (carga dinámica), Autorrecuperacion
  celula/cliente/                CelulaCliente (foliado), EventoSolicitud, VentanaCalculadora
  gestor/                        GestorServicios, VentanaGestor, PanelMapa
  herramientas/                  FirmarGenes (SHA-256 de los genes), Verificar
  pruebas/Escenarios.java        pruebas automatizadas de los 8 escenarios
src/genes/<gen>-<versión>/     cada microservicio es un proyecto aparte con su gen.properties
genes/                         repositorio en disco: <gen>.jar + <gen>.jar.sha256 (lo genera compilar)
logs/                          bitácora de cada proceso (logs/<id>.log) y evidencia de pruebas
```

`ads-core.jar` **no contiene** la lógica de ninguna operación. Los genes se compilan por separado, no están en el
classpath y se cargan desde `genes/` con un `URLClassLoader` propio.

| Gen            | Versión | CC  | Descripción                                                                            |
| -------------- | ------- | --- | -------------------------------------------------------------------------------------- |
| SUMA           | 1.0     | 101 | aritmética `double` (0.1 + 0.2 = 0.30000000000000004)                                  |
| SUMA           | 2.0     | 101 | `BigDecimal` exacto (0.1 + 0.2 = 0.3): sirve para demostrar la sustitución en caliente |
| RESTA          | 1.0     | 102 |                                                                                        |
| MULTIPLICACION | 1.0     | 103 |                                                                                        |
| DIVISION       | 1.0     | 104 | rechaza la división entre cero                                                         |
| POTENCIA       | 1.0     | 105 | operación nueva: la calculadora la descubre y crea su botón sola                       |

Para agregar un gen nuevo:

1. Cree `src/genes/<nombre>-<versión>/` con un `gen.properties` y una clase que implemente `ads.api.Microservicio`.
2. Vuelva a ejecutar `compilar`.

## Códigos de Contenido

| Rango | CC           | Mensajes                                                                                                                                                        |
| ----- | ------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 0xx   | 1–4          | Conexión: HOLA_NODO, HOLA_CELULA, BIENVENIDA, RECHAZO (sólo en el enlace, no se difunden)                                                                       |
| 1xx   | 101–105      | Solicitudes de operación. El CC lo declara cada gen.                                                                                                            |
| 2xx   | 201–204      | Foliado: ACK, EVENTO_CONFIRMADO, RESULTADO, EVENTO_FIN                                                                                                          |
| 3xx   | 301–306      | Membresía y topología: LATIDO_CELULA, LATIDO_NODO, CELULA_CONECTADA, CELULA_DESCONECTADA, CELULA_BAJA, CONSULTA_TOPOLOGIA                                       |
| 4xx   | 401–409, 499 | Gestión de servicios: ADMIN_ALTA, ADMIN_BAJA, ADMIN_MODIFICAR, ADMIN_RESPUESTA, ADMIN_SILENCIAR, ADMIN_FALLA, ADMIN_RETIRAR, SOLICITUD_GEN, GEN_BINARIO, APAGAR |
| 5xx   | 501–504      | Autorrecuperación: FALLA_DETECTADA, CLON_RESERVA, CLON_CANCELA, CLON_LISTO                                                                                      |
| 6xx   | 601          | DEMO_RAFAGA (ráfaga sincronizada entre calculadoras)                                                                                                            |

## Configuración (`config/ads.properties`)

| Clave                                                         | Valor                 | Efecto                                                                       |
| ------------------------------------------------------------- | --------------------- | ---------------------------------------------------------------------------- |
| `nodos`                                                       | `N1@localhost:5001,…` | nodos conocidos de antemano; los nuevos se descubren solos                   |
| `evento.minAck`                                               | 2                     | Min. # de ACK por defecto (cada calculadora puede cambiarlo)                 |
| `evento.timeoutAck.ms` / `evento.timeoutResultado.ms`         | 1000 / 2500           | esperas del protocolo de foliado                                             |
| `latido.intervalo.ms` / `latido.timeout.ms`                   | 1500 / 5000           | latidos y detección de fallas                                                |
| `desconexion.gracia.ms`                                       | 2000                  | tiempo para que una célula reaparezca en otro nodo antes de darla por muerta |
| `clonacion.confirmacion.ms` / `clonacion.paso.ms`             | 2500 / 3000           | espera antes de clonar y relevo entre candidatas                             |
| `clonacion.maxGeneracion`                                     | 5                     | telómeros: límite de clones de clones                                        |
| `clonacion.maxInstanciasPorServicio` / `clonacion.maxCelulas` | 8 / 40                | topes anti-cáncer                                                            |

## Varias máquinas

1. En `config/ads.properties` de todas las máquinas ponga las IP reales, por ejemplo
   `nodos=N1@192.168.1.10:5001,N2@192.168.1.11:5002,…`.
2. Arranque cada nodo con su IP: `java -cp lib/ads-core.jar ads.nodo.Nodo --id N1 --puerto 5001 --host 192.168.1.10`.
   Con `--host` distinto de localhost, el nodo escucha en todas las interfaces.
3. Las células se conectan con `--nodo IP:puerto`.

Si una máquina no tiene un gen en su carpeta `genes/`, la célula lo pide por el Campo de Datos (SOLICITUD_GEN) y
cualquier célula o gestor que lo tenga responde con el `.jar` (GEN_BINARIO). La célula verifica el SHA-256, lo guarda
en disco y lo carga.

## Pruebas automatizadas

`probar` levanta 4 nodos y 16 células servidor sin ventanas, actúa como dos calculadoras y como gestor, y verifica
**38 condiciones** de los 8 escenarios del documento, además de un escenario extra de falsa alarma y apoptosis. La
salida completa (trazas con folio, huella y ACK) queda en `logs/pruebas.txt` y se puede usar como evidencia en el
reporte. Tarda alrededor de un minuto y medio. En `docs/evidencia-pruebas.txt` hay una corrida de referencia y en
`docs/capturas/` tres pantallas de la demostración con interfaces.
