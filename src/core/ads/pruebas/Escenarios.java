package ads.pruebas;

import ads.celula.Directorio.InfoCelula;
import ads.celula.cliente.CelulaCliente;
import ads.celula.cliente.EventoSolicitud;
import ads.comun.Args;
import ads.comun.Config;
import ads.comun.DirNodo;
import ads.comun.Procesos;
import ads.comun.Util;
import ads.gestor.GestorServicios;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Pruebas automatizadas de los 8 escenarios del tercer parcial (sin interfaz gráfica).
 * Levanta 4 nodos y 16 células servidor como procesos independientes, actúa como dos calculadoras
 * y como Gestor de Servicios, y verifica cada requisito. Deja la evidencia en logs/pruebas.txt.
 *
 * Uso: java -cp lib/ads-core.jar ads.pruebas.Escenarios
 */
public final class Escenarios {

    private final Config cfg;
    private final List<Process> procesos = new ArrayList<>();
    private final Map<String, Process> porId = new LinkedHashMap<>();
    private final PrintStream out;
    private CelulaCliente a;
    private CelulaCliente b;
    private GestorServicios g;
    private int aprobadas;
    private int fallidas;
    private final List<String> resumen = new ArrayList<>();

    private Escenarios(Config cfg, PrintStream out) {
        this.cfg = cfg;
        this.out = out;
    }

    public static void main(String[] argv) throws Exception {
        Args args = new Args(argv);
        Config cfg = Config.cargar(args.get("config", "config/ads.properties"));
        Files.createDirectories(cfg.dirLogs());
        Path evid = cfg.dirLogs().resolve("pruebas.txt");
        try (PrintStream archivo = new PrintStream(Files.newOutputStream(evid), true, StandardCharsets.UTF_8)) {
            PrintStream doble = new PrintStream(new java.io.OutputStream() {
                @Override
                public void write(int x) {
                    System.out.write(x);
                    archivo.write(x);
                }

                @Override
                public void write(byte[] buf, int off, int len) {
                    System.out.write(buf, off, len);
                    archivo.write(buf, off, len);
                }
            }, true, StandardCharsets.UTF_8);
            Escenarios e = new Escenarios(cfg, doble);
            int codigo = 1;
            try {
                codigo = e.ejecutar() ? 0 : 1;
            } catch (Throwable t) {
                t.printStackTrace(doble);
                codigo = 2;
            } finally {
                e.limpiar();
                doble.flush();
            }
            System.out.println("\nEvidencia guardada en " + evid.toAbsolutePath());
            System.exit(codigo);
        }
    }

    // ------------------------------------------------------------------ infraestructura

    private void titulo(String t) {
        out.println();
        out.println("=".repeat(96));
        out.println(t);
        out.println("=".repeat(96));
    }

    private void verificar(String requisito, boolean ok, String evidencia) {
        out.println((ok ? "  [OK]    " : "  [FALLA] ") + requisito + (evidencia == null ? "" : "  ->  " + evidencia));
        if (ok) {
            aprobadas++;
        } else {
            fallidas++;
        }
        resumen.add((ok ? "OK    " : "FALLA ") + requisito);
    }

    private static boolean esperar(BooleanSupplier cond, long ms) {
        long fin = Util.ahora() + ms;
        while (Util.ahora() < fin) {
            if (cond.getAsBoolean()) {
                return true;
            }
            Util.dormir(100);
        }
        return cond.getAsBoolean();
    }

    private Process lanzar(String clase, String id, String... args) throws IOException {
        List<String> l = new ArrayList<>(List.of(args));
        l.add("--sin-gui");
        l.add("--config");
        l.add(cfg.archivo().toString());
        Process p = Procesos.lanzar(clase, l, cfg.dirLogs(), id);
        procesos.add(p);
        porId.put(id, p);
        return p;
    }

    private EventoSolicitud operar(CelulaCliente c, String op, String x, String y) {
        Object[] o = c.operacion(op);
        if (o == null) {
            o = "POTENCIA".equals(op) ? new Object[]{"POTENCIA", 105, "^"} : new Object[]{op, 199, "?"};
        }
        EventoSolicitud ev = c.solicitar((Integer) o[1], (String) o[0], (String) o[2], List.of(x, y));
        esperar(ev::cerrado, 8000);
        return ev;
    }

    private void traza(EventoSolicitud ev) {
        for (String l : ev.traza()) {
            out.println("          " + l);
        }
    }

    private int proveedores(String gen) {
        int n = 0;
        for (InfoCelula c : g.directorio.servidoresVivos()) {
            if (c.tieneGen(gen)) {
                n++;
            }
        }
        return n;
    }

    private InfoCelula celulaCon(String nodo, String gen) {
        for (InfoCelula c : g.directorio.servidoresVivos()) {
            if (nodo.equals(c.nodo) && c.tieneGen(gen) && c.reemplaza == null) {
                return c;
            }
        }
        return null;
    }

    private InfoCelula clonDe(String original) {
        for (InfoCelula c : g.directorio.servidoresVivos()) {
            if (original.equals(c.reemplaza)) {
                return c;
            }
        }
        return null;
    }

    private void matar(String id) {
        Process p = porId.get(id);
        if (p != null) {
            p.destroyForcibly();
            return;
        }
        InfoCelula c = g.directorio.celula(id);
        if (c != null && c.pid > 0) {
            ProcessHandle.of(c.pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private static void senal(long pid, String sig) {
        try {
            new ProcessBuilder("kill", "-" + sig, String.valueOf(pid)).start().waitFor(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // no disponible en este sistema
        }
    }

    // ------------------------------------------------------------------ escenarios

    private boolean ejecutar() throws Exception {
        titulo("PREPARACIÓN: 4 nodos en malla completa + 16 células servidor (una por operación en cada nodo)");
        List<DirNodo> nodos = cfg.nodos().subList(0, 4);
        for (DirNodo d : nodos) {
            lanzar("ads.nodo.Nodo", d.id(), "--id", d.id(), "--puerto", String.valueOf(d.puerto()), "--host", d.host());
        }
        Util.dormir(1500);
        for (DirNodo d : nodos) {
            for (int i = 0; i < 4; i++) {
                String id = "S-" + d.id() + "-" + (i + 1);
                lanzar("ads.celula.servidor.CelulaServidor", id, "--id", id, "--nodo", d.direccion());
            }
        }
        g = new GestorServicios("GESTOR", cfg, nodos.get(1));
        a = new CelulaCliente("GUI-1", cfg, nodos.get(0));
        b = new CelulaCliente("GUI-3", cfg, nodos.get(2));
        g.arrancar();
        a.arrancar();
        b.arrancar();
        boolean vacias = esperar(() -> {
            List<InfoCelula> s = g.directorio.servidoresVivos();
            return s.size() >= 16 && s.stream().allMatch(c -> c.conLatido && c.genes.isEmpty());
        }, 60000);
        verificar("Las 16 células servidor arrancan SIN microservicios activos", vacias,
                g.directorio.servidoresVivos().size() + " células, todas sin genes");
        List<GestorServicios.Comando> despliegue = g.despliegueInicial();
        int okDesp = 0;
        for (GestorServicios.Comando c : despliegue) {
            if (Boolean.TRUE.equals(c.futuro.get(20, TimeUnit.SECONDS).ok)) {
                okDesp++;
            }
        }
        out.println("          " + despliegue.size() + " comandos ADMIN_ALTA, p. ej. " + despliegue.get(0).folio + ": "
                + despliegue.get(0).descripcion() + " -> " + despliegue.get(0).detalle);
        verificar("Despliegue inicial desde el Gestor guiado por la topología (16 altas)", okDesp == 16,
                okDesp + "/16 confirmadas por ACK y ADMIN_RESPUESTA");
        boolean listo = esperar(() -> g.directorio.servidoresVivos().size() >= 16 && proveedores("SUMA") == 4
                && proveedores("DIVISION") == 4 && a.directorio.proveedores(101).size() == 4
                && b.directorio.proveedores(104).size() == 4, 60000);
        verificar("16 células servidor conectadas y anunciando sus genes", listo,
                g.directorio.servidoresVivos().size() + " servidores vivos");
        boolean malla = esperar(() -> {
            if (g.directorio.nodos().size() < 4) {
                return false;
            }
            for (var n : g.directorio.nodos()) {
                if (n.vecinos.size() < 3) {
                    return false;
                }
            }
            return true;
        }, 15000);
        StringBuilder sb = new StringBuilder();
        for (var n : g.directorio.nodos()) {
            sb.append(n.id).append("->").append(n.vecinos).append(' ');
        }
        verificar("Campo de Datos en malla completa (cada nodo con 3 vecinos)", malla, sb.toString().trim());
        if (!listo) {
            return false;
        }
        Util.dormir(7000); // madurez de las células para la autorrecuperación

        // ---------------------------------------------------------------- 1
        titulo("ESCENARIO 1 — Operación normal con foliado (folio, huella, CC, ACK, respuesta)");
        long ajenosAntes = b.ajenos.get();
        EventoSolicitud e1 = operar(a, "SUMA", "5", "3");
        traza(e1);
        verificar("La solicitud lleva folio y firma (huella de origen)", e1.folio.startsWith("GUI-1-") && e1.firma.length() == 12,
                "folio " + e1.folio + ", firma " + e1.firma);
        verificar("Se recibieron ACK de las 4 células con el gen SUMA", e1.numAcks() == 4, e1.numAcks() + " ACK");
        verificar("Resultado correcto 5 + 3 = 8", e1.fase == EventoSolicitud.Fase.COMPLETADO && "8".equals(e1.valor),
                e1.fase + " " + e1.valor + " por " + e1.ejecutor);
        verificar("La huella registra la trayectoria GUI-1 → N1 → ... → ejecutora",
                !e1.huellaResultadoIda.isEmpty() && e1.huellaResultadoIda.get(0).equals("GUI-1")
                        && e1.huellaResultadoIda.contains("N1"),
                "ida " + String.join(" → ", e1.huellaResultadoIda) + " → " + e1.ejecutor
                        + " | vuelta " + String.join(" → ", e1.huellaResultadoVuelta) + " → GUI-1");
        Util.dormir(300);
        verificar("La otra GUI (GUI-3) NO muestra ese resultado: lo descarta como ajeno",
                b.eventos().isEmpty() && b.ajenos.get() > ajenosAntes,
                "GUI-3 tiene " + b.eventos().size() + " eventos propios y descartó " + (b.ajenos.get() - ajenosAntes)
                        + " mensajes ajenos");

        // ---------------------------------------------------------------- 2
        titulo("ESCENARIO 2 — Solicitudes simultáneas desde dos GUI");
        List<EventoSolicitud> deA = new ArrayList<>();
        List<EventoSolicitud> deB = new ArrayList<>();
        CountDownLatch salida = new CountDownLatch(1);
        Thread ta = new Thread(() -> {
            try {
                salida.await();
            } catch (InterruptedException ignored) {
                return;
            }
            deA.addAll(a.rafaga(10));
        });
        Thread tb = new Thread(() -> {
            try {
                salida.await();
            } catch (InterruptedException ignored) {
                return;
            }
            deB.addAll(b.rafaga(10));
        });
        ta.start();
        tb.start();
        salida.countDown();
        ta.join();
        tb.join();
        esperar(() -> deA.stream().allMatch(EventoSolicitud::cerrado) && deB.stream().allMatch(EventoSolicitud::cerrado), 15000);
        int correctasA = 0;
        int correctasB = 0;
        for (EventoSolicitud ev : deA) {
            correctasA += correcta(ev) ? 1 : 0;
        }
        for (EventoSolicitud ev : deB) {
            correctasB += correcta(ev) ? 1 : 0;
        }
        out.println("          GUI-1: " + resumenEventos(deA));
        out.println("          GUI-3: " + resumenEventos(deB));
        verificar("Las 20 operaciones simultáneas se completaron con el resultado correcto",
                correctasA == 10 && correctasB == 10, "GUI-1 " + correctasA + "/10, GUI-3 " + correctasB + "/10");
        boolean aislado = a.eventos().stream().allMatch(ev -> ev.folio.startsWith("GUI-1-"))
                && b.eventos().stream().allMatch(ev -> ev.folio.startsWith("GUI-3-"));
        verificar("Cada GUI sólo tiene eventos de sus propios folios", aislado,
                "GUI-1 descartó " + a.ajenos.get() + " ajenos, GUI-3 descartó " + b.ajenos.get());

        // ---------------------------------------------------------------- 3
        titulo("ESCENARIO 3 — Min. # de ACK y tolerancia a fallas");
        InfoCelula s4 = celulaCon("N4", "SUMA");
        InfoCelula s3 = celulaCon("N3", "SUMA");
        InfoCelula s2 = celulaCon("N2", "SUMA");
        g.silenciar(s4.id, true).futuro.get(10, TimeUnit.SECONDS);
        a.minAck = 3;
        EventoSolicitud e3 = operar(a, "SUMA", "7", "8");
        traza(e3);
        verificar("Con una célula sin responder, el evento continúa (3 ACK ≥ Min. 3)",
                e3.fase == EventoSolicitud.Fase.COMPLETADO && "15".equals(e3.valor) && e3.numAcks() == 3,
                e3.numAcks() + " ACK, resultado " + e3.valor);
        verificar("El sistema identifica la ausencia de la célula silenciada", e3.ausentes.contains(s4.id),
                "ausentes " + e3.ausentes);
        g.silenciar(s3.id, true).futuro.get(10, TimeUnit.SECONDS);
        g.silenciar(s2.id, true).futuro.get(10, TimeUnit.SECONDS);
        EventoSolicitud e3b = operar(a, "SUMA", "1", "1");
        traza(e3b);
        verificar("Con sólo 1 ACK < Min. 3 el evento se RECHAZA", e3b.fase == EventoSolicitud.Fase.RECHAZADO,
                e3b.fase + ": " + e3b.error);
        for (InfoCelula x : List.of(s2, s3, s4)) {
            g.silenciar(x.id, false).futuro.get(10, TimeUnit.SECONDS);
        }
        a.minAck = 2;

        // ---------------------------------------------------------------- 4
        titulo("ESCENARIO 4 — Carga dinámica de un microservicio en una célula sin genes");
        String nueva = g.nuevaCelulaServidor("N1", true);
        boolean aparece = esperar(() -> {
            InfoCelula c = g.directorio.celula(nueva);
            return c != null && c.viva() && c.conLatido;
        }, 20000);
        InfoCelula cn = g.directorio.celula(nueva);
        verificar("La célula " + nueva + " arranca sin microservicios", aparece && cn.genes.isEmpty(),
                aparece ? "genes: " + cn.genesTexto() : "no apareció");
        a.minAck = 1;
        EventoSolicitud e4a = operar(a, "POTENCIA", "2", "10");
        verificar("Antes del alta nadie atiende POTENCIA (CC 105)", e4a.fase == EventoSolicitud.Fase.RECHAZADO,
                e4a.fase + ": " + e4a.error);
        GestorServicios.Comando alta = g.alta(nueva, "POTENCIA", null).futuro.get(15, TimeUnit.SECONDS);
        verificar("Alta de POTENCIA desde el Gestor (ACK + respuesta)", Boolean.TRUE.equals(alta.ok) && alta.ack,
                alta.folio + ": " + alta.detalle);
        esperar(() -> a.directorio.proveedores(105).size() == 1, 8000);
        EventoSolicitud e4 = operar(a, "POTENCIA", "2", "10");
        traza(e4);
        verificar("La célula adquiere la funcionalidad sin modificar su programa base: 2^10 = 1024",
                e4.fase == EventoSolicitud.Fase.COMPLETADO && "1024".equals(e4.valor) && nueva.equals(e4.ejecutor),
                e4.valor + " por " + e4.ejecutor);
        String logNueva = Files.readString(cfg.dirLogs().resolve(nueva + ".log"), StandardCharsets.UTF_8);
        verificar("Bitácora de la célula: localizar, validar, cargar, autoprueba y activar",
                logNueva.contains("LOCALIZAR") && logNueva.contains("VALIDAR") && logNueva.contains("CARGAR")
                        && logNueva.contains("AUTOPRUEBA") && logNueva.contains("ACTIVAR"), "logs/" + nueva + ".log");

        // ---------------------------------------------------------------- 5
        titulo("ESCENARIO 5 — Baja y modificación de un servicio");
        GestorServicios.Comando baja = g.baja(nueva, "POTENCIA").futuro.get(15, TimeUnit.SECONDS);
        verificar("Baja de POTENCIA en " + nueva, Boolean.TRUE.equals(baja.ok), baja.detalle);
        esperar(() -> a.directorio.proveedores(105).isEmpty(), 8000);
        EventoSolicitud e5a = operar(a, "POTENCIA", "2", "3");
        verificar("Después de la baja la célula deja de atender el CC 105", e5a.fase == EventoSolicitud.Fase.RECHAZADO
                && e5a.numAcks() == 0, e5a.fase + ", " + e5a.numAcks() + " ACK");
        InfoCelula s1 = celulaCon("N1", "SUMA");
        EventoSolicitud e5b = operar(a, "SUMA", "0.1", "0.2");
        verificar("SUMA v1.0 (double): 0.1 + 0.2", e5b.valor != null && e5b.valor.startsWith("0.3000"),
                e5b.valor + " (" + e5b.genResultado + " v" + e5b.versionResultado + ")");
        GestorServicios.Comando mod = g.modificar(s1.id, "SUMA", "2.0").futuro.get(15, TimeUnit.SECONDS);
        verificar("Sustitución en caliente de SUMA v1.0 por v2.0 en " + s1.id, Boolean.TRUE.equals(mod.ok), mod.detalle);
        esperar(() -> {
            InfoCelula x = a.directorio.celula(s1.id);
            return x != null && x.gen("SUMA") != null && "2.0".equals(x.gen("SUMA").version);
        }, 8000);
        EventoSolicitud e5c = operar(a, "SUMA", "0.1", "0.2");
        traza(e5c);
        verificar("La nueva versión atiende: 0.1 + 0.2 = 0.3 con SUMA v2.0",
                "0.3".equals(e5c.valor) && "2.0".equals(e5c.versionResultado), e5c.valor + " por " + e5c.ejecutor);
        a.minAck = 2;

        // ---------------------------------------------------------------- 6
        titulo("ESCENARIO 6 — Falla de una célula y autorrecuperación por clonación");
        InfoCelula victima = celulaCon("N2", "SUMA");
        long t0 = Util.ahora();
        out.println("          Se mata abruptamente el proceso de " + victima.id + " (pid " + victima.pid + ")");
        matar(victima.id);
        boolean detectada = esperar(() -> {
            InfoCelula x = g.directorio.celula(victima.id);
            return x == null || !x.viva();
        }, 15000);
        long tDet = Util.ahora() - t0;
        verificar("Se detecta la falla de " + victima.id, detectada, "en " + tDet + " ms");
        boolean clonado = esperar(() -> clonDe(victima.id) != null && proveedores("SUMA") == 4, 40000);
        long tRec = Util.ahora() - t0;
        InfoCelula clon = clonDe(victima.id);
        verificar("Se genera una nueva célula con el gen SUMA que reemplaza a " + victima.id, clonado,
                clon == null ? "no apareció" : clon.id + " (padre " + clon.padre + ", generación " + clon.generacion
                        + ", nodo " + clon.nodo + ") en " + tRec + " ms");
        EventoSolicitud e6 = operar(a, "SUMA", "40", "2");
        verificar("El servicio sigue respondiendo con 4 proveedores", "42".equals(e6.valor) && e6.numAcks() == 4,
                e6.valor + " con " + e6.numAcks() + " ACK");

        // ---------------------------------------------------------------- 7
        titulo("ESCENARIO 7 — Prevención del 'cáncer': la clonación se detiene al recuperar el nivel requerido");
        int servidoresAntes = g.directorio.servidoresVivos().size();
        Util.dormir(15000);
        int servidoresDespues = g.directorio.servidoresVivos().size();
        verificar("Tras 15 s no se crearon células innecesarias", proveedores("SUMA") == 4 && servidoresAntes == servidoresDespues,
                "SUMA " + proveedores("SUMA") + "/4, servidores " + servidoresAntes + " → " + servidoresDespues);
        InfoCelula r1 = celulaCon("N3", "RESTA");
        InfoCelula r2 = celulaCon("N4", "RESTA");
        out.println("          Se matan a la vez " + r1.id + " y " + r2.id + " (dos réplicas de RESTA)");
        matar(r1.id);
        matar(r2.id);
        boolean dos = esperar(() -> clonDe(r1.id) != null && clonDe(r2.id) != null && proveedores("RESTA") == 4, 50000);
        verificar("Recuperación gradual: un clon por cada célula perdida", dos,
                (clonDe(r1.id) == null ? "-" : clonDe(r1.id).id) + ", " + (clonDe(r2.id) == null ? "-" : clonDe(r2.id).id));
        int antes = g.directorio.servidoresVivos().size();
        Util.dormir(12000);
        verificar("Después de recuperar RESTA 4/4 la clonación se detuvo",
                proveedores("RESTA") == 4 && g.directorio.servidoresVivos().size() == antes,
                "RESTA " + proveedores("RESTA") + ", servidores " + antes + " → " + g.directorio.servidoresVivos().size());

        // ---------------------------------------------------------------- 8
        titulo("ESCENARIO 8 — Cambio de topología durante la ejecución");
        String n5 = g.nuevoNodo(true);
        boolean n5ok = esperar(() -> {
            var n = g.directorio.nodo(n5);
            return n != null && n.vecinos.size() >= 4;
        }, 20000);
        verificar("Nuevo nodo " + n5 + " integrado a la malla", n5ok,
                g.directorio.nodo(n5) == null ? "no apareció" : n5 + " vecinos " + g.directorio.nodo(n5).vecinos);
        String enN5 = g.nuevaCelulaServidor(n5, true);
        boolean c5 = esperar(() -> {
            InfoCelula x = g.directorio.celula(enN5);
            return x != null && x.viva() && n5.equals(x.nodo);
        }, 20000);
        verificar("Nueva célula " + enN5 + " conectada a " + n5 + " y visible en la topología", c5, enN5);
        GestorServicios.Comando alta5 = g.alta(enN5, "MULTIPLICACION", null).futuro.get(15, TimeUnit.SECONDS);
        verificar("El gestor decide con la nueva topología: alta de MULTIPLICACION en " + enN5, Boolean.TRUE.equals(alta5.ok),
                alta5.detalle);
        GestorServicios.Comando ret = g.retirar(nueva).futuro.get(15, TimeUnit.SECONDS);
        Util.dormir(9000);
        InfoCelula xr = g.directorio.celula(nueva);
        verificar("Retiro ordenado de " + nueva + " sin provocar clonación", Boolean.TRUE.equals(ret.ok) && clonDe(nueva) == null
                && (xr == null || !xr.viva()), "estado " + (xr == null ? "purgada" : xr.estado));
        List<String> enN4 = new ArrayList<>();
        for (InfoCelula x : g.directorio.servidoresVivos()) {
            if ("N4".equals(x.nodo)) {
                enN4.add(x.id);
            }
        }
        int servAntes = g.directorio.servidoresVivos().size();
        out.println("          Se detiene abruptamente el nodo N4 (células conectadas: " + enN4 + ")");
        porId.get("N4").destroyForcibly();
        boolean migraron = esperar(() -> {
            for (String id : enN4) {
                InfoCelula x = g.directorio.celula(id);
                if (x == null || !x.viva() || "N4".equals(x.nodo)) {
                    return false;
                }
            }
            return true;
        }, 20000);
        StringBuilder mig = new StringBuilder();
        for (String id : enN4) {
            InfoCelula x = g.directorio.celula(id);
            mig.append(id).append("→").append(x == null ? "?" : x.nodo).append(' ');
        }
        verificar("Las células de N4 se reconectan solas a otro nodo (no hay nodo maestro)", migraron, mig.toString().trim());
        Util.dormir(6000);
        EventoSolicitud e8 = operar(a, "DIVISION", "84", "2");
        verificar("Sin clonaciones innecesarias y el servicio sigue funcionando",
                g.directorio.servidoresVivos().size() == servAntes && "42".equals(e8.valor),
                "servidores " + servAntes + " → " + g.directorio.servidoresVivos().size() + ", 84 ÷ 2 = " + e8.valor);

        // ---------------------------------------------------------------- extra
        if (!Util.esWindows()) {
            titulo("EXTRA — Falsa alarma y apoptosis (una célula congelada que regresa)");
            InfoCelula m1 = celulaCon("N1", "MULTIPLICACION");
            out.println("          Se congela (SIGSTOP) " + m1.id + " para que deje de latir");
            senal(m1.pid, "STOP");
            boolean clonM = esperar(() -> clonDe(m1.id) != null, 40000);
            verificar("La célula congelada se da por muerta y se clona", clonM,
                    clonDe(m1.id) == null ? "-" : clonDe(m1.id).id);
            senal(m1.pid, "CONT");
            out.println("          Se reanuda (SIGCONT) " + m1.id);
            boolean apop = esperar(() -> clonDe(m1.id) == null && g.directorio.estaViva(m1.id), 25000);
            verificar("El original regresa y su clon se retira solo (apoptosis): no queda exceso", apop,
                    "MULTIPLICACION " + proveedores("MULTIPLICACION"));
        }

        titulo("RESUMEN");
        for (String r : resumen) {
            out.println("  " + r);
        }
        out.println();
        out.println("  " + aprobadas + " verificaciones correctas, " + fallidas + " fallidas");
        return fallidas == 0;
    }

    private static boolean correcta(EventoSolicitud ev) {
        if (ev.fase != EventoSolicitud.Fase.COMPLETADO) {
            return false;
        }
        double x = Double.parseDouble(ev.operandos.get(0));
        double y = Double.parseDouble(ev.operandos.get(1));
        double r = switch (ev.operacion) {
            case "SUMA" -> x + y;
            case "RESTA" -> x - y;
            case "MULTIPLICACION" -> x * y;
            default -> x / y;
        };
        return Math.abs(Double.parseDouble(ev.valor) - r) < 1e-9;
    }

    private static String resumenEventos(List<EventoSolicitud> l) {
        List<String> t = new ArrayList<>();
        for (EventoSolicitud ev : l) {
            t.add(ev.folio.substring(ev.folio.lastIndexOf('-') + 1) + ":" + ev.expresion() + "=" + ev.valor);
        }
        return String.join("  ", t);
    }

    private void limpiar() {
        try {
            if (g != null) {
                g.apagarSistema();
            }
        } catch (RuntimeException ignored) {
            // nada
        }
        Util.dormir(1500);
        for (Process p : procesos) {
            p.destroyForcibly();
        }
        String cp = System.getProperty("java.class.path");
        ProcessHandle.allProcesses().forEach(h -> {
            if (h.pid() == ProcessHandle.current().pid()) {
                return;
            }
            String cmd = h.info().commandLine().orElse("");
            if (cmd.contains(cp) && cmd.contains(" ads.")) {
                h.destroyForcibly();
            }
        });
    }
}
