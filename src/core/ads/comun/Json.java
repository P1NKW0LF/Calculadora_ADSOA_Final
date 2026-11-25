package ads.comun;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Codificador/decodificador JSON mínimo, sin dependencias externas.
 * Se usa como formato de los mensajes que viajan por el Campo de Datos
 * (un mensaje = un objeto JSON en una sola línea de texto UTF-8).
 */
public final class Json {

    private Json() {
    }

    public static String escribir(Object valor) {
        StringBuilder sb = new StringBuilder(256);
        escribir(valor, sb);
        return sb.toString();
    }

    private static void escribir(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            cadena(s, sb);
        } else if (v instanceof Double d && (d.isNaN() || d.isInfinite())) {
            cadena(d.toString(), sb);
        } else if (v instanceof Float f && (f.isNaN() || f.isInfinite())) {
            cadena(f.toString(), sb);
        } else if (v instanceof Number n) {
            sb.append(n);
        } else if (v instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean primero = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!primero) {
                    sb.append(',');
                }
                primero = false;
                cadena(String.valueOf(e.getKey()), sb);
                sb.append(':');
                escribir(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean primero = true;
            for (Object o : c) {
                if (!primero) {
                    sb.append(',');
                }
                primero = false;
                escribir(o, sb);
            }
            sb.append(']');
        } else if (v instanceof Object[] arr) {
            escribir(Arrays.asList(arr), sb);
        } else {
            cadena(v.toString(), sb);
        }
    }

    private static void cadena(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object leer(String texto) {
        Lector l = new Lector(texto);
        l.espacios();
        Object v = l.valor();
        l.espacios();
        if (l.i != texto.length()) {
            throw new IllegalArgumentException("JSON: texto sobrante en la posición " + l.i);
        }
        return v;
    }

    private static final class Lector {
        private final String s;
        private int i;

        Lector(String s) {
            this.s = s;
        }

        void espacios() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object valor() {
            char c = peek();
            return switch (c) {
                case '{' -> objeto();
                case '[' -> arreglo();
                case '"' -> cadena();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> numero();
            };
        }

        Map<String, Object> objeto() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            espacios();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                espacios();
                if (peek() != '"') {
                    throw error("se esperaba una clave");
                }
                String k = cadena();
                espacios();
                esperar(':');
                espacios();
                m.put(k, valor());
                espacios();
                char c = sig();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("se esperaba ',' o '}'");
                }
            }
        }

        List<Object> arreglo() {
            List<Object> l = new ArrayList<>();
            i++;
            espacios();
            if (peek() == ']') {
                i++;
                return l;
            }
            while (true) {
                espacios();
                l.add(valor());
                espacios();
                char c = sig();
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error("se esperaba ',' o ']'");
                }
            }
        }

        String cadena() {
            esperar('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw error("cadena sin cerrar");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = sig();
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("escape unicode incompleto");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("escape inválido");
                }
            }
        }

        Object numero() {
            int ini = i;
            while (i < s.length() && "0123456789+-.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            String t = s.substring(ini, i);
            if (t.isEmpty()) {
                throw error("valor inválido");
            }
            if (t.indexOf('.') >= 0 || t.indexOf('e') >= 0 || t.indexOf('E') >= 0) {
                return Double.parseDouble(t);
            }
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(t);
            }
        }

        Object literal(String lit, Object v) {
            if (!s.startsWith(lit, i)) {
                throw error("literal inválido");
            }
            i += lit.length();
            return v;
        }

        char peek() {
            if (i >= s.length()) {
                throw error("fin inesperado");
            }
            return s.charAt(i);
        }

        char sig() {
            char c = peek();
            i++;
            return c;
        }

        void esperar(char c) {
            if (sig() != c) {
                throw error("se esperaba '" + c + "'");
            }
        }

        IllegalArgumentException error(String m) {
            return new IllegalArgumentException("JSON: " + m + " (posición " + i + ")");
        }
    }
}
