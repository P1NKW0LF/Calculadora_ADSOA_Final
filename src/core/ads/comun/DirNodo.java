package ads.comun;

/** Dirección de un nodo del Campo de Datos: "N1@localhost:5001". */
public record DirNodo(String id, String host, int puerto) {

    public static DirNodo parse(String texto) {
        String t = texto.trim();
        String id = null;
        int arroba = t.indexOf('@');
        if (arroba >= 0) {
            id = t.substring(0, arroba).trim();
            t = t.substring(arroba + 1).trim();
        }
        int dos = t.lastIndexOf(':');
        if (dos < 0) {
            throw new IllegalArgumentException("Dirección de nodo inválida (se espera host:puerto): " + texto);
        }
        String host = t.substring(0, dos).trim();
        int puerto = Integer.parseInt(t.substring(dos + 1).trim());
        return new DirNodo(id == null || id.isEmpty() ? null : id, host.isEmpty() ? "localhost" : host, puerto);
    }

    public String direccion() {
        return host + ":" + puerto;
    }

    public boolean mismaDireccion(DirNodo o) {
        return o != null && puerto == o.puerto && normal(host).equals(normal(o.host));
    }

    private static String normal(String h) {
        String x = h.toLowerCase();
        return ("127.0.0.1".equals(x) || "::1".equals(x)) ? "localhost" : x;
    }

    @Override
    public String toString() {
        return (id != null ? id + "@" : "") + host + ":" + puerto;
    }
}
