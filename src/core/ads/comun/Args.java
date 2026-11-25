package ads.comun;

import java.util.LinkedHashMap;
import java.util.Map;

/** Argumentos de línea de comandos del estilo --clave valor y --bandera. */
public final class Args {

    private final Map<String, String> m = new LinkedHashMap<>();

    public Args(String[] a) {
        for (int i = 0; i < a.length; i++) {
            if (a[i].startsWith("--")) {
                String k = a[i].substring(2);
                String v = "true";
                if (i + 1 < a.length && !a[i + 1].startsWith("--")) {
                    v = a[++i];
                }
                m.put(k, v);
            }
        }
    }

    public String get(String k, String def) {
        String v = m.get(k);
        return v == null ? def : v;
    }

    public int entero(String k, int def) {
        try {
            return Integer.parseInt(get(k, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean flag(String k) {
        return "true".equalsIgnoreCase(m.get(k));
    }

    public boolean tiene(String k) {
        return m.containsKey(k);
    }
}
