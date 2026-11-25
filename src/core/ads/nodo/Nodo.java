package ads.nodo;

import ads.comun.Args;
import ads.comun.Bitacora;
import ads.comun.CC;
import ads.comun.Config;
import ads.comun.DirNodo;
import ads.comun.Mensaje;
import ads.comun.Util;
import ads.red.Enlace;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Nodo del Campo de Datos.
 *
 * <ul>
 *   <li>Protocolo de conexión nodo-nodo: cada nodo intenta enlazarse con todos los nodos que conoce
 *       (configuración + descubrimiento por difusión). Si dos nodos se conectan a la vez, ambos
 *       conservan el enlace abierto por el nodo de identificador menor (regla determinista).</li>
 *   <li>Protocolo de conexión célula-nodo: la célula saluda con HOLA_CELULA y el nodo responde
 *       BIENVENIDA; el nodo anuncia CELULA_CONECTADA / CELULA_DESCONECTADA.</li>
 *   <li>Difusión (broadcast) por inundación con tres defensas contra ciclos:
 *       (1) caché de identificadores ya vistos, (2) huella: no se reenvía a nodos que ya aparecen
 *       en la trayectoria, (3) TTL decreciente.</li>
 * </ul>
 * El nodo no interpreta el contenido de las operaciones: sólo transporta. No hay nodo maestro.
 */
public final class Nodo {

    public final String id;
    public final String host;
    public final int puerto;
    private final String bindHost;
    final Config cfg;
    public final Bitacora log;
    private final int ttlInicial;
    private final long intervaloLatido;

    final Map<String, DirNodo> conocidos = new ConcurrentHashMap<>();
    private final Map<String, Integer> fallosConexion = new ConcurrentHashMap<>();
    private final Map<String, Long> proximoIntento = new ConcurrentHashMap<>();
    final Map<String, Enlace> nodos = new ConcurrentHashMap<>();
    final Map<String, Enlace> celulas = new ConcurrentHashMap<>();
    private final Set<String> conectando = ConcurrentHashMap.newKeySet();
    private final Vistos vistos = new Vistos(60000);

    public final AtomicLong recibidos = new AtomicLong();
    public final AtomicLong entregados = new AtomicLong();
    public final AtomicLong reenviados = new AtomicLong();
    public final AtomicLong duplicados = new AtomicLong();
    public final AtomicLong ttlAgotados = new AtomicLong();
    public final AtomicLong propios = new AtomicLong();

    private final AtomicLong seq = new AtomicLong();
    private final String nonce = Util.nonce(4);
    private final ScheduledExecutorService planificador = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "nodo-planificador");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService conexiones = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "nodo-conexiones");
        t.setDaemon(true);
        return t;
    });
    public final long inicio = Util.ahora();
    private volatile boolean activo = true;
    private ServerSocket servidor;
    private final List<Runnable> oyentes = new CopyOnWriteArrayList<>();

    public Nodo(String id, String host, int puerto, String bindHost, Config cfg) {
        this.id = id;
        this.host = host;
        this.puerto = puerto;
        this.bindHost = bindHost;
        this.cfg = cfg;
        this.log = new Bitacora(id, cfg.dirLogs());
        this.ttlInicial = cfg.entero("broadcast.ttl", 8);
        this.intervaloLatido = cfg.largo("latido.intervalo.ms", 1500);
    }

    public static void main(String[] argv) {
        Args a = new Args(argv);
        Config cfg = Config.cargar(a.get("config", "config/ads.properties"));
        String id = a.get("id", null);
        DirNodo propio = id != null ? cfg.nodoPorId(id) : null;
        int puerto = a.entero("puerto", propio != null ? propio.puerto() : 5001);
        if (id == null) {
            id = "N" + Math.max(1, puerto - 5000);
        }
        String host = a.get("host", propio != null ? propio.host() : "localhost");
        boolean gui = !a.flag("sin-gui") && !GraphicsEnvironment.isHeadless();
        // Si el nodo se anuncia como localhost sólo escucha en la interfaz local (sin aviso del firewall);
        // para varias máquinas se arranca con --host <IP> y escucha en todas las interfaces.
        boolean local = "localhost".equalsIgnoreCase(host) || host.startsWith("127.");
        Nodo n = new Nodo(id, host, puerto, a.get("bind", local ? "127.0.0.1" : "0.0.0.0"), cfg);
        try {
            n.iniciar();
        } catch (IOException e) {
            String msg = "El nodo " + id + " no pudo abrir el puerto " + puerto + ": " + e.getMessage();
            n.log.error(msg);
            if (gui) {
                javax.swing.JOptionPane.showMessageDialog(null, msg, "Nodo " + id, javax.swing.JOptionPane.ERROR_MESSAGE);
            }
            System.exit(2);
        }
        if (gui) {
            VentanaNodo.mostrar(n, a.get("pos", null));
        }
    }

    public void iniciar() throws IOException {
        servidor = new ServerSocket();
        servidor.setReuseAddress(true);
        servidor.bind(new InetSocketAddress(bindHost, puerto));
        for (DirNodo d : cfg.nodos()) {
            String clave = d.id() != null ? d.id() : "?" + d.direccion();
            if (!id.equals(d.id()) && !(d.puerto() == puerto && d.mismaDireccion(new DirNodo(id, host, puerto)))) {
                conocidos.put(clave, d);
            }
        }
        log.evento("Nodo " + id + " escuchando en " + host + ":" + puerto + "  (nodos conocidos: "
                + new TreeMap<>(conocidos).keySet() + ")");
        Thread aceptador = new Thread(this::bucleAceptar, "aceptar-" + id);
        aceptador.start();
        planificador.scheduleWithFixedDelay(seguro(this::mantenerMalla), 200, 2000, TimeUnit.MILLISECONDS);
        planificador.scheduleWithFixedDelay(seguro(this::enviarLatido), 700, intervaloLatido, TimeUnit.MILLISECONDS);
    }

    private Runnable seguro(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (RuntimeException e) {
                log.error("Error interno: " + e);
            }
        };
    }

    // ------------------------------------------------------------------ conexión

    private void bucleAceptar() {
        while (activo) {
            try {
                Socket s = servidor.accept();
                conexiones.execute(() -> atenderEntrante(s));
            } catch (IOException e) {
                if (activo) {
                    log.error("Error aceptando conexión: " + e.getMessage());
                    Util.dormir(200);
                }
            }
        }
    }

    private void atenderEntrante(Socket s) {
        try {
            Enlace e = new Enlace(s);
            String linea = e.leerLinea(3000);
            if (linea == null) {
                e.cerrar();
                return;
            }
            Mensaje h = Mensaje.deJson(linea);
            switch (h.cc) {
                case CC.HOLA_NODO -> {
                    String pid = h.str("id");
                    if (pid == null || pid.equals(id)) {
                        rechazar(e, "identificador de nodo duplicado (" + pid + ")");
                        return;
                    }
                    String hostPar = h.str("host", s.getInetAddress().getHostAddress());
                    conocidos.put(pid, new DirNodo(pid, hostPar, (int) h.num("puerto", 0)));
                    aprender(h.lista("conocidos"));
                    fallosConexion.remove(pid);
                    proximoIntento.remove(pid);
                    e.escribirAhora(saludo(CC.BIENVENIDA).aJson());
                    e.idRemoto = pid;
                    e.tipo = Enlace.Tipo.NODO;
                    e.iniciadoPorMi = false;
                    registrarNodo(e);
                }
                case CC.HOLA_CELULA -> {
                    String cid = h.str("id");
                    if (cid == null || cid.isBlank()) {
                        rechazar(e, "célula sin identificador");
                        return;
                    }
                    Enlace previo = celulas.get(cid);
                    if (previo != null && previo.abierto() && Util.ahora() - previo.ultimaRecepcion < 4000) {
                        rechazar(e, "ya hay una célula activa con el id " + cid + " en el nodo " + id);
                        return;
                    }
                    e.escribirAhora(saludo(CC.BIENVENIDA).aJson());
                    e.idRemoto = cid;
                    e.claseRemota = h.str("clase", "?");
                    e.tipo = Enlace.Tipo.CELULA;
                    registrarCelula(e);
                }
                default -> rechazar(e, "saludo inválido (CC " + h.cc + ")");
            }
        } catch (IOException | RuntimeException ex) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
        }
    }

    private void rechazar(Enlace e, String motivo) {
        try {
            Mensaje r = Mensaje.de(CC.RECHAZO).con("motivo", motivo);
            sellar(r);
            e.escribirAhora(r.aJson());
        } catch (IOException ignored) {
            // nada
        }
        log.aviso("Conexión rechazada: " + motivo);
        e.cerrar();
    }

    private void mantenerMalla() {
        if (!activo) {
            return;
        }
        long ahora = Util.ahora();
        for (Map.Entry<String, DirNodo> en : conocidos.entrySet()) {
            String clave = en.getKey();
            if (clave.equals(id) || nodos.containsKey(clave) || conectando.contains(clave)) {
                continue;
            }
            Long prox = proximoIntento.get(clave);
            if (prox != null && ahora < prox) {
                continue;
            }
            conectando.add(clave);
            conexiones.execute(() -> {
                try {
                    conectarA(clave, en.getValue());
                } finally {
                    conectando.remove(clave);
                }
            });
        }
    }

    private void conectarA(String clave, DirNodo d) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(d.host(), d.puerto()), 1000);
            Enlace e = new Enlace(s);
            e.escribirAhora(saludo(CC.HOLA_NODO).aJson());
            String linea = e.leerLinea(3000);
            if (linea == null) {
                e.cerrar();
                throw new IOException("sin respuesta al saludo");
            }
            Mensaje r = Mensaje.deJson(linea);
            if (r.cc != CC.BIENVENIDA) {
                e.cerrar();
                throw new IOException("saludo rechazado: " + r.str("motivo"));
            }
            String pid = r.str("id");
            if (pid == null || pid.equals(id)) {
                e.cerrar();
                conocidos.remove(clave);
                return;
            }
            if (!pid.equals(clave)) {
                conocidos.remove(clave);
            }
            conocidos.put(pid, new DirNodo(pid, d.host(), d.puerto()));
            aprender(r.lista("conocidos"));
            fallosConexion.remove(clave);
            proximoIntento.remove(clave);
            e.idRemoto = pid;
            e.tipo = Enlace.Tipo.NODO;
            e.iniciadoPorMi = true;
            registrarNodo(e);
        } catch (IOException | RuntimeException ex) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
            int f = fallosConexion.merge(clave, 1, Integer::sum);
            proximoIntento.put(clave, Util.ahora() + (f < 3 ? 1500 : 6000));
            if (f == 1 || f % 20 == 0) {
                log.info("Sin enlace con " + clave + " (" + d.direccion() + "): " + ex.getMessage());
            }
        }
    }

    private Mensaje saludo(int cc) {
        Mensaje m = Mensaje.de(cc).con("id", id).con("host", host).con("puerto", puerto)
                .con("conocidos", listaConocidos());
        sellar(m);
        return m;
    }

    /** Registra un enlace con otro nodo resolviendo duplicados de forma determinista. */
    private void registrarNodo(Enlace nuevo) {
        String pid = nuevo.idRemoto;
        Enlace viejo;
        synchronized (nodos) {
            viejo = nodos.get(pid);
            if (viejo != null && viejo.abierto()) {
                String iniNuevo = nuevo.iniciadoPorMi ? id : pid;
                String iniViejo = viejo.iniciadoPorMi ? id : pid;
                boolean conservarNuevo = iniNuevo.equals(iniViejo) || iniNuevo.compareTo(iniViejo) < 0;
                if (!conservarNuevo) {
                    nuevo.cerrar();
                    return;
                }
            }
            nodos.put(pid, nuevo);
        }
        nuevo.iniciar(l -> alRecibir(l, nuevo), this::alCerrar, id + "-" + pid);
        if (viejo != null) {
            viejo.cerrar();
        }
        log.evento("Enlace con nodo " + pid + " establecido (" + (nuevo.iniciadoPorMi ? "saliente" : "entrante") + ")");
        enviarLatido();
        notificar();
    }

    private void registrarCelula(Enlace e) {
        Enlace previo = celulas.put(e.idRemoto, e);
        e.iniciar(l -> alRecibir(l, e), this::alCerrar, id + "-" + e.idRemoto);
        if (previo != null) {
            log.aviso("La célula " + e.idRemoto + " se reconectó; se reemplaza su enlace anterior");
            previo.cerrar();
        }
        log.evento("Célula " + e.idRemoto + " [" + e.claseRemota + "] conectada");
        difundirPropio(Mensaje.de(CC.CELULA_CONECTADA).con("celula", e.idRemoto).con("clase", e.claseRemota)
                .con("nodo", id));
        notificar();
    }

    private void alCerrar(Enlace e) {
        if (!activo) {
            return;
        }
        if (e.tipo == Enlace.Tipo.NODO) {
            if (nodos.remove(e.idRemoto, e)) {
                log.aviso("Se perdió el enlace con el nodo " + e.idRemoto);
                proximoIntento.remove(e.idRemoto);
                enviarLatido();
                notificar();
            }
        } else if (e.tipo == Enlace.Tipo.CELULA) {
            if (celulas.remove(e.idRemoto, e)) {
                log.aviso("Célula " + e.idRemoto + " desconectada");
                difundirPropio(Mensaje.de(CC.CELULA_DESCONECTADA).con("celula", e.idRemoto)
                        .con("clase", e.claseRemota).con("nodo", id));
                notificar();
            }
        }
    }

    // ------------------------------------------------------------------ difusión

    /** Recepción de un mensaje desde cualquier enlace: deduplicar, sellar huella y reenviar. */
    private void alRecibir(String linea, Enlace desde) {
        recibidos.incrementAndGet();
        Mensaje m;
        try {
            m = Mensaje.deJson(linea);
        } catch (RuntimeException ex) {
            log.error("Mensaje mal formado de " + desde.idRemoto + ": " + ex.getMessage());
            return;
        }
        if (m.id == null) {
            return;
        }
        if (!vistos.agregar(m.id)) {          // (1) ya pasó por este nodo: se descarta (evita ciclos)
            duplicados.incrementAndGet();
            return;
        }
        if (m.ttl <= 0) {                     // (3) TTL agotado
            ttlAgotados.incrementAndGet();
            return;
        }
        m.ttl--;
        m.huella.add(id);                     // el nodo deja su marca en la trayectoria
        String json = m.aJson();
        for (Enlace c : celulas.values()) {
            if (c != desde && c.enviar(json)) {
                entregados.incrementAndGet();
            }
        }
        for (Map.Entry<String, Enlace> en : nodos.entrySet()) {
            Enlace n = en.getValue();
            if (n == desde || m.huella.contains(en.getKey())) {   // (2) no regresa a nodos de la huella
                continue;
            }
            if (n.enviar(json)) {
                reenviados.incrementAndGet();
            }
        }
        consumir(m);
    }

    /** Mensajes que también interesan al propio nodo. */
    private void consumir(Mensaje m) {
        switch (m.cc) {
            case CC.LATIDO_NODO -> {
                aprender(m.lista("conocidos"));
                String nid = m.str("nodo");
                if (nid != null && !nid.equals(id) && !conocidos.containsKey(nid)) {
                    conocidos.put(nid, new DirNodo(nid, m.str("host", "localhost"), (int) m.num("puerto", 0)));
                    log.info("Nodo " + nid + " descubierto por difusión");
                }
            }
            case CC.CONSULTA_TOPOLOGIA -> enviarLatido();
            case CC.APAGAR -> {
                log.evento("APAGAR recibido de " + m.origen + ": el nodo termina");
                planificador.schedule(() -> System.exit(0), 700, TimeUnit.MILLISECONDS);
            }
            case CC.ADMIN_FALLA -> {
                if (id.equals(m.str("destino"))) {
                    log.evento("Falla simulada solicitada por " + m.origen + ": el nodo se detiene abruptamente");
                    planificador.schedule(() -> Runtime.getRuntime().halt(1), 200, TimeUnit.MILLISECONDS);
                }
            }
            default -> {
                // el nodo sólo transporta
            }
        }
    }

    private void aprender(List<Object> lista) {
        for (Object o : lista) {
            try {
                DirNodo d = DirNodo.parse(String.valueOf(o));
                if (d.id() != null && !d.id().equals(id) && !conocidos.containsKey(d.id())) {
                    conocidos.put(d.id(), d);
                    log.info("Nodo " + d.id() + " (" + d.direccion() + ") descubierto por intercambio de conocidos");
                }
            } catch (RuntimeException ignored) {
                // entrada inválida
            }
        }
    }

    private List<String> listaConocidos() {
        List<String> r = new ArrayList<>();
        r.add(new DirNodo(id, host, puerto).toString());
        for (Map.Entry<String, DirNodo> en : conocidos.entrySet()) {
            if (!en.getKey().startsWith("?")) {
                r.add(new DirNodo(en.getKey(), en.getValue().host(), en.getValue().puerto()).toString());
            }
        }
        return r;
    }

    private void sellar(Mensaje m) {
        m.id = id + ":" + nonce + ":" + seq.incrementAndGet();
        m.origen = id;
        m.ts = Util.ahora();
        m.ttl = ttlInicial;
        m.huella = new ArrayList<>(List.of(id));
    }

    /** Difunde un mensaje creado por el propio nodo (latidos, avisos de conexión). */
    private void difundirPropio(Mensaje m) {
        if (!activo) {
            return;
        }
        sellar(m);
        vistos.agregar(m.id);
        propios.incrementAndGet();
        String json = m.aJson();
        for (Enlace c : celulas.values()) {
            if (c.enviar(json)) {
                entregados.incrementAndGet();
            }
        }
        for (Enlace n : nodos.values()) {
            if (n.enviar(json)) {
                reenviados.incrementAndGet();
            }
        }
    }

    private void enviarLatido() {
        List<Map<String, Object>> cs = new ArrayList<>();
        for (Enlace c : celulas.values()) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.idRemoto);
            x.put("clase", String.valueOf(c.claseRemota));
            cs.add(x);
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("recibidos", recibidos.get());
        stats.put("entregados", entregados.get());
        stats.put("reenviados", reenviados.get());
        stats.put("duplicados", duplicados.get());
        stats.put("ttlAgotados", ttlAgotados.get());
        difundirPropio(Mensaje.de(CC.LATIDO_NODO).con("nodo", id).con("host", host).con("puerto", puerto)
                .con("vecinos", new ArrayList<>(new TreeMap<>(nodos).keySet())).con("celulas", cs)
                .con("conocidos", listaConocidos()).con("inicio", inicio).con("stats", stats));
    }

    // ------------------------------------------------------------------ consulta para la interfaz

    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    private void notificar() {
        for (Runnable r : oyentes) {
            r.run();
        }
    }

    public Map<String, DirNodo> conocidos() {
        return new TreeMap<>(conocidos);
    }

    public Set<String> vecinos() {
        return new TreeMap<>(nodos).keySet();
    }

    public List<String[]> celulasConectadas() {
        List<String[]> r = new ArrayList<>();
        for (Enlace e : new TreeMap<>(celulas).values()) {
            r.add(new String[]{e.idRemoto, String.valueOf(e.claseRemota)});
        }
        return r;
    }

    public int tamVistos() {
        return vistos.tam();
    }

    /** Detiene el nodo de golpe (simula la falla del nodo). */
    public void detenerAbruptamente() {
        log.evento("Nodo detenido por el usuario (falla simulada)");
        Runtime.getRuntime().halt(1);
    }

    /** Caché acotada de identificadores de mensajes ya vistos. */
    static final class Vistos {
        private final LinkedHashMap<String, Boolean> mapa;

        Vistos(int capacidad) {
            mapa = new LinkedHashMap<>(capacidad * 4 / 3 + 16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > capacidad;
                }
            };
        }

        synchronized boolean agregar(String id) {
            return mapa.put(id, Boolean.TRUE) == null;
        }

        synchronized int tam() {
            return mapa.size();
        }
    }
}
