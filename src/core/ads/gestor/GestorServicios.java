package ads.gestor;

import ads.celula.Celula;
import ads.celula.Directorio;
import ads.celula.Directorio.InfoCelula;
import ads.celula.Directorio.InfoGen;
import ads.celula.servidor.GestorGenes;
import ads.comun.Args;
import ads.comun.CC;
import ads.comun.Config;
import ads.comun.DirNodo;
import ads.comun.Mensaje;
import ads.comun.Procesos;
import ads.comun.Util;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Gestor de Servicios: herramienta administrativa que se conecta al Campo de Datos como una célula más.
 * Construye la topología a partir de los latidos (no hay registro central), y envía comandos con folio
 * (ADMIN_ALTA / ADMIN_BAJA / ADMIN_MODIFICAR ...) a la célula elegida, que los confirma con ACK y
 * responde con ADMIN_RESPUESTA. El sistema funciona igual aunque el gestor se cierre.
 */
public final class GestorServicios extends Celula {

    public static final String[] DESPLIEGUE = {"SUMA", "RESTA", "MULTIPLICACION", "DIVISION"};

    /** Un comando administrativo es también un evento con folio. */
    public static final class Comando {
        public final String folio;
        public final int cc;
        public final String destino;
        public final String gen;
        public final String version;
        public final long t0 = Util.ahora();
        public volatile String estado = "ENVIADO";
        public volatile boolean ack;
        public volatile Boolean ok;
        public volatile String detalle = "";
        public final CompletableFuture<Comando> futuro = new CompletableFuture<>();

        Comando(String folio, int cc, String destino, String gen, String version) {
            this.folio = folio;
            this.cc = cc;
            this.destino = destino;
            this.gen = gen;
            this.version = version;
        }

        public String descripcion() {
            String n = CC.nombre(cc).replace("ADMIN_", "");
            return n + (gen != null ? " " + gen + (version != null ? " v" + version : "") : "") + " en " + destino;
        }
    }

    /** Deuda de recuperación vista desde el gestor (unión de lo que reportan las células). */
    public record DeudaVista(String celula, String genes, String estado, String reservadaPor, String clon, String motivo) {
    }

    private final Map<String, Comando> comandos = new ConcurrentHashMap<>();
    private final AtomicLong secFolio = new AtomicLong();
    private final List<Consumer<Comando>> oyentesComando = new CopyOnWriteArrayList<>();
    private final Set<String> clonesVistos = new HashSet<>();
    private final Set<String> fallasVistas = new HashSet<>();
    private final Set<String> genBinarioVisto = new HashSet<>();
    public volatile String pos;

    public GestorServicios(String id, Config cfg, DirNodo nodo) {
        super(id, "GESTOR", cfg, nodo);
    }

    public static void main(String[] argv) {
        Args a = new Args(argv);
        Config cfg = Config.cargar(a.get("config", "config/ads.properties"));
        String nodoTxt = a.get("nodo", null);
        DirNodo nodo = nodoTxt != null ? DirNodo.parse(nodoTxt) : cfg.nodos().get(0);
        GestorServicios g = new GestorServicios(a.get("id", "GESTOR"), cfg, nodo);
        g.pos = a.get("pos", null);
        g.arrancar();
        if (!a.flag("sin-gui") && !GraphicsEnvironment.isHeadless()) {
            VentanaGestor.mostrar(g);
        }
    }

    public void alComando(Consumer<Comando> o) {
        oyentesComando.add(o);
    }

    private void notificar(Comando c) {
        for (Consumer<Comando> o : oyentesComando) {
            try {
                o.accept(c);
            } catch (RuntimeException ignored) {
                // nada
            }
        }
    }

    @Override
    protected void alConectar() {
        consultarTopologia();
    }

    // ------------------------------------------------------------------ comandos

    private Comando enviarComando(int cc, String destino, String gen, String version, Boolean valor) {
        String folio = id + "#" + secFolio.incrementAndGet();
        Comando c = new Comando(folio, cc, destino, gen, version);
        comandos.put(folio, c);
        Mensaje m = Mensaje.de(cc).conFolio(folio).con("destino", destino);
        if (gen != null) {
            m.con("gen", gen);
        }
        if (version != null) {
            m.con("version", version);
        }
        if (valor != null) {
            m.con("valor", valor);
        }
        if (!enviar(m)) {
            c.estado = "SIN CONEXIÓN";
            c.ok = false;
            c.detalle = "el gestor no está conectado al Campo de Datos";
            c.futuro.complete(c);
            notificar(c);
            return c;
        }
        log.evento("COMANDO " + c.descripcion() + " (folio " + folio + ")");
        notificar(c);
        bucle.schedule(() -> {
            if (c.ok == null) {
                c.estado = c.ack ? "SIN RESPUESTA" : "SIN ACK";
                c.ok = false;
                c.detalle = c.ack ? "la célula confirmó (ACK) pero no respondió" : "ninguna célula confirmó el comando";
                log.aviso("Comando " + folio + " sin respuesta: " + c.detalle);
                c.futuro.complete(c);
                notificar(c);
            }
        }, 8000, TimeUnit.MILLISECONDS);
        return c;
    }

    public Comando alta(String celula, String gen, String version) {
        return enviarComando(CC.ADMIN_ALTA, celula, gen, version, null);
    }

    public Comando baja(String celula, String gen) {
        return enviarComando(CC.ADMIN_BAJA, celula, gen, null, null);
    }

    public Comando modificar(String celula, String gen, String version) {
        return enviarComando(CC.ADMIN_MODIFICAR, celula, gen, version, null);
    }

    public Comando silenciar(String celula, boolean valor) {
        return enviarComando(CC.ADMIN_SILENCIAR, celula, null, null, valor);
    }

    public Comando simularFalla(String destino) {
        return enviarComando(CC.ADMIN_FALLA, destino, null, null, null);
    }

    public Comando retirar(String celula) {
        return enviarComando(CC.ADMIN_RETIRAR, celula, null, null, null);
    }

    public void consultarTopologia() {
        enviar(Mensaje.de(CC.CONSULTA_TOPOLOGIA));
    }

    public void apagarSistema() {
        log.evento("APAGAR: se pide a todos los nodos y células terminar ordenadamente");
        enviar(Mensaje.de(CC.APAGAR).con("motivo", "solicitado desde el Gestor de Servicios"));
    }

    /**
     * Despliegue inicial guiado por la topología: en cada nodo, a las células servidor sin genes se les
     * asigna una operación que el nodo todavía no ofrece (SUMA, RESTA, MULTIPLICACION, DIVISION).
     */
    public List<Comando> despliegueInicial() {
        List<Comando> r = new ArrayList<>();
        Map<String, List<InfoCelula>> porNodo = new TreeMap<>();
        for (InfoCelula c : directorio.servidoresVivos()) {
            porNodo.computeIfAbsent(c.nodo == null ? "?" : c.nodo, k -> new ArrayList<>()).add(c);
        }
        for (Map.Entry<String, List<InfoCelula>> en : porNodo.entrySet()) {
            List<String> faltan = new ArrayList<>(List.of(DESPLIEGUE));
            for (InfoCelula c : en.getValue()) {
                for (InfoGen g : c.genes) {
                    faltan.remove(g.nombre);
                }
            }
            for (InfoCelula c : en.getValue()) {
                if (faltan.isEmpty()) {
                    break;
                }
                if (c.genes.isEmpty()) {
                    r.add(alta(c.id, faltan.remove(0), "1.0"));
                }
            }
        }
        if (r.isEmpty()) {
            log.aviso("Despliegue inicial: no hay células sin genes o cada nodo ya ofrece las cuatro operaciones");
        }
        return r;
    }

    // ------------------------------------------------------------------ procesos nuevos (cambio de topología)

    public String nuevaCelulaServidor(String nodoId, boolean sinGui) throws IOException {
        Directorio.InfoNodo n = directorio.nodo(nodoId);
        String dir = n != null && n.puerto > 0 ? n.direccion() : (dirActual != null ? dirActual.direccion() : null);
        if (dir == null) {
            throw new IOException("No se conoce la dirección del nodo " + nodoId);
        }
        int max = 0;
        for (InfoCelula c : directorio.celulas()) {
            String pref = "S-" + nodoId + "-";
            if (c.id.startsWith(pref)) {
                try {
                    max = Math.max(max, Integer.parseInt(c.id.substring(pref.length()).split("-")[0]));
                } catch (NumberFormatException ignored) {
                    // id con otro formato
                }
            }
        }
        String nid = "S-" + nodoId + "-" + (max + 1);
        int col = 0;
        try {
            col = (Integer.parseInt(nodoId.replaceAll("\\D", "")) - 1) % 4;
        } catch (NumberFormatException ignored) {
            // nada
        }
        List<String> args = new ArrayList<>(List.of("--id", nid, "--nodo", dir, "--config", cfg.archivo().toString(),
                "--pos", String.format(Locale.ROOT, "%.3f,%.3f", 0.50 + 0.125 * col + 0.02, 0.60)));
        if (sinGui) {
            args.add("--sin-gui");
        }
        Process p = Procesos.lanzar("ads.celula.servidor.CelulaServidor", args, cfg.dirLogs(), nid);
        log.evento("TOPOLOGÍA: nueva célula servidor " + nid + " (sin genes) conectándose a " + nodoId + " (pid " + p.pid() + ")");
        return nid;
    }

    public String nuevoNodo(boolean sinGui) throws IOException {
        int maxN = 0;
        int maxPuerto = 5000;
        for (Directorio.InfoNodo n : directorio.nodos()) {
            try {
                maxN = Math.max(maxN, Integer.parseInt(n.id.replaceAll("\\D", "")));
            } catch (NumberFormatException ignored) {
                // nada
            }
            maxPuerto = Math.max(maxPuerto, n.puerto);
        }
        for (DirNodo d : cfg.nodos()) {
            maxPuerto = Math.max(maxPuerto, d.puerto());
            try {
                maxN = Math.max(maxN, Integer.parseInt(d.id().replaceAll("\\D", "")));
            } catch (RuntimeException ignored) {
                // nada
            }
        }
        String nid = "N" + (maxN + 1);
        int puerto = maxPuerto + 1;
        List<String> args = new ArrayList<>(List.of("--id", nid, "--puerto", String.valueOf(puerto), "--host", "localhost",
                "--config", cfg.archivo().toString(), "--pos", "0.40,0.75"));
        if (sinGui) {
            args.add("--sin-gui");
        }
        Process p = Procesos.lanzar("ads.nodo.Nodo", args, cfg.dirLogs(), nid);
        log.evento("TOPOLOGÍA: nuevo nodo " + nid + " en el puerto " + puerto + " (pid " + p.pid()
                + "); se enlazará en malla con los demás");
        return nid;
    }

    // ------------------------------------------------------------------ catálogo y disponibilidad

    public List<GestorGenes.Descriptor> catalogo() {
        return GestorGenes.catalogo(cfg.dirGenes());
    }

    /** Disponibilidad por servicio: nombre → {activos, deudas, versiones}. */
    public Map<String, int[]> disponibilidad() {
        Map<String, int[]> r = new TreeMap<>();
        for (InfoCelula c : directorio.servidoresVivos()) {
            for (InfoGen g : c.genes) {
                r.computeIfAbsent(g.nombre, k -> new int[2])[0]++;
            }
        }
        for (DeudaVista d : deudas()) {
            if (d.estado().equals("PENDIENTE") || d.estado().equals("RESERVADA")) {
                for (String g : d.genes().split(",")) {
                    String nombre = g.trim().split(" ")[0];
                    if (!nombre.isEmpty()) {
                        r.computeIfAbsent(nombre, k -> new int[2])[1]++;
                    }
                }
            }
        }
        return r;
    }

    /** Une las deudas que reportan las células en sus latidos (cada una tiene su copia). */
    @SuppressWarnings("unchecked")
    public List<DeudaVista> deudas() {
        Map<String, DeudaVista> r = new TreeMap<>();
        Set<String> reemplazadas = new HashSet<>();
        List<InfoCelula> vivas = directorio.servidoresVivos();
        for (InfoCelula c : vivas) {
            if (c.reemplaza != null) {
                reemplazadas.add(c.reemplaza);
            }
        }
        for (InfoCelula c : vivas) {
            for (Object o : c.deudas) {
                if (!(o instanceof Map<?, ?> m0)) {
                    continue;
                }
                Map<String, Object> m = (Map<String, Object>) m0;
                String cel = String.valueOf(m.get("celula"));
                if (reemplazadas.contains(cel) || directorio.estaViva(cel)) {
                    continue;
                }
                List<String> gs = new ArrayList<>();
                if (m.get("genes") instanceof List<?> l) {
                    for (Object x : l) {
                        InfoGen g = InfoGen.de(x);
                        if (g != null) {
                            gs.add(g.etiqueta());
                        }
                    }
                }
                String estado = String.valueOf(m.get("estado"));
                DeudaVista nueva = new DeudaVista(cel, String.join(", ", gs), estado,
                        m.get("reservadaPor") == null ? null : String.valueOf(m.get("reservadaPor")),
                        m.get("clon") == null ? null : String.valueOf(m.get("clon")),
                        m.get("motivo") == null ? null : String.valueOf(m.get("motivo")));
                DeudaVista vieja = r.get(cel);
                if (vieja == null || peso(estado) > peso(vieja.estado())) {
                    r.put(cel, nueva);
                }
            }
        }
        return new ArrayList<>(r.values());
    }

    private static int peso(String estado) {
        return switch (estado) {
            case "IRRECUPERABLE" -> 3;
            case "RESERVADA" -> 2;
            case "PENDIENTE" -> 1;
            default -> 0;
        };
    }

    public List<Comando> comandos() {
        List<Comando> r = new ArrayList<>(comandos.values());
        r.sort((a, b) -> Long.compare(b.t0, a.t0));
        return r;
    }

    // ------------------------------------------------------------------ recepción

    @Override
    protected void alRecibir(Mensaje m) {
        switch (m.cc) {
            case CC.ACK -> {
                Comando c = m.folio == null ? null : comandos.get(m.folio);
                if (c != null && !c.ack) {
                    c.ack = true;
                    c.estado = "ACK";
                    log.info("ACK del comando " + m.folio + " recibido de " + m.str("celula", m.origen));
                    notificar(c);
                }
            }
            case CC.ADMIN_RESPUESTA -> {
                Comando c = m.folio == null ? null : comandos.get(m.folio);
                if (c != null && c.ok == null) {
                    c.ok = m.bool("ok");
                    c.detalle = m.str("detalle", "");
                    c.estado = c.ok ? "COMPLETADO" : "ERROR";
                    if (c.ok) {
                        log.evento("RESPUESTA " + m.folio + " de " + m.origen + ": " + c.detalle);
                    } else {
                        log.error("RESPUESTA " + m.folio + " de " + m.origen + ": " + c.detalle);
                    }
                    c.futuro.complete(c);
                    notificar(c);
                    bucle.schedule(this::consultarTopologia, 300, TimeUnit.MILLISECONDS);
                }
            }
            case CC.LATIDO_CELULA -> {
                String r = m.str("reemplaza");
                String cid = m.str("celula", m.origen);
                if (r != null && clonesVistos.add(cid)) {
                    log.evento("AUTORRECUPERACIÓN: apareció el clon " + cid + " (padre " + m.str("padre") + ", generación "
                            + m.num("generacion", 0) + ") que reemplaza a " + r);
                }
            }
            case CC.FALLA_DETECTADA -> {
                Map<String, Object> d = m.mapa("deuda");
                String cel = String.valueOf(d.get("celula"));
                if (fallasVistas.add(cel + "@" + d.get("inicioCelula"))) {
                    log.aviso("FALLA DETECTADA por " + m.origen + ": la célula " + cel + " dejó de responder");
                }
            }
            case CC.CLON_RESERVA -> log.evento("CLONACIÓN: " + m.str("padre") + " reservó el reemplazo de " + m.str("deuda")
                    + " → clon " + m.str("clon") + " (folio " + m.folio + ")");
            case CC.CLON_LISTO -> log.evento("CLONACIÓN completada: " + m.str("clon") + " está listo ("
                    + (m.bool("ok") ? "genes heredados activos" : "no viable: " + m.str("motivo")) + ")");
            case CC.CELULA_BAJA -> log.info("Baja ordenada de " + m.str("celula", m.origen) + ": " + m.str("motivo", ""));
            case CC.CELULA_CONECTADA -> log.info("Célula " + m.str("celula") + " [" + m.str("clase") + "] conectada a "
                    + m.str("nodo"));
            case CC.CELULA_DESCONECTADA -> log.info("Célula " + m.str("celula") + " desconectada de " + m.str("nodo"));
            case CC.SOLICITUD_GEN -> responderSolicitudGen(m);
            case CC.GEN_BINARIO -> genBinarioVisto.add(m.folio);
            default -> {
                // nada
            }
        }
    }

    /** El gestor también comparte genes de su repositorio local con quien los pida. */
    private void responderSolicitudGen(Mensaje m) {
        GestorGenes.Descriptor d = null;
        for (GestorGenes.Descriptor x : catalogo()) {
            if (x.nombre.equalsIgnoreCase(m.str("nombre", ""))
                    && (m.str("version") == null || Util.compararVersiones(x.version, m.str("version")) == 0)) {
                if (d == null || Util.compararVersiones(x.version, d.version) > 0) {
                    d = x;
                }
            }
        }
        if (d == null) {
            return;
        }
        GestorGenes.Descriptor f = d;
        bucle.schedule(seguro(() -> {
            if (genBinarioVisto.contains(m.folio)) {
                return;
            }
            try {
                byte[] b = Files.readAllBytes(f.jar);
                enviar(Mensaje.de(CC.GEN_BINARIO).conFolio(m.folio).con("nombre", f.nombre).con("version", f.version)
                        .con("archivo", f.jar.getFileName().toString()).con("sha256", Util.sha256(b))
                        .con("contenido", Base64.getEncoder().encodeToString(b)));
                genBinarioVisto.add(m.folio);
                log.info("Envié el gen " + f.etiqueta() + " a " + m.origen);
            } catch (IOException e) {
                log.error("No se pudo enviar el gen: " + e.getMessage());
            }
        }), 200 + Util.azar(300), TimeUnit.MILLISECONDS);
    }

    @Override
    protected void completarLatido(Mensaje m) {
        m.con("pos", pos);
    }

    public Map<String, Object> resumen() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("nodos", directorio.nodos().size());
        r.put("celulas", directorio.vivas().size());
        return r;
    }
}
