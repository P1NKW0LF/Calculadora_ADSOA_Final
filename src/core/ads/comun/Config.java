package ads.comun;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Configuración compartida (config/ads.properties). Ningún valor es obligatorio:
 * si falta el archivo o una clave se usan valores por defecto razonables.
 */
public final class Config {

    private final Properties p = new Properties();
    private final Path archivo;

    private Config(Path archivo) {
        this.archivo = archivo;
    }

    public static Config cargar(String ruta) {
        Path path = Paths.get(ruta == null ? "config/ads.properties" : ruta);
        Config c = new Config(path.toAbsolutePath());
        if (Files.isRegularFile(path)) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                c.p.load(r);
            } catch (IOException e) {
                System.err.println("No se pudo leer la configuración " + path + ": " + e.getMessage());
            }
        }
        return c;
    }

    public Path archivo() {
        return archivo;
    }

    public String texto(String k, String def) {
        String v = p.getProperty(k);
        return v == null || v.isBlank() ? def : v.trim();
    }

    public int entero(String k, int def) {
        try {
            return Integer.parseInt(texto(k, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public long largo(String k, long def) {
        try {
            return Long.parseLong(texto(k, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean bool(String k, boolean def) {
        String v = texto(k, null);
        return v == null ? def : Boolean.parseBoolean(v);
    }

    public void fijar(String k, String v) {
        p.setProperty(k, v);
    }

    /** Lista de nodos conocidos de antemano: nodos=N1@localhost:5001,N2@localhost:5002,... */
    public List<DirNodo> nodos() {
        List<DirNodo> r = new ArrayList<>();
        String v = texto("nodos", "N1@localhost:5001,N2@localhost:5002,N3@localhost:5003,N4@localhost:5004");
        for (String parte : v.split(",")) {
            if (!parte.isBlank()) {
                try {
                    r.add(DirNodo.parse(parte));
                } catch (RuntimeException e) {
                    System.err.println("Entrada de nodo ignorada: " + parte + " (" + e.getMessage() + ")");
                }
            }
        }
        return r;
    }

    public DirNodo nodoPorId(String id) {
        for (DirNodo d : nodos()) {
            if (id != null && id.equals(d.id())) {
                return d;
            }
        }
        return null;
    }

    public Path dirGenes() {
        return Paths.get(texto("genes.dir", "genes"));
    }

    public Path dirLogs() {
        return Paths.get(texto("logs.dir", "logs"));
    }
}
