package ads.comun;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mensaje del Campo de Datos.
 *
 * <pre>
 * {
 *   "id":     "GUI-1:k3x9:17",          identificador único global (deduplicación / anti-ciclos)
 *   "cc":     101,                       Código de Contenido
 *   "origen": "GUI-1",                   célula o nodo que lo creó
 *   "folio":  "GUI-1-031522-0007",       folio del evento al que pertenece (opcional)
 *   "huella": ["GUI-1","N1","N3"],       trayectoria lógica: origen + nodos recorridos
 *   "ttl":    7,                         saltos restantes (red de seguridad contra ciclos)
 *   "ts":     1764000000000,             instante de creación (ms)
 *   "datos":  { ... }                    contenido propio de cada CC
 * }
 * </pre>
 */
public final class Mensaje {

    public String id;
    public int cc;
    public String origen;
    public String folio;
    public List<String> huella = new ArrayList<>();
    public int ttl;
    public long ts;
    public Map<String, Object> datos = new LinkedHashMap<>();

    public Mensaje() {
    }

    public Mensaje(int cc) {
        this.cc = cc;
    }

    public static Mensaje de(int cc) {
        return new Mensaje(cc);
    }

    public Mensaje con(String clave, Object valor) {
        datos.put(clave, valor);
        return this;
    }

    public Mensaje conFolio(String f) {
        this.folio = f;
        return this;
    }

    // ---- lectura cómoda de datos ----

    public String str(String k) {
        Object v = datos.get(k);
        return v == null ? null : v.toString();
    }

    public String str(String k, String def) {
        String v = str(k);
        return v == null ? def : v;
    }

    public long num(String k, long def) {
        Object v = datos.get(k);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                // se usa el valor por defecto
            }
        }
        return def;
    }

    public double dbl(String k, double def) {
        Object v = datos.get(k);
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException ignored) {
                // se usa el valor por defecto
            }
        }
        return def;
    }

    public boolean bool(String k) {
        Object v = datos.get(k);
        return v instanceof Boolean b ? b : "true".equalsIgnoreCase(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    public List<Object> lista(String k) {
        Object v = datos.get(k);
        return v instanceof List<?> l ? (List<Object>) l : List.of();
    }

    public List<String> listaTexto(String k) {
        List<String> r = new ArrayList<>();
        for (Object o : lista(k)) {
            r.add(String.valueOf(o));
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> mapa(String k) {
        Object v = datos.get(k);
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    // ---- serialización ----

    public String aJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("cc", cc);
        m.put("origen", origen);
        if (folio != null) {
            m.put("folio", folio);
        }
        m.put("huella", huella);
        m.put("ttl", ttl);
        m.put("ts", ts);
        m.put("datos", datos);
        return Json.escribir(m);
    }

    @SuppressWarnings("unchecked")
    public static Mensaje deJson(String linea) {
        Object raiz = Json.leer(linea);
        if (!(raiz instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("El mensaje no es un objeto JSON");
        }
        Map<String, Object> m = (Map<String, Object>) raiz;
        Mensaje x = new Mensaje();
        x.id = m.get("id") == null ? null : String.valueOf(m.get("id"));
        x.cc = m.get("cc") instanceof Number n ? n.intValue() : 0;
        x.origen = m.get("origen") == null ? null : String.valueOf(m.get("origen"));
        x.folio = m.get("folio") == null ? null : String.valueOf(m.get("folio"));
        if (m.get("huella") instanceof List<?> l) {
            for (Object o : l) {
                x.huella.add(String.valueOf(o));
            }
        }
        x.ttl = m.get("ttl") instanceof Number n ? n.intValue() : 0;
        x.ts = m.get("ts") instanceof Number n ? n.longValue() : 0L;
        if (m.get("datos") instanceof Map<?, ?> d) {
            x.datos = (Map<String, Object>) d;
        }
        return x;
    }

    /** Trayectoria legible: "GUI-1 → N1 → N3". */
    public String trayectoria() {
        return String.join(" → ", huella);
    }

    public static String trayectoria(List<?> h) {
        List<String> t = new ArrayList<>();
        for (Object o : h) {
            t.add(String.valueOf(o));
        }
        return String.join(" → ", t);
    }

    @Override
    public String toString() {
        return "[CC " + cc + " " + CC.nombre(cc) + "] de " + origen
                + (folio != null ? " folio=" + folio : "") + " huella=" + trayectoria();
    }
}
