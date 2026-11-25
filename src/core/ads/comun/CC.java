package ads.comun;

/**
 * Códigos de Contenido (CC) del sistema ADS.
 *
 * En ADS los mensajes no llevan destinatario: viajan por todo el Campo de Datos
 * y cada célula decide si los atiende según su Código de Contenido (y, para los
 * eventos, según su folio).
 *
 * Rangos:
 *  0xx  protocolo de conexión (sólo en el enlace punto a punto, no se difunde)
 *  1xx  solicitudes de operación: el CC exacto lo declara cada gen en su gen.properties
 *       (genes de ejemplo: 101 SUMA, 102 RESTA, 103 MULTIPLICACION, 104 DIVISION, 105 POTENCIA)
 *  2xx  protocolo de foliado (ACK, confirmación, resultado, fin)
 *  3xx  membresía y topología (latidos, altas y bajas de células)
 *  4xx  gestión de servicios (genes) y administración
 *  5xx  autorrecuperación (detección de fallas y clonación)
 *  6xx  utilidades de demostración
 */
public final class CC {

    private CC() {
    }

    // ---- 0xx: protocolo de conexión ----
    public static final int HOLA_NODO = 1;
    public static final int HOLA_CELULA = 2;
    public static final int BIENVENIDA = 3;
    public static final int RECHAZO = 4;

    // ---- 1xx: solicitudes de operación (rango reservado a los genes) ----
    public static final int OPERACION_MIN = 100;
    public static final int OPERACION_MAX = 199;

    // ---- 2xx: foliado ----
    public static final int ACK = 201;
    public static final int EVENTO_CONFIRMADO = 202;
    public static final int RESULTADO = 203;
    public static final int EVENTO_FIN = 204;

    // ---- 3xx: membresía y topología ----
    public static final int LATIDO_CELULA = 301;
    public static final int LATIDO_NODO = 302;
    public static final int CELULA_CONECTADA = 303;
    public static final int CELULA_DESCONECTADA = 304;
    public static final int CELULA_BAJA = 305;
    public static final int CONSULTA_TOPOLOGIA = 306;

    // ---- 4xx: gestión de servicios ----
    public static final int ADMIN_ALTA = 401;
    public static final int ADMIN_BAJA = 402;
    public static final int ADMIN_MODIFICAR = 403;
    public static final int ADMIN_RESPUESTA = 404;
    public static final int ADMIN_SILENCIAR = 405;
    public static final int ADMIN_FALLA = 406;
    public static final int ADMIN_RETIRAR = 407;
    public static final int SOLICITUD_GEN = 408;
    public static final int GEN_BINARIO = 409;
    public static final int APAGAR = 499;

    // ---- 5xx: autorrecuperación ----
    public static final int FALLA_DETECTADA = 501;
    public static final int CLON_RESERVA = 502;
    public static final int CLON_CANCELA = 503;
    public static final int CLON_LISTO = 504;

    // ---- 6xx: demostración ----
    public static final int DEMO_RAFAGA = 601;

    public static boolean esOperacion(int cc) {
        return cc >= OPERACION_MIN && cc <= OPERACION_MAX;
    }

    public static boolean esAdministracion(int cc) {
        return cc >= 401 && cc <= 407;
    }

    public static String nombre(int cc) {
        return switch (cc) {
            case HOLA_NODO -> "HOLA_NODO";
            case HOLA_CELULA -> "HOLA_CELULA";
            case BIENVENIDA -> "BIENVENIDA";
            case RECHAZO -> "RECHAZO";
            case ACK -> "ACK";
            case EVENTO_CONFIRMADO -> "EVENTO_CONFIRMADO";
            case RESULTADO -> "RESULTADO";
            case EVENTO_FIN -> "EVENTO_FIN";
            case LATIDO_CELULA -> "LATIDO_CELULA";
            case LATIDO_NODO -> "LATIDO_NODO";
            case CELULA_CONECTADA -> "CELULA_CONECTADA";
            case CELULA_DESCONECTADA -> "CELULA_DESCONECTADA";
            case CELULA_BAJA -> "CELULA_BAJA";
            case CONSULTA_TOPOLOGIA -> "CONSULTA_TOPOLOGIA";
            case ADMIN_ALTA -> "ADMIN_ALTA";
            case ADMIN_BAJA -> "ADMIN_BAJA";
            case ADMIN_MODIFICAR -> "ADMIN_MODIFICAR";
            case ADMIN_RESPUESTA -> "ADMIN_RESPUESTA";
            case ADMIN_SILENCIAR -> "ADMIN_SILENCIAR";
            case ADMIN_FALLA -> "ADMIN_FALLA";
            case ADMIN_RETIRAR -> "ADMIN_RETIRAR";
            case SOLICITUD_GEN -> "SOLICITUD_GEN";
            case GEN_BINARIO -> "GEN_BINARIO";
            case APAGAR -> "APAGAR";
            case FALLA_DETECTADA -> "FALLA_DETECTADA";
            case CLON_RESERVA -> "CLON_RESERVA";
            case CLON_CANCELA -> "CLON_CANCELA";
            case CLON_LISTO -> "CLON_LISTO";
            case DEMO_RAFAGA -> "DEMO_RAFAGA";
            default -> esOperacion(cc) ? "SOLICITUD_OPERACION" : "CC_" + cc;
        };
    }
}
