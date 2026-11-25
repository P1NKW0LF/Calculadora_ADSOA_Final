package ads.celula;

import ads.comun.Mensaje;
import ads.comun.Util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Vista local y distribuida de la topología: cada célula construye la suya escuchando los latidos
 * (LATIDO_CELULA, LATIDO_NODO) y los avisos de conexión/desconexión que circulan por el Campo de Datos.
 * No existe un registro central: todas las vistas convergen porque todas oyen los mismos mensajes.
 *
 * Detección de fallas:
 *  - una célula pasa a SOSPECHOSA si su nodo avisa CELULA_DESCONECTADA o si lleva medio timeout sin latir;
 *  - pasa a MUERTA si tras la desconexión no vuelve a dar señales en el periodo de gracia
 *    (tiempo para reconectarse a otro nodo) o si supera el timeout completo sin latidos;
 *  - una baja ordenada (CELULA_BAJA) la deja RETIRADA: no es una falla y no genera recuperación.
 */
public final class Directorio {

    public enum Estado { VIVA, SOSPECHOSA, MUERTA, RETIRADA }

    /** Gen activo anunciado por una célula en su latido. */
    public static final class InfoGen {
        public final String nombre;
        public final String version;
        public final int cc;
        public final String simbolo;
        public final String descripcion;

        public InfoGen(String nombre, String version, int cc, String simbolo, String descripcion) {
            this.nombre = nombre;
            this.version = version;
            this.cc = cc;
            this.simbolo = simbolo;
            this.descripcion = descripcion;
        }

        public static InfoGen de(Object o) {
            if (!(o instanceof Map<?, ?> m)) {
                return null;
            }
            Object cc = m.get("cc");
            return new InfoGen(String.valueOf(m.get("nombre")), String.valueOf(m.get("version")),
                    cc instanceof Number n ? n.intValue() : 0,
                    m.get("simbolo") == null ? "?" : String.valueOf(m.get("simbolo")),
                    m.get("descripcion") == null ? "" : String.valueOf(m.get("descripcion")));
        }

        public Map<String, Object> aMapa() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nombre", nombre);
            m.put("version", version);
            m.put("cc", cc);
            m.put("simbolo", simbolo);
            m.put("descripcion", descripcion);
            return m;
        }

        public String etiqueta() {
            return nombre + " v" + version;
        }
    }

    public static final class InfoCelula {
        public final String id;
        public String clase = "?";
        public String nodo;
        public String host;
        public long pid;
        public List<InfoGen> genes = new ArrayList<>();
        public int generacion;
        public String padre;
        public String reemplaza;
        public boolean silenciada;
        public long inicio;
        public long ultimaVida;
        public long desconectadaEn;
        public String pos;
        public boolean conLatido;
        public Estado estado = Estado.VIVA;
        public long cambioEstado;
        public List<Object> deudas = new ArrayList<>();
        public Map<String, Object> stats = new LinkedHashMap<>();

        InfoCelula(String id) {
            this.id = id;
            this.ultimaVida = Util.ahora();
            this.cambioEstado = ultimaVida;
        }

        public boolean viva() {
            return estado == Estado.VIVA || estado == Estado.SOSPECHOSA;
        }

        public boolean esServidor() {
            return "SERVIDOR".equals(clase);
        }

        public boolean tieneGen(String nombre) {
            return gen(nombre) != null;
        }

        public InfoGen gen(String nombre) {
            for (InfoGen g : genes) {
                if (g.nombre.equalsIgnoreCase(nombre)) {
                    return g;
                }
            }
            return null;
        }

        public boolean atiende(int cc) {
            for (InfoGen g : genes) {
                if (g.cc == cc) {
                    return true;
                }
            }
            return false;
        }

        public String genesTexto() {
            if (genes.isEmpty()) {
                return "(sin genes)";
            }
            List<String> t = new ArrayList<>();
            for (InfoGen g : genes) {
                t.add(g.etiqueta());
            }
            return String.join(", ", t);
        }

        public InfoCelula copia() {
            InfoCelula c = new InfoCelula(id);
            c.clase = clase;
            c.nodo = nodo;
            c.host = host;
            c.pid = pid;
            c.genes = new ArrayList<>(genes);
            c.generacion = generacion;
            c.padre = padre;
            c.reemplaza = reemplaza;
            c.silenciada = silenciada;
            c.inicio = inicio;
            c.ultimaVida = ultimaVida;
            c.desconectadaEn = desconectadaEn;
            c.pos = pos;
            c.conLatido = conLatido;
            c.estado = estado;
            c.cambioEstado = cambioEstado;
            c.deudas = new ArrayList<>(deudas);
            c.stats = new LinkedHashMap<>(stats);
            return c;
        }
    }

    public static final class InfoNodo {
        public final String id;
        public String host;
        public int puerto;
        public List<String> vecinos = new ArrayList<>();
        public List<String> celulas = new ArrayList<>();
        public long ultimoLatido;
        public long inicio;
        public Map<String, Object> stats = new LinkedHashMap<>();
        public boolean caido;

        InfoNodo(String id) {
            this.id = id;
        }

        public InfoNodo copia() {
            InfoNodo n = new InfoNodo(id);
            n.host = host;
            n.puerto = puerto;
            n.vecinos = new ArrayList<>(vecinos);
            n.celulas = new ArrayList<>(celulas);
            n.ultimoLatido = ultimoLatido;
            n.inicio = inicio;
            n.stats = new LinkedHashMap<>(stats);
            n.caido = caido;
            return n;
        }

        public String direccion() {
            return host + ":" + puerto;
        }
    }

    /** Entrada para representar a la propia célula observadora (no se guarda en el directorio). */
    public static InfoCelula sintetica(String id, String clase, String nodo, long inicio) {
        InfoCelula c = new InfoCelula(id);
        c.clase = clase;
        c.nodo = nodo;
        c.inicio = inicio;
        c.conLatido = true;
        return c;
    }

    public record Revision(List<InfoCelula> muertas, List<InfoCelula> revividas, List<InfoNodo> nodosCaidos) {
    }

    private final String propio;
    private final long timeout;
    private final long gracia;
    private final Map<String, InfoCelula> celulas = new TreeMap<>();
    private final Map<String, InfoNodo> nodos = new TreeMap<>();
    private final List<InfoCelula> revividasPendientes = new ArrayList<>();
    private boolean pausado = true;

    public Directorio(String propio, long timeout, long gracia) {
        this.propio = propio;
        this.timeout = timeout;
        this.gracia = gracia;
    }

    // ------------------------------------------------------------------ actualización

    public synchronized void latidoCelula(Mensaje m) {
        String cid = m.str("celula", m.origen);
        if (cid == null || cid.equals(propio)) {
            return;
        }
        InfoCelula c = celulas.get(cid);
        long inicioLatido = m.num("inicio", 0);
        if (c != null && c.estado == Estado.RETIRADA && inicioLatido == c.inicio) {
            return; // latido rezagado de una célula que ya se retiró
        }
        if (c == null) {
            c = new InfoCelula(cid);
            celulas.put(cid, c);
        }
        c.clase = m.str("clase", c.clase);
        c.nodo = m.str("nodo", c.nodo);
        c.host = m.str("host", c.host);
        c.pid = m.num("pid", c.pid);
        List<InfoGen> gs = new ArrayList<>();
        for (Object o : m.lista("genes")) {
            InfoGen g = InfoGen.de(o);
            if (g != null) {
                gs.add(g);
            }
        }
        c.genes = gs;
        c.generacion = (int) m.num("generacion", 0);
        c.padre = m.str("padre");
        c.reemplaza = m.str("reemplaza");
        c.silenciada = m.bool("silenciada");
        c.inicio = inicioLatido;
        c.pos = m.str("pos");
        c.deudas = new ArrayList<>(m.lista("deudas"));
        c.stats = new LinkedHashMap<>(m.mapa("stats"));
        c.conLatido = true;
        vida(c, true);
    }

    /** Cualquier mensaje originado por una célula es evidencia de que sigue viva. */
    public synchronized void actividad(String origen) {
        if (origen == null) {
            return;
        }
        InfoCelula c = celulas.get(origen);
        if (c != null && c.estado != Estado.RETIRADA) {
            vida(c, false);
        }
    }

    private void vida(InfoCelula c, boolean esLatido) {
        long ahora = Util.ahora();
        c.ultimaVida = ahora;
        c.desconectadaEn = 0;
        switch (c.estado) {
            case MUERTA -> {
                c.estado = Estado.VIVA;
                c.cambioEstado = ahora;
                revividasPendientes.add(c.copia());
            }
            case SOSPECHOSA -> {
                c.estado = Estado.VIVA;
                c.cambioEstado = ahora;
            }
            case RETIRADA -> {
                if (esLatido) {
                    c.estado = Estado.VIVA;
                    c.cambioEstado = ahora;
                }
            }
            default -> {
                // sigue viva
            }
        }
    }

    public synchronized void conectada(String cid, String clase, String nodo) {
        if (cid == null || cid.equals(propio)) {
            return;
        }
        InfoCelula c = celulas.get(cid);
        if (c == null) {
            c = new InfoCelula(cid);
            c.clase = clase == null ? "?" : clase;
            c.nodo = nodo;
            celulas.put(cid, c);
            return;
        }
        if (clase != null) {
            c.clase = clase;
        }
        c.nodo = nodo;
        if (c.estado != Estado.RETIRADA) {
            vida(c, false);
        }
    }

    public synchronized void desconectada(String cid, String nodo) {
        InfoCelula c = cid == null ? null : celulas.get(cid);
        if (c == null || cid.equals(propio)) {
            return;
        }
        if (nodo != null && c.nodo != null && !nodo.equals(c.nodo)) {
            return; // aviso viejo: la célula ya se mudó a otro nodo
        }
        if (c.viva()) {
            c.desconectadaEn = Util.ahora();
            if (c.estado == Estado.VIVA) {
                c.estado = Estado.SOSPECHOSA;
                c.cambioEstado = c.desconectadaEn;
            }
        }
    }

    public synchronized void retirada(String cid, long inicioCelula) {
        if (cid == null || cid.equals(propio)) {
            return;
        }
        InfoCelula c = celulas.computeIfAbsent(cid, InfoCelula::new);
        c.estado = Estado.RETIRADA;
        c.cambioEstado = Util.ahora();
        if (inicioCelula > 0) {
            c.inicio = inicioCelula;
        }
    }

    public synchronized void latidoNodo(Mensaje m) {
        String nid = m.str("nodo", m.origen);
        if (nid == null) {
            return;
        }
        InfoNodo n = nodos.computeIfAbsent(nid, InfoNodo::new);
        n.host = m.str("host", n.host);
        n.puerto = (int) m.num("puerto", n.puerto);
        n.vecinos = m.listaTexto("vecinos");
        List<String> cs = new ArrayList<>();
        for (Object o : m.lista("celulas")) {
            if (o instanceof Map<?, ?> x) {
                cs.add(String.valueOf(x.get("id")));
            }
        }
        n.celulas = cs;
        n.inicio = m.num("inicio", n.inicio);
        n.stats = new LinkedHashMap<>(m.mapa("stats"));
        n.ultimoLatido = Util.ahora();
        n.caido = false;
    }

    /** Mientras la célula propia está desconectada no se puede juzgar a las demás. */
    public synchronized void pausar() {
        pausado = true;
    }

    /** Al reconectarse se concede un periodo nuevo a todas (no se oyó nada mientras tanto). */
    public synchronized void reanudar() {
        pausado = false;
        long ahora = Util.ahora();
        for (InfoCelula c : celulas.values()) {
            if (c.viva()) {
                c.ultimaVida = ahora;
                c.desconectadaEn = 0;
                c.estado = Estado.VIVA;
            }
        }
        for (InfoNodo n : nodos.values()) {
            if (!n.caido) {
                n.ultimoLatido = ahora;
            }
        }
    }

    /** Revisión periódica: transiciones de estado y purga de entradas viejas. */
    public synchronized Revision revisar() {
        long ahora = Util.ahora();
        List<InfoCelula> muertas = new ArrayList<>();
        List<InfoNodo> caidos = new ArrayList<>();
        if (!pausado) {
            for (InfoCelula c : celulas.values()) {
                if (!c.viva()) {
                    continue;
                }
                boolean porDesconexion = c.desconectadaEn > 0 && ahora - c.desconectadaEn >= gracia;
                boolean porSilencio = ahora - c.ultimaVida >= timeout;
                if (porDesconexion || porSilencio) {
                    c.estado = Estado.MUERTA;
                    c.cambioEstado = ahora;
                    muertas.add(c.copia());
                } else if (c.estado == Estado.VIVA && ahora - c.ultimaVida >= timeout / 2) {
                    c.estado = Estado.SOSPECHOSA;
                    c.cambioEstado = ahora;
                }
            }
            for (InfoNodo n : nodos.values()) {
                if (!n.caido && ahora - n.ultimoLatido >= timeout) {
                    n.caido = true;
                    caidos.add(n.copia());
                }
            }
        }
        celulas.values().removeIf(c -> !c.viva() && ahora - c.cambioEstado > 90_000);
        nodos.values().removeIf(n -> n.caido && ahora - n.ultimoLatido > 120_000);
        List<InfoCelula> rev = new ArrayList<>(revividasPendientes);
        revividasPendientes.clear();
        return new Revision(muertas, rev, caidos);
    }

    // ------------------------------------------------------------------ consultas

    public synchronized List<InfoCelula> celulas() {
        List<InfoCelula> r = new ArrayList<>();
        for (InfoCelula c : celulas.values()) {
            r.add(c.copia());
        }
        return r;
    }

    public synchronized List<InfoCelula> vivas() {
        List<InfoCelula> r = new ArrayList<>();
        for (InfoCelula c : celulas.values()) {
            if (c.viva()) {
                r.add(c.copia());
            }
        }
        return r;
    }

    public synchronized List<InfoCelula> servidoresVivos() {
        List<InfoCelula> r = new ArrayList<>();
        for (InfoCelula c : celulas.values()) {
            if (c.viva() && c.esServidor()) {
                r.add(c.copia());
            }
        }
        return r;
    }

    public synchronized InfoCelula celula(String cid) {
        InfoCelula c = celulas.get(cid);
        return c == null ? null : c.copia();
    }

    public synchronized boolean estaViva(String cid) {
        InfoCelula c = celulas.get(cid);
        return c != null && c.viva();
    }

    /** Células servidor vivas que atienden el CC indicado. */
    public synchronized List<InfoCelula> proveedores(int cc) {
        List<InfoCelula> r = new ArrayList<>();
        for (InfoCelula c : celulas.values()) {
            if (c.viva() && c.esServidor() && c.atiende(cc)) {
                r.add(c.copia());
            }
        }
        return r;
    }

    /** Operaciones conocidas en el Campo de Datos (CC → gen de mayor versión). */
    public synchronized Map<Integer, InfoGen> operaciones() {
        Map<Integer, InfoGen> r = new TreeMap<>();
        for (InfoCelula c : celulas.values()) {
            if (!c.viva() || !c.esServidor()) {
                continue;
            }
            for (InfoGen g : c.genes) {
                InfoGen x = r.get(g.cc);
                if (x == null || Util.compararVersiones(g.version, x.version) > 0) {
                    r.put(g.cc, g);
                }
            }
        }
        return r;
    }

    public synchronized List<InfoNodo> nodos() {
        List<InfoNodo> r = new ArrayList<>();
        for (InfoNodo n : nodos.values()) {
            r.add(n.copia());
        }
        return r;
    }

    public synchronized InfoNodo nodo(String nid) {
        InfoNodo n = nodos.get(nid);
        return n == null ? null : n.copia();
    }
}
