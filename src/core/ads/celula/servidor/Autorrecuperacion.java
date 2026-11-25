package ads.celula.servidor;

import ads.celula.Directorio;
import ads.celula.Directorio.InfoCelula;
import ads.celula.Directorio.InfoGen;
import ads.comun.CC;
import ads.comun.Mensaje;
import ads.comun.Util;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Protocolo de autorrecuperación, ejecutado por CADA célula servidor (no hay controlador central).
 *
 * <b>Detección.</b> Cuando el Directorio declara MUERTA a una célula servidor que tenía genes activos,
 * esa capacidad perdida se registra como una <i>deuda de recuperación</i> (una por célula perdida).
 * La deuda se anuncia con FALLA_DETECTADA y viaja en los latidos, de modo que todas las células la conocen.
 * Una baja ordenada (CELULA_BAJA) o la baja de un gen NO generan deuda: no son fallas.
 *
 * <b>Nivel requerido.</b> Para cada servicio: requerido = instancias activas + deudas abiertas.
 * Las fallas no reducen el nivel requerido; las altas/bajas intencionales sí lo cambian.
 *
 * <b>Quién clona.</b> Todas las células ordenan a las candidatas con el mismo criterio determinista
 * (tiene alguno de los genes perdidos, está en el mismo nodo, menor generación, menor id). La candidata
 * de rango r actúa sólo si la deuda sigue sin reservar tras confirmación + r·paso ms; así, si la primera
 * no puede (enfriamiento, falla, límite), la siguiente toma el relevo sin depender de nadie.
 *
 * <b>Cómo.</b> CLON_RESERVA (todas apartan la deuda) → espera corta para detectar reservas rivales
 * (gana el padre de id menor) → se crea un proceso nuevo con la célula base, que carga desde disco los
 * genes heredados → CLON_LISTO y latido con "reemplaza" = deuda pagada.
 *
 * <b>Anti-cáncer.</b> (1) a lo más un clon por célula perdida (la deuda se paga una sola vez);
 * (2) reserva distribuida y desempate determinista; (3) un clon por ronda y enfriamiento por célula;
 * (4) madurez mínima antes de poder clonar; (5) límite de generaciones (telómeros);
 * (6) máximo de instancias por servicio y de células en total; (7) apoptosis: un clon redundante
 * (o cuyo original regresó) se retira solo; (8) congelamiento si se pierde más de la mitad de golpe
 * (posible partición local).
 */
final class Autorrecuperacion {

    enum EstadoDeuda { PENDIENTE, RESERVADA, PAGADA, CANCELADA, IRRECUPERABLE }

    static final class Deuda {
        final String celula;
        final List<InfoGen> genes;
        final String nodo;
        final String dirNodo;
        final int generacion;
        final String pos;
        final long inicioCelula;
        final long detectada;
        EstadoDeuda estado = EstadoDeuda.PENDIENTE;
        String reservadaPor;
        String clon;
        String folio;
        long reservaExpira;
        int intentos;
        String pagadaPor;
        String motivo;
        long cerrada;
        boolean anunciada;
        boolean avisoLimite;

        Deuda(String celula, List<InfoGen> genes, String nodo, String dirNodo, int generacion, String pos,
              long inicioCelula, long detectada) {
            this.celula = celula;
            this.genes = genes;
            this.nodo = nodo;
            this.dirNodo = dirNodo;
            this.generacion = generacion;
            this.pos = pos;
            this.inicioCelula = inicioCelula;
            this.detectada = detectada;
        }

        boolean abierta() {
            return estado == EstadoDeuda.PENDIENTE || estado == EstadoDeuda.RESERVADA;
        }

        boolean incluye(String gen) {
            for (InfoGen g : genes) {
                if (g.nombre.equalsIgnoreCase(gen)) {
                    return true;
                }
            }
            return false;
        }

        String genesTexto() {
            List<String> t = new ArrayList<>();
            for (InfoGen g : genes) {
                t.add(g.etiqueta());
            }
            return String.join(", ", t);
        }

        Map<String, Object> aMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("celula", celula);
            List<Object> gs = new ArrayList<>();
            for (InfoGen g : genes) {
                gs.add(g.aMapa());
            }
            m.put("genes", gs);
            m.put("nodo", nodo);
            m.put("dirNodo", dirNodo);
            m.put("generacion", generacion);
            m.put("pos", pos);
            m.put("inicioCelula", inicioCelula);
            m.put("estado", estado.name());
            m.put("reservadaPor", reservadaPor);
            m.put("clon", clon);
            m.put("intentos", intentos);
            m.put("motivo", motivo);
            return m;
        }

        static Deuda deMapa(Map<String, Object> m, long ahora) {
            List<InfoGen> gs = new ArrayList<>();
            if (m.get("genes") instanceof List<?> l) {
                for (Object o : l) {
                    InfoGen g = InfoGen.de(o);
                    if (g != null) {
                        gs.add(g);
                    }
                }
            }
            Object gen = m.get("generacion");
            Object ini = m.get("inicioCelula");
            return new Deuda(String.valueOf(m.get("celula")), gs, (String) m.get("nodo"), (String) m.get("dirNodo"),
                    gen instanceof Number n ? n.intValue() : 0, (String) m.get("pos"),
                    ini instanceof Number n ? n.longValue() : 0, ahora);
        }
    }

    private final CelulaServidor celula;
    private final Map<String, Deuda> deudas = new LinkedHashMap<>();
    private final boolean habilitada;
    private final long paso;
    private final long confirmacion;
    private final long reservaMs;
    private final long espera;
    private final long enfriamiento;
    private final long maduracion;
    private final int maxGeneracion;
    private final int maxInstancias;
    private final int maxCelulas;
    private final int maxIntentos;
    private long ultimaClonacion;
    private long congeladaHasta;
    private final Deque<Long> muertesRecientes = new ArrayDeque<>();
    private boolean apagando;
    private int secuencia;
    private long ultimaRevisionApoptosis;

    Autorrecuperacion(CelulaServidor celula) {
        this.celula = celula;
        var cfg = celula.cfg;
        habilitada = cfg.bool("clonacion.habilitada", true);
        paso = cfg.largo("clonacion.paso.ms", 3000);
        confirmacion = cfg.largo("clonacion.confirmacion.ms", 2500);
        reservaMs = cfg.largo("clonacion.reserva.ms", 20000);
        espera = cfg.largo("clonacion.espera.ms", 400);
        enfriamiento = cfg.largo("clonacion.enfriamiento.ms", 8000);
        maduracion = cfg.largo("clonacion.maduracion.ms", 6000);
        maxGeneracion = cfg.entero("clonacion.maxGeneracion", 5);
        maxInstancias = cfg.entero("clonacion.maxInstanciasPorServicio", 8);
        maxCelulas = cfg.entero("clonacion.maxCelulas", 40);
        maxIntentos = cfg.entero("clonacion.maxIntentos", 3);
    }

    void apagar() {
        apagando = true;
    }

    // ------------------------------------------------------------------ detección

    /** El Directorio declaró muerta a una célula. */
    void alMorir(InfoCelula c) {
        long ahora = Util.ahora();
        if (!c.esServidor()) {
            celula.log.aviso("Falla detectada: " + c.id + " [" + c.clase + "] dejó de responder (no ofrece servicios)");
            return;
        }
        registrarMuerte(ahora);
        if (c.genes.isEmpty()) {
            celula.log.aviso("Falla detectada: " + c.id + " dejó de responder; no tenía genes activos, no hay capacidad que recuperar");
            return;
        }
        Deuda d = deudas.get(c.id);
        if (d != null && d.abierta()) {
            return;
        }
        Directorio.InfoNodo n = c.nodo == null ? null : celula.directorio.nodo(c.nodo);
        d = new Deuda(c.id, new ArrayList<>(c.genes), c.nodo, n == null ? null : n.direccion(), c.generacion, c.pos,
                c.inicio, ahora);
        deudas.put(c.id, d);
        celula.log.evento("FALLA DETECTADA: " + c.id + " (" + d.genesTexto() + ") en " + c.nodo
                + " — disponibilidad: " + disponibilidad(d));
        Deuda fd = d;
        celula.bucle().schedule(() -> {
            if (!fd.anunciada && fd.abierta()) {
                fd.anunciada = true;
                celula.enviar(Mensaje.de(CC.FALLA_DETECTADA).con("deuda", fd.aMapa()));
            }
        }, Util.azar(600), TimeUnit.MILLISECONDS);
    }

    /** Otra célula anunció una falla: si no la conocíamos, adoptamos la deuda. */
    void alFallaAnunciada(Mensaje m) {
        Map<String, Object> dm = m.mapa("deuda");
        String cid = String.valueOf(dm.get("celula"));
        Deuda d = deudas.get(cid);
        if (d != null) {
            d.anunciada = true;
            return;
        }
        if (cid.equals(celula.id)) {
            return;
        }
        InfoCelula c = celula.directorio.celula(cid);
        if (c != null && c.viva() && Util.ahora() - c.ultimaVida < 1500) {
            return; // nosotros la vemos viva: no adoptamos una deuda dudosa
        }
        d = Deuda.deMapa(dm, Util.ahora());
        d.anunciada = true;
        if (d.genes.isEmpty()) {
            return;
        }
        deudas.put(cid, d);
        celula.log.evento("Falla de " + cid + " anunciada por " + m.origen + " — deuda de recuperación registrada ("
                + d.genesTexto() + ")");
    }

    /** Latido de cualquier célula: paga deudas (clones) o las cancela (falsa alarma). */
    void alLatido(Mensaje m) {
        String cid = m.str("celula", m.origen);
        String reemplaza = m.str("reemplaza");
        if (reemplaza != null) {
            Deuda d = deudas.get(reemplaza);
            if (d != null && d.estado != EstadoDeuda.PAGADA) {
                d.estado = EstadoDeuda.PAGADA;
                d.pagadaPor = cid;
                d.cerrada = Util.ahora();
                celula.log.evento("RECUPERACIÓN COMPLETA: " + cid + " reemplaza a " + reemplaza + " ("
                        + d.genesTexto() + ") — disponibilidad restablecida");
            }
        }
        Deuda propia = deudas.get(cid);
        if (propia != null && propia.abierta()) {
            propia.estado = EstadoDeuda.CANCELADA;
            propia.motivo = "falsa alarma: la célula volvió a dar señales";
            propia.cerrada = Util.ahora();
            celula.log.evento("Deuda de " + cid + " cancelada: la célula volvió a dar señales (falsa alarma)");
        }
    }

    void alReserva(Mensaje m) {
        String cid = m.str("deuda");
        Deuda d = deudas.get(cid);
        if (d == null) {
            d = Deuda.deMapa(m.mapa("info"), Util.ahora());
            if (d.genes.isEmpty()) {
                return;
            }
            d.anunciada = true;
            deudas.put(cid, d);
        }
        if (!d.abierta()) {
            return;
        }
        String padre = m.str("padre");
        long ahora = Util.ahora();
        boolean libre = d.reservadaPor == null || ahora > d.reservaExpira;
        if (libre || padre.equals(d.reservadaPor) || padre.compareTo(d.reservadaPor) < 0) {
            boolean yoCedo = celula.id.equals(d.reservadaPor) && !padre.equals(celula.id);
            d.reservadaPor = padre;
            d.clon = m.str("clon");
            d.folio = m.folio;
            d.reservaExpira = ahora + m.num("expira", reservaMs);
            d.estado = EstadoDeuda.RESERVADA;
            celula.log.info("Reserva de clonación: " + padre + " reemplazará a " + cid + " con el clon " + d.clon);
            if (yoCedo) {
                celula.log.evento("Cedo la clonación de " + cid + " a " + padre + " (regla de desempate: id menor)");
            }
        }
    }

    void alCancela(Mensaje m) {
        Deuda d = deudas.get(m.str("deuda"));
        if (d != null && d.estado == EstadoDeuda.RESERVADA && m.str("padre", "").equals(d.reservadaPor)) {
            d.reservadaPor = null;
            d.estado = EstadoDeuda.PENDIENTE;
            d.intentos++;
            celula.log.aviso("Reserva de " + m.str("deuda") + " cancelada por " + m.origen + ": " + m.str("motivo"));
        }
    }

    void alClonListo(Mensaje m) {
        Deuda d = deudas.get(m.str("deuda"));
        if (d == null) {
            return;
        }
        if (m.bool("ok")) {
            if (d.estado != EstadoDeuda.PAGADA) {
                d.estado = EstadoDeuda.PAGADA;
                d.pagadaPor = m.str("clon", m.origen);
                d.cerrada = Util.ahora();
                celula.log.evento("RECUPERACIÓN COMPLETA: " + d.pagadaPor + " reemplaza a " + d.celula + " ("
                        + d.genesTexto() + ")");
            }
        } else if (d.abierta()) {
            d.reservadaPor = null;
            d.estado = EstadoDeuda.PENDIENTE;
            d.intentos++;
            celula.log.aviso("El clon " + m.origen + " no fue viable: " + m.str("motivo"));
        }
    }

    // ------------------------------------------------------------------ decisión

    /** Se ejecuta cada pulso (500 ms) en el hilo de la célula. */
    void evaluar() {
        long ahora = Util.ahora();
        revisarApoptosis(ahora);
        for (Deuda d : deudas.values()) {
            if (d.estado == EstadoDeuda.RESERVADA && ahora > d.reservaExpira) {
                d.intentos++;
                d.reservadaPor = null;
                if (d.intentos >= maxIntentos) {
                    d.estado = EstadoDeuda.IRRECUPERABLE;
                    d.motivo = "se agotaron " + maxIntentos + " intentos de clonación";
                    d.cerrada = ahora;
                    celula.log.error("Deuda de " + d.celula + " irrecuperable: " + d.motivo);
                } else {
                    d.estado = EstadoDeuda.PENDIENTE;
                    celula.log.aviso("La reserva para reemplazar a " + d.celula + " expiró sin que apareciera el clon "
                            + d.clon + " (intento " + d.intentos + ")");
                }
            }
        }
        deudas.values().removeIf(d -> !d.abierta() && d.cerrada > 0
                && ahora - d.cerrada > (d.estado == EstadoDeuda.IRRECUPERABLE ? 300_000 : 30_000));

        if (!habilitada || apagando || !celula.conectada()) {
            return;
        }
        if (ahora < congeladaHasta) {
            return;
        }
        boolean yoPuedo = ahora - celula.inicio >= maduracion && celula.generacion < maxGeneracion
                && ahora - ultimaClonacion >= enfriamiento;
        if (!yoPuedo) {
            return;
        }
        for (Deuda d : deudas.values()) {
            if (d.estado != EstadoDeuda.PENDIENTE) {
                continue;
            }
            if (d.generacion + 1 > maxGeneracion) {
                d.estado = EstadoDeuda.IRRECUPERABLE;
                d.motivo = "límite de generaciones (telómeros): la línea de " + d.celula + " ya va en la generación "
                        + d.generacion;
                d.cerrada = ahora;
                celula.log.aviso("ANTI-CÁNCER: no se clona a " + d.celula + " — " + d.motivo);
                continue;
            }
            int rango = rango(d, ahora);
            if (rango < 0) {
                continue;
            }
            if (ahora - d.detectada < confirmacion + rango * paso) {
                continue;
            }
            String limite = limite(d);
            if (limite != null) {
                d.motivo = "bloqueada: " + limite;
                if (!d.avisoLimite) {
                    d.avisoLimite = true;
                    celula.log.aviso("ANTI-CÁNCER: clonación para reemplazar a " + d.celula + " bloqueada — " + limite);
                }
                continue;
            }
            reservarYClonar(d, ahora, rango);
            return; // a lo más un clon por ronda: recuperación gradual
        }
    }

    /** Posición de esta célula entre las candidatas a clonar (todas calculan el mismo orden). */
    private int rango(Deuda d, long ahora) {
        List<InfoCelula> cand = new ArrayList<>();
        for (InfoCelula c : celula.directorio.servidoresVivos()) {
            if (c.conLatido && c.inicio > 0 && ahora - c.inicio >= maduracion && c.generacion < maxGeneracion
                    && !c.id.equals(d.celula)) {
                cand.add(c);
            }
        }
        InfoCelula yo = celula.comoInfo();
        cand.add(yo);
        cand.sort(Comparator.<InfoCelula>comparingInt(c -> tieneAlguno(c, d) ? 0 : 1)
                .thenComparingInt(c -> Objects.equals(c.nodo, d.nodo) ? 0 : 1)
                .thenComparingInt(c -> c.generacion)
                .thenComparing(c -> c.id));
        for (int i = 0; i < cand.size(); i++) {
            if (cand.get(i).id.equals(celula.id)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean tieneAlguno(InfoCelula c, Deuda d) {
        for (InfoGen g : d.genes) {
            if (c.tieneGen(g.nombre)) {
                return true;
            }
        }
        return false;
    }

    private String limite(Deuda d) {
        int reservas = 0;
        for (Deuda x : deudas.values()) {
            if (x.estado == EstadoDeuda.RESERVADA) {
                reservas++;
            }
        }
        int servidores = celula.directorio.servidoresVivos().size() + 1 + reservas;
        if (servidores >= maxCelulas) {
            return "se alcanzó el máximo de " + maxCelulas + " células servidor";
        }
        for (InfoGen g : d.genes) {
            int n = instancias(g.nombre);
            for (Deuda x : deudas.values()) {
                if (x.estado == EstadoDeuda.RESERVADA && x.incluye(g.nombre)) {
                    n++;
                }
            }
            if (n >= maxInstancias) {
                return "el servicio " + g.nombre + " ya tiene " + n + " instancias (máximo " + maxInstancias + ")";
            }
        }
        return null;
    }

    private int instancias(String gen) {
        int n = celula.genes.porNombre(gen) != null ? 1 : 0;
        for (InfoCelula c : celula.directorio.servidoresVivos()) {
            if (c.tieneGen(gen)) {
                n++;
            }
        }
        return n;
    }

    /** Texto "SUMA 3/4" para la bitácora. */
    private String disponibilidad(Deuda nueva) {
        List<String> t = new ArrayList<>();
        for (InfoGen g : nueva.genes) {
            int act = instancias(g.nombre);
            int deu = 0;
            for (Deuda x : deudas.values()) {
                if (x.abierta() && x.incluye(g.nombre)) {
                    deu++;
                }
            }
            t.add(g.nombre + " " + act + "/" + (act + deu));
        }
        return String.join(", ", t);
    }

    private void reservarYClonar(Deuda d, long ahora, int rango) {
        int genClon = Math.max(celula.generacion, d.generacion) + 1;
        String base = d.celula.replaceAll("-R\\d+[a-z]{2}$", "");
        String clonId = base + "-R" + genClon + Util.letras(2);
        String folio = celula.id + "#CLON-" + (++secuencia);
        Directorio.InfoNodo n = d.nodo == null ? null : celula.directorio.nodo(d.nodo);
        boolean nodoVivo = n != null && !n.caido && n.puerto > 0;
        String destino = nodoVivo ? n.direccion() : celula.dirActual.direccion();
        String nodoDestino = nodoVivo ? n.id : celula.nodoActual;
        d.estado = EstadoDeuda.RESERVADA;
        d.motivo = null;
        d.reservadaPor = celula.id;
        d.clon = clonId;
        d.folio = folio;
        d.reservaExpira = ahora + reservaMs;
        celula.log.evento("CLONACIÓN [inicio] folio " + folio + ": soy la candidata #" + rango + " y reservo el reemplazo de "
                + d.celula + " → clon " + clonId + " (genes " + d.genesTexto() + ", generación " + genClon
                + ", nodo " + nodoDestino + ")");
        List<Object> gs = new ArrayList<>();
        for (InfoGen g : d.genes) {
            gs.add(g.aMapa());
        }
        celula.enviar(Mensaje.de(CC.CLON_RESERVA).conFolio(folio).con("deuda", d.celula).con("padre", celula.id)
                .con("clon", clonId).con("genes", gs).con("nodo", nodoDestino).con("generacion", genClon)
                .con("expira", reservaMs).con("info", d.aMapa()));
        celula.bucle().schedule(celula.seguroPublico(() -> engendrar(d, folio, clonId, destino, genClon)), espera,
                TimeUnit.MILLISECONDS);
    }

    private void engendrar(Deuda d, String folio, String clonId, String destino, int genClon) {
        if (!celula.id.equals(d.reservadaPor) || !folio.equals(d.folio) || d.estado != EstadoDeuda.RESERVADA) {
            celula.log.info("CLONACIÓN [cancelada] folio " + folio + ": la reserva de " + d.celula + " quedó en manos de "
                    + d.reservadaPor);
            return;
        }
        List<String> specs = new ArrayList<>();
        for (InfoGen g : d.genes) {
            specs.add(g.nombre + ":" + g.version);
        }
        try {
            long pid = celula.lanzarClon(clonId, destino, String.join(",", specs), genClon, d.celula, folio, d.pos);
            ultimaClonacion = Util.ahora();
            celula.log.evento("CLONACIÓN [procesamiento] folio " + folio + ": proceso " + pid + " creado para " + clonId
                    + "; cargará desde disco " + String.join(", ", specs));
        } catch (Exception e) {
            celula.log.error("No se pudo crear el proceso clon: " + e.getMessage());
            d.reservadaPor = null;
            d.estado = EstadoDeuda.PENDIENTE;
            d.intentos++;
            celula.enviar(Mensaje.de(CC.CLON_CANCELA).conFolio(folio).con("deuda", d.celula).con("padre", celula.id)
                    .con("motivo", e.getMessage()));
        }
    }

    private void registrarMuerte(long ahora) {
        muertesRecientes.addLast(ahora);
        while (!muertesRecientes.isEmpty() && ahora - muertesRecientes.peekFirst() > 5000) {
            muertesRecientes.pollFirst();
        }
        int perdidas = muertesRecientes.size();
        int vivas = celula.directorio.servidoresVivos().size() + 1;
        if (perdidas >= 3 && perdidas * 2 > vivas + perdidas && ahora >= congeladaHasta) {
            congeladaHasta = ahora + 15000;
            celula.log.aviso("ANTI-CÁNCER: se perdieron " + perdidas + " células en 5 s (más de la mitad). "
                    + "Posible partición local: la clonación se congela 15 s");
        }
    }

    /** Apoptosis: un clon se retira si su original regresó o si otra réplica ya lo reemplaza. */
    private void revisarApoptosis(long ahora) {
        String r = celula.reemplaza;
        if (r == null || !celula.conectada() || ahora - celula.inicio < 4000 || ahora - ultimaRevisionApoptosis < 1000) {
            return;
        }
        ultimaRevisionApoptosis = ahora;
        InfoCelula original = celula.directorio.celula(r);
        if (original != null && original.viva() && original.conLatido) {
            celula.retirarse("APOPTOSIS: la célula " + r + " que reemplacé volvió a estar activa; sobro");
            return;
        }
        for (InfoCelula c : celula.directorio.servidoresVivos()) {
            if (r.equals(c.reemplaza) && c.conLatido && c.id.compareTo(celula.id) < 0) {
                celula.retirarse("APOPTOSIS: " + c.id + " ya reemplaza a " + r + "; esta réplica es redundante");
                return;
            }
        }
    }

    // ------------------------------------------------------------------ consulta

    List<Object> deudasAbiertas() {
        List<Object> r = new ArrayList<>();
        for (Deuda d : deudas.values()) {
            if (d.abierta() || d.estado == EstadoDeuda.IRRECUPERABLE) {
                r.add(d.aMapa());
            }
        }
        return r;
    }

    int abiertas() {
        int n = 0;
        for (Deuda d : deudas.values()) {
            if (d.abierta()) {
                n++;
            }
        }
        return n;
    }
}
