package ads.celula.cliente;

import ads.celula.Celula;
import ads.celula.Directorio;
import ads.comun.Args;
import ads.comun.CC;
import ads.comun.Config;
import ads.comun.DirNodo;
import ads.comun.Mensaje;
import ads.comun.Util;

import java.awt.GraphicsEnvironment;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Célula cliente (calculadora). Lado cliente del protocolo de foliado:
 * genera el folio y la firma (huella de origen), recolecta ACK, decide con el Min. # de ACK,
 * designa a la célula ejecutora, valida el RESULTADO (folio + firma + ejecutora) y cierra el evento.
 * Todo ACK o RESULTADO cuyo folio no sea suyo se descarta y se cuenta como "ajeno".
 */
public final class CelulaCliente extends Celula {

    /** Operaciones estándar de la calculadora (si aún no se descubren en los latidos). */
    public static final Object[][] ESTANDAR = {
            {"SUMA", 101, "+"}, {"RESTA", 102, "-"}, {"MULTIPLICACION", 103, "×"}, {"DIVISION", 104, "÷"}};

    private final Map<String, EventoSolicitud> eventos = new LinkedHashMap<>();
    private final AtomicLong secFolio = new AtomicLong();
    private final String marca = new SimpleDateFormat("HHmmss").format(new Date());
    public volatile int minAck;
    private final long timeoutAck;
    private final long timeoutResultado;
    private final int reintentos;
    public final AtomicLong ajenos = new AtomicLong();
    public final AtomicLong propios = new AtomicLong();
    private final List<Consumer<EventoSolicitud>> oyentes = new CopyOnWriteArrayList<>();
    private final List<Consumer<Mensaje>> oyentesMensajes = new CopyOnWriteArrayList<>();
    public volatile String pos;

    public CelulaCliente(String id, Config cfg, DirNodo nodo) {
        super(id, "CLIENTE", cfg, nodo);
        this.minAck = cfg.entero("evento.minAck", 2);
        this.timeoutAck = cfg.largo("evento.timeoutAck.ms", 1000);
        this.timeoutResultado = cfg.largo("evento.timeoutResultado.ms", 2500);
        this.reintentos = cfg.entero("evento.reintentos", 2);
    }

    public static void main(String[] argv) {
        Args a = new Args(argv);
        Config cfg = Config.cargar(a.get("config", "config/ads.properties"));
        String id = a.get("id", "GUI-" + Util.nonce(3));
        String nodoTxt = a.get("nodo", null);
        DirNodo nodo = nodoTxt != null ? DirNodo.parse(nodoTxt) : cfg.nodos().get(0);
        CelulaCliente c = new CelulaCliente(id, cfg, nodo);
        c.pos = a.get("pos", null);
        c.arrancar();
        if (!a.flag("sin-gui") && !GraphicsEnvironment.isHeadless()) {
            VentanaCalculadora.mostrar(c, a.get("guion", null));
        }
    }

    public void alEvento(Consumer<EventoSolicitud> o) {
        oyentes.add(o);
    }

    public void alMensaje(Consumer<Mensaje> o) {
        oyentesMensajes.add(o);
    }

    private void notificar(EventoSolicitud ev) {
        for (Consumer<EventoSolicitud> o : oyentes) {
            try {
                o.accept(ev);
            } catch (RuntimeException ignored) {
                // la interfaz no debe romper el protocolo
            }
        }
    }

    @Override
    protected void alConectar() {
        enviar(Mensaje.de(CC.CONSULTA_TOPOLOGIA)); // llenar el directorio sin esperar al siguiente latido
    }

    @Override
    protected void completarLatido(Mensaje m) {
        m.con("pos", pos).con("minAck", minAck);
    }

    // ------------------------------------------------------------------ API

    /** Crea y difunde una solicitud. Se puede llamar desde cualquier hilo. */
    public EventoSolicitud solicitar(int cc, String operacion, String simbolo, List<String> operandos) {
        String folio = id + "-" + marca + "-" + String.format("%04d", secFolio.incrementAndGet());
        EventoSolicitud ev = new EventoSolicitud(folio, id, cc, operacion, simbolo, operandos, minAck);
        bucle.execute(seguro(() -> iniciar(ev)));
        return ev;
    }

    /** Operaciones vistas alguna vez en los latidos (se recuerdan aunque hoy nadie tenga el gen). */
    private final Map<String, Directorio.InfoGen> conocidas = new java.util.concurrent.ConcurrentHashMap<>();

    /** CC y símbolo para una operación por nombre (descubierto en latidos, recordado o estándar). */
    public Object[] operacion(String nombre) {
        for (Directorio.InfoGen g : directorio.operaciones().values()) {
            conocidas.put(g.nombre, g);
        }
        Directorio.InfoGen g = conocidas.get(nombre.toUpperCase(java.util.Locale.ROOT));
        if (g != null) {
            return new Object[]{g.nombre, g.cc, g.simbolo};
        }
        for (Object[] e : ESTANDAR) {
            if (((String) e[0]).equalsIgnoreCase(nombre)) {
                return e;
            }
        }
        return null;
    }

    /** Operaciones conocidas (descubiertas ahora o antes), por CC. */
    public Map<Integer, Directorio.InfoGen> operacionesConocidas() {
        for (Directorio.InfoGen g : directorio.operaciones().values()) {
            conocidas.put(g.nombre, g);
        }
        Map<Integer, Directorio.InfoGen> r = new java.util.TreeMap<>();
        for (Directorio.InfoGen g : conocidas.values()) {
            r.put(g.cc, g);
        }
        return r;
    }

    /** Envía n operaciones aleatorias a la vez (escenario de solicitudes simultáneas). */
    public List<EventoSolicitud> rafaga(int n) {
        List<EventoSolicitud> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Object[] op = ESTANDAR[Util.azar(ESTANDAR.length)];
            Object[] real = operacion((String) op[0]);
            int a = 1 + Util.azar(99);
            int b = 1 + Util.azar(20);
            r.add(solicitar((Integer) real[1], (String) real[0], (String) real[2], List.of(String.valueOf(a), String.valueOf(b))));
        }
        return r;
    }

    /** Pide a todas las calculadoras del Campo de Datos lanzar una ráfaga en el mismo instante. */
    public void rafagaGlobal(int n) {
        long en = Util.ahora() + 900;
        enviar(Mensaje.de(CC.DEMO_RAFAGA).con("n", n).con("en", en));
        log.evento("Ráfaga sincronizada solicitada a todas las calculadoras (" + n + " operaciones cada una)");
        bucle.schedule(seguro(() -> rafaga(n)), 900, TimeUnit.MILLISECONDS);
    }

    public List<EventoSolicitud> eventos() {
        try {
            return bucle.submit(() -> new ArrayList<>(eventos.values())).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------ protocolo de foliado (cliente)

    private void iniciar(EventoSolicitud ev) {
        for (Directorio.InfoCelula c : directorio.proveedores(ev.cc)) {
            ev.esperados.add(c.id);
        }
        eventos.put(ev.folio, ev);
        while (eventos.size() > 400) {
            String primero = eventos.keySet().iterator().next();
            eventos.remove(primero);
        }
        Mensaje m = Mensaje.de(ev.cc).conFolio(ev.folio).con("operacion", ev.operacion).con("operandos", ev.operandos)
                .con("minAck", ev.minAck).con("firma", ev.firma).con("esperados", ev.esperados.size());
        if (!enviar(m)) {
            ev.fase = EventoSolicitud.Fase.FALLIDO;
            ev.error = "sin conexión con el Campo de Datos";
            ev.tFin = Util.ahora();
            ev.anotar("FALLIDO: " + ev.error);
            notificar(ev);
            return;
        }
        ev.anotar("INICIO  CC " + ev.cc + " (" + ev.operacion + ") " + ev.expresion() + "  firma " + ev.firma
                + "  Min. # ACK " + ev.minAck + "  esperadas " + ev.esperados.size() + " " + ev.esperados);
        ev.temporizador = bucle.schedule(seguro(() -> finEsperaAck(ev)), timeoutAck, TimeUnit.MILLISECONDS);
        log.info("INICIO " + ev.folio + ": " + ev.operacion + " " + ev.expresion());
        notificar(ev);
    }

    @Override
    protected void alRecibir(Mensaje m) {
        for (Consumer<Mensaje> o : oyentesMensajes) {
            o.accept(m);
        }
        switch (m.cc) {
            case CC.ACK -> alAck(m);
            case CC.RESULTADO -> alResultado(m);
            case CC.DEMO_RAFAGA -> {
                long espera = Math.max(0, Math.min(3000, m.num("en", 0) - Util.ahora()));
                int n = (int) Math.max(1, Math.min(20, m.num("n", 5)));
                log.evento("Ráfaga sincronizada pedida por " + m.origen + ": " + n + " operaciones en " + espera + " ms");
                bucle.schedule(seguro(() -> rafaga(n)), espera, TimeUnit.MILLISECONDS);
            }
            default -> {
                // los demás CC no le interesan a una calculadora
            }
        }
    }

    private void alAck(Mensaje m) {
        EventoSolicitud ev = m.folio == null ? null : eventos.get(m.folio);
        if (ev == null) {
            ajenos.incrementAndGet(); // folio de otra GUI (o de otro tipo de evento): se ignora
            return;
        }
        propios.incrementAndGet();
        boolean tardio = ev.fase != EventoSolicitud.Fase.INICIADO;
        EventoSolicitud.Ack a = new EventoSolicitud.Ack(m, ev.t0, tardio);
        synchronized (ev) {
            for (EventoSolicitud.Ack x : ev.acks) {
                if (x.celula.equals(a.celula)) {
                    return;
                }
            }
            ev.acks.add(a);
        }
        ev.anotar((tardio ? "ACK tardío" : "ACK #" + ev.numAcks() + " (mín " + ev.minAck + ")") + " de " + a.celula + " ("
                + a.gen + " v" + a.version + ")  ida: " + Mensaje.trayectoria(a.huellaIda) + " → " + a.celula
                + "  vuelta: " + Mensaje.trayectoria(a.huellaVuelta));
        if (!tardio && !ev.esperados.isEmpty() && ev.numAcks() >= ev.minAck && todosRespondieron(ev)) {
            confirmar(ev);
        }
        notificar(ev);
    }

    private boolean todosRespondieron(EventoSolicitud ev) {
        for (String e : ev.esperados) {
            boolean ok = false;
            for (EventoSolicitud.Ack a : ev.copiaAcks()) {
                if (a.celula.equals(e)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void finEsperaAck(EventoSolicitud ev) {
        if (ev.fase != EventoSolicitud.Fase.INICIADO) {
            return;
        }
        if (ev.numAcks() >= ev.minAck) {
            confirmar(ev);
        } else {
            ev.ausentes = ausentes(ev);
            ev.fase = EventoSolicitud.Fase.RECHAZADO;
            ev.error = "Min. # de ACK no alcanzado (" + ev.numAcks() + "/" + ev.minAck + ")";
            ev.anotar("RECHAZADO: " + ev.error + (ev.ausentes.isEmpty() ? "" : "; sin respuesta: " + ev.ausentes));
            finalizar(ev, "RECHAZADO");
        }
        notificar(ev);
    }

    private List<String> ausentes(EventoSolicitud ev) {
        List<String> r = new ArrayList<>();
        for (String e : ev.esperados) {
            boolean ok = false;
            for (EventoSolicitud.Ack a : ev.copiaAcks()) {
                if (a.celula.equals(e)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                r.add(e);
            }
        }
        return r;
    }

    private void confirmar(EventoSolicitud ev) {
        if (ev.temporizador != null) {
            ev.temporizador.cancel(false);
        }
        ev.fase = EventoSolicitud.Fase.CONFIRMADO;
        ev.tConfirmado = Util.ahora();
        ev.ausentes = ausentes(ev);
        ev.anotar("CONFIRMADO: ACK " + ev.numAcks() + " ≥ Min. # ACK " + ev.minAck
                + (ev.ausentes.isEmpty() ? " (respondieron todas las esperadas)" : "; AUSENTES (sin ACK): " + ev.ausentes));
        asignar(ev);
    }

    /** Elige la ejecutora: versión de gen más reciente y, a igualdad, el ACK más rápido. */
    private void asignar(EventoSolicitud ev) {
        List<EventoSolicitud.Ack> cand = new ArrayList<>();
        for (EventoSolicitud.Ack a : ev.copiaAcks()) {
            if (!ev.probados.contains(a.celula)) {
                cand.add(a);
            }
        }
        cand.sort(Comparator.<EventoSolicitud.Ack, String>comparing(a -> a.version == null ? "" : a.version,
                (x, y) -> Util.compararVersiones(y, x)).thenComparingLong(a -> a.t));
        if (cand.isEmpty() || ev.intento > reintentos) {
            fallar(ev, "ninguna célula ejecutora respondió (" + ev.probados + ")");
            return;
        }
        EventoSolicitud.Ack elegido = cand.get(0);
        ev.ejecutor = elegido.celula;
        ev.intento++;
        ev.probados.add(elegido.celula);
        ev.fase = EventoSolicitud.Fase.PROCESANDO;
        ev.tProcesando = Util.ahora();
        List<String> ids = new ArrayList<>();
        for (EventoSolicitud.Ack a : ev.copiaAcks()) {
            ids.add(a.celula);
        }
        enviar(Mensaje.de(CC.EVENTO_CONFIRMADO).conFolio(ev.folio).con("ejecutor", ev.ejecutor).con("intento", ev.intento)
                .con("acks", ev.numAcks()).con("minAck", ev.minAck).con("confirmaron", ids).con("ausentes", ev.ausentes));
        ev.anotar("PROCESANDO: ejecutora " + ev.ejecutor + " (" + elegido.gen + " v" + elegido.version + ", intento "
                + ev.intento + ")");
        int intento = ev.intento;
        ev.temporizador = bucle.schedule(seguro(() -> finEsperaResultado(ev, intento)), timeoutResultado,
                TimeUnit.MILLISECONDS);
        notificar(ev);
    }

    private void finEsperaResultado(EventoSolicitud ev, int intento) {
        if (ev.fase != EventoSolicitud.Fase.PROCESANDO || ev.intento != intento) {
            return;
        }
        ev.anotar("Sin RESULTADO de " + ev.ejecutor + " en " + timeoutResultado + " ms: se reasigna (tolerancia a fallas)");
        asignar(ev);
    }

    private void alResultado(Mensaje m) {
        EventoSolicitud ev = m.folio == null ? null : eventos.get(m.folio);
        if (ev == null) {
            ajenos.incrementAndGet(); // resultado de una solicitud de otra GUI: NO se muestra
            return;
        }
        propios.incrementAndGet();
        String celula = m.str("celula", m.origen);
        if (ev.fase != EventoSolicitud.Fase.PROCESANDO || !celula.equals(ev.ejecutor)) {
            ev.anotar("Resultado de " + celula + " ignorado (no es la ejecutora vigente)");
            return;
        }
        if (!ev.firma.equals(m.str("firma"))) {
            ev.anotar("Resultado de " + celula + " descartado: la firma no coincide con la de la solicitud");
            return;
        }
        if (ev.temporizador != null) {
            ev.temporizador.cancel(false);
        }
        String estado = m.str("estado", "ERROR");
        if ("NO_DISPONIBLE".equals(estado)) {
            ev.anotar(celula + " ya no tiene el gen (" + m.str("error") + "): se reasigna");
            asignar(ev);
            return;
        }
        ev.genResultado = m.str("gen");
        ev.versionResultado = m.str("version");
        ev.huellaResultadoIda = m.listaTexto("huellaSolicitud");
        ev.huellaResultadoVuelta = new ArrayList<>(m.huella);
        ev.tFin = Util.ahora();
        if ("OK".equals(estado)) {
            ev.valor = m.str("valor");
            ev.fase = EventoSolicitud.Fase.COMPLETADO;
            ev.anotar("RESULTADO " + ev.expresion() + " = " + ev.valor + "  por " + celula + " (" + ev.genResultado + " v"
                    + ev.versionResultado + ")  firma verificada  vuelta: " + Mensaje.trayectoria(ev.huellaResultadoVuelta));
            finalizar(ev, "COMPLETADO");
        } else {
            ev.error = m.str("error", "error desconocido");
            ev.fase = EventoSolicitud.Fase.ERROR;
            ev.anotar("RESULTADO con error del gen: " + ev.error + "  (" + celula + ")");
            finalizar(ev, "ERROR");
        }
        notificar(ev);
    }

    private void fallar(EventoSolicitud ev, String motivo) {
        ev.fase = EventoSolicitud.Fase.FALLIDO;
        ev.error = motivo;
        ev.anotar("FALLIDO: " + motivo);
        finalizar(ev, "FALLIDO");
        notificar(ev);
    }

    private void finalizar(EventoSolicitud ev, String estado) {
        if (ev.tFin == 0) {
            ev.tFin = Util.ahora();
        }
        enviar(Mensaje.de(CC.EVENTO_FIN).conFolio(ev.folio).con("estado", estado).con("ejecutor", ev.ejecutor));
        ev.anotar("FIN (" + estado + ") en " + (ev.tFin - ev.t0) + " ms");
        log.info("FIN " + ev.folio + " " + estado + (ev.valor != null ? " = " + ev.valor : "")
                + (ev.error != null ? " (" + ev.error + ")" : ""));
    }
}
