package ads.celula;

import ads.comun.Bitacora;
import ads.comun.CC;
import ads.comun.Config;
import ads.comun.DirNodo;
import ads.comun.Mensaje;
import ads.comun.Util;
import ads.red.Enlace;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Programa base común a todas las células (cliente, servidor y gestor).
 *
 * <ul>
 *   <li>Protocolo de conexión célula-nodo con tolerancia a fallas: si su nodo cae, la célula
 *       reintenta una vez y luego recorre los demás nodos conocidos (configuración + latidos).</li>
 *   <li>Toda la lógica de la célula corre en un único hilo ("bucle"): mensajes recibidos,
 *       temporizadores y latidos se procesan en orden, sin condiciones de carrera.</li>
 *   <li>Latido periódico (LATIDO_CELULA) y vista distribuida de la topología (Directorio).</li>
 * </ul>
 */
public abstract class Celula {

    public final String id;
    public final String clase;
    public final Config cfg;
    public final Bitacora log;
    public final Directorio directorio;
    protected final ScheduledExecutorService bucle;
    protected final ExecutorService trabajadores;
    private final List<DirNodo> candidatos = new CopyOnWriteArrayList<>();
    private volatile Enlace enlace;
    private volatile CountDownLatch cierre;
    public volatile String nodoActual;
    public volatile DirNodo dirActual;
    private final AtomicLong seq = new AtomicLong();
    private final String nonce = Util.nonce(4);
    protected final int ttl;
    protected volatile boolean activo = true;
    public final long inicio = Util.ahora();
    public final AtomicLong recibidos = new AtomicLong();
    public final AtomicLong enviados = new AtomicLong();
    private final List<Consumer<Boolean>> oyentesConexion = new CopyOnWriteArrayList<>();
    private final String hostLocal;

    protected Celula(String id, String clase, Config cfg, DirNodo preferido) {
        this.id = id;
        this.clase = clase;
        this.cfg = cfg;
        this.log = new Bitacora(id, cfg.dirLogs());
        this.directorio = new Directorio(id, cfg.largo("latido.timeout.ms", 5000),
                cfg.largo("desconexion.gracia.ms", 2000));
        this.ttl = cfg.entero("broadcast.ttl", 8);
        this.bucle = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bucle-" + id);
            t.setDaemon(true);
            return t;
        });
        this.trabajadores = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "trabajo-" + id);
            t.setDaemon(true);
            return t;
        });
        if (preferido != null) {
            candidatos.add(preferido);
        }
        for (DirNodo d : cfg.nodos()) {
            agregarCandidato(d);
        }
        String h;
        try {
            h = InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            h = "localhost";
        }
        hostLocal = h;
    }

    private void agregarCandidato(DirNodo d) {
        for (DirNodo c : candidatos) {
            if (c.mismaDireccion(d)) {
                return;
            }
        }
        candidatos.add(d);
    }

    public void arrancar() {
        Thread t = new Thread(this::bucleConexion, "conexion-" + id);
        t.start(); // hilo no-demonio: mantiene vivo el proceso aun sin interfaz gráfica
        bucle.scheduleWithFixedDelay(seguro(this::pulsoBase), 500, 500, TimeUnit.MILLISECONDS);
        long iv = cfg.largo("latido.intervalo.ms", 1500);
        bucle.scheduleWithFixedDelay(seguro(this::enviarLatido), 150 + Util.azar(300), iv, TimeUnit.MILLISECONDS);
    }

    protected final Runnable seguro(Runnable r) {
        return () -> {
            try {
                r.run();
            } catch (Throwable e) {
                log.error("Error interno: " + e);
            }
        };
    }

    // ------------------------------------------------------------------ conexión con el nodo

    private void bucleConexion() {
        int idx = 0;
        int fallos = 0;
        while (activo) {
            List<DirNodo> lista = new ArrayList<>(candidatos);
            if (lista.isEmpty()) {
                Util.dormir(1000);
                continue;
            }
            DirNodo d = lista.get(Math.floorMod(idx, lista.size()));
            if (fallos == 1 && lista.size() > 2) {
                // el nodo anterior no volvió: se elige otro al azar para repartir la carga entre los nodos
                idx = idx + Util.azar(lista.size() - 1);
                d = lista.get(Math.floorMod(idx, lista.size()));
            }
            if (conectar(d)) {
                fallos = 0;
                try {
                    cierre.await();
                } catch (InterruptedException e) {
                    return;
                }
                enlace = null;
                String previo = nodoActual;
                nodoActual = null;
                if (!activo) {
                    break;
                }
                log.aviso("Se perdió la conexión con el nodo " + previo + "; buscando un nodo disponible...");
                bucle.execute(seguro(this::desconectadoInterno));
                continue; // primero se reintenta el mismo nodo; si falla, se pasa al siguiente
            }
            idx++;
            fallos++;
            Util.dormir(fallos % lista.size() == 0 ? 1000 : 150);
        }
    }

    private boolean conectar(DirNodo d) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(d.host(), d.puerto()), 800);
            Enlace e = new Enlace(s);
            Mensaje hola = Mensaje.de(CC.HOLA_CELULA).con("id", id).con("clase", clase);
            sellar(hola);
            e.escribirAhora(hola.aJson());
            String l = e.leerLinea(2500);
            if (l == null) {
                e.cerrar();
                return false;
            }
            Mensaje r = Mensaje.deJson(l);
            if (r.cc != CC.BIENVENIDA) {
                log.aviso("El nodo " + d.direccion() + " rechazó la conexión: " + r.str("motivo"));
                e.cerrar();
                return false;
            }
            for (Object o : r.lista("conocidos")) {
                try {
                    agregarCandidato(DirNodo.parse(String.valueOf(o)));
                } catch (RuntimeException ignored) {
                    // entrada inválida
                }
            }
            CountDownLatch c = new CountDownLatch(1);
            cierre = c;
            e.tipo = Enlace.Tipo.NODO;
            e.idRemoto = r.str("id");
            nodoActual = r.str("id");
            dirActual = new DirNodo(nodoActual, d.host(), d.puerto());
            enlace = e;
            e.iniciar(this::recibirLinea, x -> c.countDown(), id);
            log.evento("Conectada al nodo " + nodoActual + " (" + d.direccion() + ")");
            bucle.execute(seguro(this::conectadoInterno));
            return true;
        } catch (IOException | RuntimeException ex) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada
            }
            return false;
        }
    }

    private void conectadoInterno() {
        directorio.reanudar();
        for (Consumer<Boolean> o : oyentesConexion) {
            o.accept(Boolean.TRUE);
        }
        enviarLatido();
        alConectar();
    }

    private void desconectadoInterno() {
        directorio.pausar();
        for (Consumer<Boolean> o : oyentesConexion) {
            o.accept(Boolean.FALSE);
        }
        alDesconectar();
    }

    public boolean conectada() {
        Enlace e = enlace;
        return e != null && e.abierto();
    }

    public void alCambiarConexion(Consumer<Boolean> oyente) {
        oyentesConexion.add(oyente);
    }

    // ------------------------------------------------------------------ mensajes

    private void recibirLinea(String l) {
        recibidos.incrementAndGet();
        Mensaje m;
        try {
            m = Mensaje.deJson(l);
        } catch (RuntimeException e) {
            log.error("Mensaje inválido recibido: " + e.getMessage());
            return;
        }
        bucle.execute(() -> {
            try {
                procesar(m);
            } catch (Throwable t) {
                log.error("Error procesando " + m + ": " + t);
            }
        });
    }

    private void procesar(Mensaje m) {
        if (m.origen != null && !m.origen.equals(id)) {
            directorio.actividad(m.origen);
        }
        if (m.cc == CC.LATIDO_CELULA && id.equals(m.str("celula")) && m.num("inicio", 0) != inicio) {
            if (m.num("inicio", 0) < inicio) {
                log.error("Ya existe otra célula activa con el id " + id + " (pid " + m.num("pid", 0)
                        + "); esta instancia, más reciente, se cierra para no duplicar identidades");
                detener();
                bucle.schedule(() -> System.exit(4), 300, TimeUnit.MILLISECONDS);
            }
            return;
        }
        switch (m.cc) {
            case CC.LATIDO_CELULA -> directorio.latidoCelula(m);
            case CC.LATIDO_NODO -> {
                directorio.latidoNodo(m);
                String nid = m.str("nodo");
                if (nid != null && m.num("puerto", 0) > 0) {
                    agregarCandidato(new DirNodo(nid, m.str("host", "localhost"), (int) m.num("puerto", 0)));
                }
            }
            case CC.CELULA_CONECTADA -> directorio.conectada(m.str("celula"), m.str("clase"), m.str("nodo"));
            case CC.CELULA_DESCONECTADA -> directorio.desconectada(m.str("celula"), m.str("nodo"));
            case CC.CELULA_BAJA -> directorio.retirada(m.str("celula", m.origen), m.num("inicio", 0));
            case CC.CONSULTA_TOPOLOGIA -> enviarLatido();
            case CC.APAGAR -> {
                alApagar(m);
                return;
            }
            default -> {
                // lo decide la subclase
            }
        }
        alRecibir(m);
    }

    private void pulsoBase() {
        Directorio.Revision r = directorio.revisar();
        for (Directorio.InfoCelula c : r.muertas()) {
            alMorirCelula(c);
        }
        for (Directorio.InfoCelula c : r.revividas()) {
            alRevivirCelula(c);
        }
        for (Directorio.InfoNodo n : r.nodosCaidos()) {
            alCaerNodo(n);
        }
        alPulso();
    }

    /** Difunde un mensaje por el Campo de Datos a través del nodo al que está conectada la célula. */
    public boolean enviar(Mensaje m) {
        sellar(m);
        Enlace e = enlace;
        if (e == null || !e.abierto()) {
            return false;
        }
        boolean ok = e.enviar(m.aJson());
        if (ok) {
            enviados.incrementAndGet();
        }
        return ok;
    }

    private void sellar(Mensaje m) {
        m.id = id + ":" + nonce + ":" + seq.incrementAndGet();
        m.origen = id;
        m.ts = Util.ahora();
        m.ttl = ttl;
        m.huella = new ArrayList<>(List.of(id));
    }

    public void enviarLatido() {
        if (!conectada()) {
            return;
        }
        Mensaje m = Mensaje.de(CC.LATIDO_CELULA).con("celula", id).con("clase", clase).con("nodo", nodoActual)
                .con("inicio", inicio).con("pid", ProcessHandle.current().pid()).con("host", hostLocal);
        completarLatido(m);
        enviar(m);
    }

    /** Cierra la conexión y deja de reconectarse. */
    protected void detener() {
        activo = false;
        Enlace e = enlace;
        if (e != null) {
            e.cerrar();
        }
    }

    // ------------------------------------------------------------------ ganchos para las subclases

    protected abstract void alRecibir(Mensaje m);

    protected void completarLatido(Mensaje m) {
    }

    protected void alConectar() {
    }

    protected void alDesconectar() {
    }

    protected void alPulso() {
    }

    protected void alMorirCelula(Directorio.InfoCelula c) {
    }

    protected void alRevivirCelula(Directorio.InfoCelula c) {
    }

    protected void alCaerNodo(Directorio.InfoNodo n) {
    }

    protected void alApagar(Mensaje m) {
        log.evento("APAGAR recibido de " + m.origen + ": la célula termina");
        detener();
        bucle.schedule(() -> System.exit(0), 400, TimeUnit.MILLISECONDS);
    }
}
