package ads.celula.servidor;

import ads.celula.Celula;
import ads.celula.Directorio;
import ads.comun.Args;
import ads.comun.CC;
import ads.comun.Config;
import ads.comun.DirNodo;
import ads.comun.Mensaje;
import ads.comun.Procesos;
import ads.comun.Util;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Célula servidor. Su programa base NO contiene la lógica de ninguna operación: lo que sabe hacer
 * depende de sus genes activos (microservicios cargados desde disco por {@link GestorGenes}).
 *
 * Atiende un mensaje cuando su CC corresponde a un gen activo y su folio no ha sido procesado.
 * Protocolo de foliado (lado servidor):
 *  SOLICITUD(cc, folio) → ACK(folio) → EVENTO_CONFIRMADO(folio, ejecutor) → si ejecutor == yo: RESULTADO(folio)
 *  → EVENTO_FIN(folio) libera el estado del folio.
 */
public final class CelulaServidor extends Celula {

    public final GestorGenes genes;
    final Autorrecuperacion recuperacion;
    public final int generacion;
    public final String padre;
    public final String reemplaza;
    public final String folioClon;
    public final boolean conGui;
    public volatile boolean silenciada;
    public volatile String pos;
    private final Map<String, Pendiente> pendientes = new HashMap<>();
    private final Map<String, CompletableFuture<Path>> peticionesGen = new HashMap<>();
    private final Set<String> genBinarioVisto = new LinkedHashSet<>();
    private final List<String> genesFaltantes = new ArrayList<>();
    private boolean primeraConexion = true;
    private volatile boolean retirandose;
    private final AtomicLong secGen = new AtomicLong();
    public final AtomicLong acks = new AtomicLong();
    public final AtomicLong ejecutadas = new AtomicLong();
    public final AtomicLong ignoradas = new AtomicLong();
    public final AtomicLong errores = new AtomicLong();

    private static final class Pendiente {
        final Mensaje solicitud;
        final long t = Util.ahora();
        int intentoEjecutado;

        Pendiente(Mensaje solicitud) {
            this.solicitud = solicitud;
        }
    }

    public CelulaServidor(String id, Config cfg, DirNodo nodo, int generacion, String padre, String reemplaza,
                          String folioClon, String pos, boolean conGui) {
        super(id, "SERVIDOR", cfg, nodo);
        this.genes = new GestorGenes(cfg.dirGenes(), log);
        this.generacion = generacion;
        this.padre = padre;
        this.reemplaza = reemplaza;
        this.folioClon = folioClon;
        this.pos = pos;
        this.conGui = conGui;
        this.recuperacion = new Autorrecuperacion(this);
        genes.alCambiar(() -> bucle.execute(seguro(this::enviarLatido)));
    }

    public static void main(String[] argv) {
        Args a = new Args(argv);
        Config cfg = Config.cargar(a.get("config", "config/ads.properties"));
        String id = a.get("id", "S-" + Util.nonce(4));
        String nodoTxt = a.get("nodo", null);
        DirNodo nodo = nodoTxt != null ? DirNodo.parse(nodoTxt) : cfg.nodos().get(0);
        boolean gui = !a.flag("sin-gui") && !GraphicsEnvironment.isHeadless();
        CelulaServidor c = new CelulaServidor(id, cfg, nodo, a.entero("generacion", 0), a.get("padre", null),
                a.get("reemplaza", null), a.get("folio", null), a.get("pos", null), gui);
        c.cargaInicial(a.get("genes", ""));
        if (gui) {
            VentanaServidor.mostrar(c);
        }
        c.arrancar();
    }

    /** Genes indicados al arrancar (despliegue rápido o genes heredados por un clon). */
    void cargaInicial(String spec) {
        if (reemplaza != null) {
            log.evento("Soy un CLON creado por " + padre + " (folio " + folioClon + ") para reemplazar a " + reemplaza
                    + "; generación " + generacion);
        }
        if (spec == null || spec.isBlank()) {
            log.info("Arranque sin genes activos: la célula espera que el Gestor de Servicios le asigne alguno");
            return;
        }
        log.info((reemplaza != null ? "Carga de los genes heredados: " : "Carga inicial de genes desde disco: ") + spec);
        for (String parte : spec.split(",")) {
            if (parte.isBlank()) {
                continue;
            }
            String[] nv = parte.trim().split(":");
            String nombre = nv[0].trim();
            String version = nv.length > 1 ? nv[1].trim() : null;
            try {
                genes.alta(nombre, version);
            } catch (GestorGenes.GenNoEncontrado e) {
                log.aviso(e.getMessage() + " — se pedirá por el Campo de Datos al conectarse");
                genesFaltantes.add(parte.trim());
            } catch (GestorGenes.ErrorGen e) {
                log.error("No se pudo activar " + parte + ": " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ accesos para Autorrecuperacion

    ScheduledExecutorService bucle() {
        return bucle;
    }

    Runnable seguroPublico(Runnable r) {
        return seguro(r);
    }

    Directorio.InfoCelula comoInfo() {
        Mensaje m = Mensaje.de(CC.LATIDO_CELULA).con("celula", id).con("clase", clase).con("nodo", nodoActual)
                .con("inicio", inicio);
        completarLatido(m);
        Directorio d = new Directorio("-", 1, 1);
        d.latidoCelula(m);
        Directorio.InfoCelula c = d.celula(id);
        c.conLatido = true;
        return c;
    }

    long lanzarClon(String clonId, String destino, String genesSpec, int gen, String deuda, String folio, String posClon)
            throws Exception {
        List<String> args = new ArrayList<>(List.of("--id", clonId, "--nodo", destino, "--genes", genesSpec,
                "--generacion", String.valueOf(gen), "--padre", id, "--reemplaza", deuda, "--folio", folio,
                "--config", cfg.archivo().toString()));
        if (posClon != null) {
            args.add("--pos");
            args.add(posClon);
        }
        if (!conGui) {
            args.add("--sin-gui");
        }
        Process p = Procesos.lanzar("ads.celula.servidor.CelulaServidor", args, cfg.dirLogs(), clonId);
        return p.pid();
    }

    // ------------------------------------------------------------------ ciclo de la célula

    @Override
    protected void alConectar() {
        if (!primeraConexion) {
            return;
        }
        primeraConexion = false;
        if (genesFaltantes.isEmpty()) {
            anunciarClon();
            return;
        }
        List<String> faltan = new ArrayList<>(genesFaltantes);
        genesFaltantes.clear();
        trabajadores.execute(() -> {
            for (String spec : faltan) {
                String[] nv = spec.split(":");
                try {
                    altaConTransferencia(nv[0], nv.length > 1 ? nv[1] : null);
                } catch (Exception e) {
                    log.error("No se pudo obtener " + spec + ": " + e.getMessage());
                }
            }
            bucle.execute(seguro(this::anunciarClon));
        });
    }

    private void anunciarClon() {
        if (reemplaza == null) {
            return;
        }
        boolean ok = !genes.activos().isEmpty();
        List<Object> gs = new ArrayList<>();
        for (Directorio.InfoGen g : genes.infoActivos()) {
            gs.add(g.aMapa());
        }
        enviar(Mensaje.de(CC.CLON_LISTO).conFolio(folioClon).con("deuda", reemplaza).con("clon", id).con("padre", padre)
                .con("ok", ok).con("genes", gs).con("motivo", ok ? "" : "no se pudo cargar ningún gen"));
        enviarLatido();
        if (ok) {
            log.evento("CLONACIÓN [finalización] folio " + folioClon + ": clon listo en " + nodoActual
                    + "; genes heredados activos: " + genesTexto());
        } else {
            log.error("Clon no viable: no se pudo cargar ningún gen heredado. La célula termina.");
            bucle.schedule(() -> System.exit(3), 1500, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    protected void completarLatido(Mensaje m) {
        List<Object> gs = new ArrayList<>();
        for (Directorio.InfoGen g : genes.infoActivos()) {
            gs.add(g.aMapa());
        }
        m.con("genes", gs).con("generacion", generacion).con("padre", padre).con("reemplaza", reemplaza)
                .con("silenciada", silenciada).con("pos", pos);
        List<Object> deudas = recuperacion.deudasAbiertas();
        if (!deudas.isEmpty()) {
            m.con("deudas", deudas);
        }
        Map<String, Object> stats = new java.util.LinkedHashMap<>();
        stats.put("acks", acks.get());
        stats.put("ejecutadas", ejecutadas.get());
        stats.put("ignoradas", ignoradas.get());
        stats.put("errores", errores.get());
        m.con("stats", stats);
    }

    @Override
    protected void alPulso() {
        long ahora = Util.ahora();
        pendientes.values().removeIf(p -> ahora - p.t > 60_000);
        recuperacion.evaluar();
    }

    @Override
    protected void alMorirCelula(Directorio.InfoCelula c) {
        recuperacion.alMorir(c);
    }

    @Override
    protected void alApagar(Mensaje m) {
        recuperacion.apagar();
        super.alApagar(m);
    }

    @Override
    protected void alRecibir(Mensaje m) {
        switch (m.cc) {
            case CC.LATIDO_CELULA -> recuperacion.alLatido(m);
            case CC.EVENTO_CONFIRMADO -> procesarConfirmacion(m);
            case CC.EVENTO_FIN -> pendientes.remove(m.folio);
            case CC.ADMIN_ALTA, CC.ADMIN_BAJA, CC.ADMIN_MODIFICAR, CC.ADMIN_SILENCIAR, CC.ADMIN_FALLA, CC.ADMIN_RETIRAR ->
                    procesarAdmin(m);
            case CC.FALLA_DETECTADA -> recuperacion.alFallaAnunciada(m);
            case CC.CLON_RESERVA -> recuperacion.alReserva(m);
            case CC.CLON_CANCELA -> recuperacion.alCancela(m);
            case CC.CLON_LISTO -> recuperacion.alClonListo(m);
            case CC.SOLICITUD_GEN -> responderSolicitudGen(m);
            case CC.GEN_BINARIO -> recibirGenBinario(m);
            default -> {
                if (CC.esOperacion(m.cc)) {
                    procesarSolicitud(m);
                }
            }
        }
    }

    // ------------------------------------------------------------------ foliado (lado servidor)

    private void procesarSolicitud(Mensaje m) {
        GestorGenes.GenActivo g = genes.porCC(m.cc);
        if (g == null) {
            ignoradas.incrementAndGet(); // no tengo un gen activo para ese Código de Contenido: lo ignoro
            return;
        }
        String folio = m.folio;
        if (folio == null || pendientes.containsKey(folio)) {
            return;
        }
        if (silenciada) {
            log.aviso("Solicitud " + folio + " (CC " + m.cc + ") recibida, pero la célula está SILENCIADA: no envía ACK");
            return;
        }
        pendientes.put(folio, new Pendiente(m));
        enviar(Mensaje.de(CC.ACK).conFolio(folio).con("celula", id).con("nodo", nodoActual).con("gen", g.d.nombre)
                .con("version", g.d.version).con("ccSolicitud", m.cc).con("huellaSolicitud", m.huella)
                .con("firma", m.str("firma")));
        acks.incrementAndGet();
        log.info("ACK " + folio + "  CC " + m.cc + " (" + g.d.etiqueta() + ")  huella " + m.trayectoria());
    }

    private void procesarConfirmacion(Mensaje m) {
        Pendiente p = pendientes.get(m.folio);
        if (p == null) {
            return; // no participé en ese evento
        }
        if (!id.equals(m.str("ejecutor"))) {
            return; // queda como respaldo hasta EVENTO_FIN
        }
        int intento = (int) m.num("intento", 1);
        if (p.intentoEjecutado >= intento) {
            return;
        }
        p.intentoEjecutado = intento;
        Mensaje sol = p.solicitud;
        log.info("CONFIRMADO " + m.folio + ": soy la ejecutora (ACK " + m.num("acks", 0) + " ≥ mín " + m.num("minAck", 0)
                + ", intento " + intento + ")");
        trabajadores.execute(() -> ejecutar(sol, intento));
    }

    private void ejecutar(Mensaje sol, int intento) {
        GestorGenes.GenActivo g = genes.porCC(sol.cc);
        Mensaje r = Mensaje.de(CC.RESULTADO).conFolio(sol.folio).con("celula", id).con("nodo", nodoActual)
                .con("huellaSolicitud", sol.huella).con("firma", sol.str("firma")).con("intento", intento);
        List<String> ops = sol.listaTexto("operandos");
        if (g == null) {
            r.con("estado", "NO_DISPONIBLE").con("error", "el gen para CC " + sol.cc + " ya no está activo en " + id);
            log.aviso("Folio " + sol.folio + ": ya no tengo el gen del CC " + sol.cc + " (NO_DISPONIBLE)");
        } else {
            r.con("gen", g.d.nombre).con("version", g.d.version);
            long t0 = System.nanoTime();
            try {
                String v = g.instancia.ejecutar(ops.toArray(new String[0]));
                g.ejecuciones.incrementAndGet();
                ejecutadas.incrementAndGet();
                r.con("estado", "OK").con("valor", v);
                log.evento("EJECUTADO " + sol.folio + ": " + String.join(" " + g.d.simbolo + " ", ops) + " = " + v
                        + "  (" + g.d.etiqueta() + ")");
            } catch (Throwable t) {
                errores.incrementAndGet();
                String msg = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                r.con("estado", "ERROR").con("error", msg);
                log.aviso("Folio " + sol.folio + ": error del gen " + g.d.etiqueta() + ": " + msg);
            }
            r.con("ms", (System.nanoTime() - t0) / 1_000_000.0);
        }
        enviar(r);
    }

    // ------------------------------------------------------------------ gestión de servicios

    private void procesarAdmin(Mensaje m) {
        if (!id.equals(m.str("destino"))) {
            return; // el comando es para otra célula
        }
        enviar(Mensaje.de(CC.ACK).conFolio(m.folio).con("celula", id).con("comando", CC.nombre(m.cc)));
        log.evento("COMANDO " + CC.nombre(m.cc) + " del gestor " + m.origen + " (folio " + m.folio + ")");
        String gen = m.str("gen");
        String version = m.str("version");
        switch (m.cc) {
            case CC.ADMIN_ALTA -> trabajadores.execute(() -> {
                try {
                    GestorGenes.GenActivo g = altaConTransferencia(gen, version);
                    responder(m, true, "Gen " + g.d.etiqueta() + " cargado desde disco y activo (CC " + g.d.cc + ")");
                } catch (Exception e) {
                    responder(m, false, e.getMessage());
                }
            });
            case CC.ADMIN_BAJA -> trabajadores.execute(() -> {
                try {
                    GestorGenes.GenActivo g = genes.baja(gen);
                    responder(m, true, "Gen " + g.d.etiqueta() + " dado de baja; CC " + g.d.cc + " ya no se atiende aquí");
                } catch (Exception e) {
                    responder(m, false, e.getMessage());
                }
            });
            case CC.ADMIN_MODIFICAR -> trabajadores.execute(() -> {
                try {
                    GestorGenes.GenActivo previo = genes.porNombre(gen);
                    String antes = previo == null ? "?" : previo.d.etiqueta();
                    GestorGenes.GenActivo g;
                    try {
                        g = genes.modificar(gen, version);
                    } catch (GestorGenes.GenNoEncontrado e) {
                        pedirGenPorCampo(gen, version).get(6, TimeUnit.SECONDS);
                        g = genes.modificar(gen, version);
                    }
                    responder(m, true, "Gen sustituido: " + antes + " → " + g.d.etiqueta());
                } catch (Exception e) {
                    responder(m, false, e.getMessage());
                }
            });
            case CC.ADMIN_SILENCIAR -> {
                silenciada = m.bool("valor");
                log.evento(silenciada ? "SILENCIADA: no enviará ACK (simula una célula que no responde)"
                        : "Reactivada: vuelve a enviar ACK");
                responder(m, true, silenciada ? "ACK silenciados" : "ACK reactivados");
            }
            case CC.ADMIN_FALLA -> {
                responder(m, true, "Falla simulada: la célula termina abruptamente");
                bucle.schedule(this::simularFalla, 300, TimeUnit.MILLISECONDS);
            }
            case CC.ADMIN_RETIRAR -> {
                responder(m, true, "Baja ordenada en curso");
                bucle.schedule(seguro(() -> retirarse("solicitada por el gestor " + m.origen)), 200, TimeUnit.MILLISECONDS);
            }
            default -> {
                // nada
            }
        }
    }

    private void responder(Mensaje cmd, boolean ok, String detalle) {
        List<Object> gs = new ArrayList<>();
        for (Directorio.InfoGen g : genes.infoActivos()) {
            gs.add(g.aMapa());
        }
        enviar(Mensaje.de(CC.ADMIN_RESPUESTA).conFolio(cmd.folio).con("celula", id).con("comando", CC.nombre(cmd.cc))
                .con("ok", ok).con("detalle", detalle).con("genes", gs));
        if (ok) {
            log.evento("Respuesta al gestor (" + cmd.folio + "): " + detalle);
        } else {
            log.error("Comando " + CC.nombre(cmd.cc) + " fallido (" + cmd.folio + "): " + detalle);
        }
        bucle.execute(seguro(this::enviarLatido));
    }

    private GestorGenes.GenActivo altaConTransferencia(String gen, String version) throws Exception {
        try {
            return genes.alta(gen, version);
        } catch (GestorGenes.GenNoEncontrado e) {
            log.aviso(e.getMessage());
            Path p = pedirGenPorCampo(gen, version).get(6, TimeUnit.SECONDS);
            log.info("Gen recibido por el Campo de Datos y guardado en disco: " + p);
            return genes.alta(gen, version);
        }
    }

    /** Pide un gen que no está en el disco local; cualquier célula que lo tenga responde con el .jar. */
    private CompletableFuture<Path> pedirGenPorCampo(String gen, String version) {
        String folio = id + "#GEN-" + secGen.incrementAndGet();
        CompletableFuture<Path> f = new CompletableFuture<>();
        bucle.execute(() -> peticionesGen.put(folio, f));
        bucle.schedule(() -> {
            CompletableFuture<Path> x = peticionesGen.remove(folio);
            if (x != null) {
                x.completeExceptionally(new GestorGenes.ErrorGen("Nadie en el Campo de Datos tiene el gen " + gen));
            }
        }, 5000, TimeUnit.MILLISECONDS);
        enviar(Mensaje.de(CC.SOLICITUD_GEN).conFolio(folio).con("nombre", gen).con("version", version));
        log.info("Se solicita el gen " + gen + " por el Campo de Datos (folio " + folio + ")");
        return f;
    }

    private void responderSolicitudGen(Mensaje m) {
        GestorGenes.Descriptor d;
        try {
            d = genes.localizar(m.str("nombre", ""), m.str("version"));
        } catch (GestorGenes.ErrorGen e) {
            return;
        }
        String folio = m.folio;
        bucle.schedule(seguro(() -> {
            if (genBinarioVisto.contains(folio)) {
                return; // alguien más ya lo envió
            }
            try {
                byte[] b = Files.readAllBytes(d.jar);
                enviar(Mensaje.de(CC.GEN_BINARIO).conFolio(folio).con("nombre", d.nombre).con("version", d.version)
                        .con("archivo", d.jar.getFileName().toString()).con("sha256", Util.sha256(b))
                        .con("contenido", Base64.getEncoder().encodeToString(b)));
                genBinarioVisto.add(folio);
                log.info("Envié el gen " + d.etiqueta() + " a " + m.origen + " (folio " + folio + ")");
            } catch (Exception e) {
                log.error("No se pudo enviar el gen: " + e.getMessage());
            }
        }), Util.azar(400), TimeUnit.MILLISECONDS);
    }

    private void recibirGenBinario(Mensaje m) {
        genBinarioVisto.add(m.folio);
        if (genBinarioVisto.size() > 500) {
            Iterator<String> it = genBinarioVisto.iterator();
            it.next();
            it.remove();
        }
        CompletableFuture<Path> f = peticionesGen.remove(m.folio);
        if (f == null) {
            return;
        }
        try {
            byte[] b = Base64.getDecoder().decode(m.str("contenido", ""));
            f.complete(genes.guardarRecibido(m.str("archivo", "gen.jar"), b, m.str("sha256")));
        } catch (Exception e) {
            f.completeExceptionally(e);
        }
    }

    // ------------------------------------------------------------------ salida

    public String genesTexto() {
        List<String> t = new ArrayList<>();
        for (GestorGenes.GenActivo g : genes.activos()) {
            t.add(g.d.etiqueta());
        }
        return t.isEmpty() ? "(ninguno)" : String.join(", ", t);
    }

    /** Baja ordenada: avisa al Campo de Datos (no es una falla, no genera clonación) y termina. */
    public void retirarse(String motivo) {
        if (retirandose) {
            return;
        }
        retirandose = true;
        recuperacion.apagar();
        log.evento("BAJA ORDENADA: " + motivo);
        List<Object> gs = new ArrayList<>();
        for (Directorio.InfoGen g : genes.infoActivos()) {
            gs.add(g.aMapa());
        }
        enviar(Mensaje.de(CC.CELULA_BAJA).con("celula", id).con("inicio", inicio).con("motivo", motivo).con("genes", gs));
        bucle.schedule(() -> {
            detener();
            genes.descargarTodo();
            System.exit(0);
        }, 600, TimeUnit.MILLISECONDS);
    }

    /** Termina abruptamente, sin avisar a nadie (equivale a matar el proceso). */
    public void simularFalla() {
        log.evento("FALLA SIMULADA: la célula termina abruptamente sin avisar");
        Runtime.getRuntime().halt(1);
    }

    public int deudasAbiertas() {
        return recuperacion.abiertas();
    }
}
