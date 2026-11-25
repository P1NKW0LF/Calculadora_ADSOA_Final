package ads.celula.cliente;

import ads.comun.Mensaje;
import ads.comun.Util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * Un EVENTO = una solicitud identificada por su folio, con cuatro fases observables en el Campo de Datos:
 * <pre>
 *  INICIO          SOLICITUD (CC de la operación, folio, firma, Min. # ACK)
 *  CONFIRMACIÓN    ACK de las células que tienen el gen  →  ACK ≥ Min. # ACK  →  EVENTO_CONFIRMADO(ejecutora)
 *  PROCESAMIENTO   la célula ejecutora corre su gen      →  RESULTADO(folio, firma)
 *  FINALIZACIÓN    EVENTO_FIN(folio, estado)              (también si se rechaza o falla)
 * </pre>
 */
public final class EventoSolicitud {

    public enum Fase { INICIADO, CONFIRMADO, PROCESANDO, COMPLETADO, ERROR, RECHAZADO, FALLIDO }

    public static final class Ack {
        public final String celula;
        public final String nodo;
        public final String gen;
        public final String version;
        public final List<String> huellaIda;
        public final List<String> huellaVuelta;
        public final long t;
        public final boolean tardio;

        Ack(Mensaje m, long t0, boolean tardio) {
            this.celula = m.str("celula", m.origen);
            this.nodo = m.str("nodo");
            this.gen = m.str("gen");
            this.version = m.str("version");
            this.huellaIda = m.listaTexto("huellaSolicitud");
            this.huellaVuelta = new ArrayList<>(m.huella);
            this.t = Util.ahora() - t0;
            this.tardio = tardio;
        }
    }

    public final String folio;
    public final String firma;
    public final int cc;
    public final String operacion;
    public final String simbolo;
    public final List<String> operandos;
    public final int minAck;
    public final long t0 = Util.ahora();
    public final String origen;

    public volatile Fase fase = Fase.INICIADO;
    public final Set<String> esperados = new LinkedHashSet<>();
    public final List<Ack> acks = new ArrayList<>();
    public List<String> ausentes = new ArrayList<>();
    public String ejecutor;
    public int intento;
    public final List<String> probados = new ArrayList<>();
    public String valor;
    public String error;
    public String genResultado;
    public String versionResultado;
    public List<String> huellaResultadoIda = List.of();
    public List<String> huellaResultadoVuelta = List.of();
    public long tConfirmado;
    public long tProcesando;
    public long tFin;
    private final List<String> traza = new ArrayList<>();
    ScheduledFuture<?> temporizador;

    EventoSolicitud(String folio, String origen, int cc, String operacion, String simbolo, List<String> operandos, int minAck) {
        this.folio = folio;
        this.origen = origen;
        this.cc = cc;
        this.operacion = operacion;
        this.simbolo = simbolo;
        this.operandos = List.copyOf(operandos);
        this.minAck = minAck;
        this.firma = Util.corto(Util.sha256(folio + "|" + cc + "|" + String.join(",", operandos) + "|" + origen + "|" + t0));
    }

    public synchronized void anotar(String linea) {
        traza.add("+" + String.format("%4d", Util.ahora() - t0) + " ms  " + linea);
    }

    public synchronized List<String> traza() {
        return new ArrayList<>(traza);
    }

    public synchronized int numAcks() {
        int n = 0;
        for (Ack a : acks) {
            if (!a.tardio) {
                n++;
            }
        }
        return n;
    }

    public synchronized List<Ack> copiaAcks() {
        return new ArrayList<>(acks);
    }

    public boolean cerrado() {
        return fase == Fase.COMPLETADO || fase == Fase.ERROR || fase == Fase.RECHAZADO || fase == Fase.FALLIDO;
    }

    public String expresion() {
        return String.join(" " + simbolo + " ", operandos);
    }

    public long duracion() {
        return (tFin > 0 ? tFin : Util.ahora()) - t0;
    }
}
