package ads.comun;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bitácora de un proceso: escribe en consola, en logs/&lt;id&gt;.log y notifica a la interfaz.
 */
public final class Bitacora {

    public enum Nivel { INFO, EVENTO, AVISO, ERROR }

    public record Linea(long ts, Nivel nivel, String texto) {
        public String formato() {
            return Util.hora(ts) + "  " + texto;
        }
    }

    private final String quien;
    private PrintWriter archivo;
    private final List<Consumer<Linea>> oyentes = new CopyOnWriteArrayList<>();
    private final java.util.ArrayDeque<Linea> recientes = new java.util.ArrayDeque<>();
    private volatile boolean consola = !"false".equalsIgnoreCase(System.getProperty("ads.consola"));

    public Bitacora(String quien, Path dirLogs) {
        this.quien = quien;
        try {
            Files.createDirectories(dirLogs);
            String nombre = quien.replaceAll("[^A-Za-z0-9_.\\-]", "_") + ".log";
            archivo = new PrintWriter(Files.newBufferedWriter(dirLogs.resolve(nombre), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
            archivo.println("==== " + Util.hora(Util.ahora()) + " inicio de " + quien + " ====");
        } catch (IOException e) {
            archivo = null;
        }
    }

    public void sinConsola() {
        consola = false;
    }

    /** Registra un oyente y le entrega primero las líneas recientes (para llenar la interfaz al abrirla). */
    public void alEscribir(Consumer<Linea> oyente) {
        List<Linea> previas;
        synchronized (this) {
            previas = new java.util.ArrayList<>(recientes);
            oyentes.add(oyente);
        }
        for (Linea l : previas) {
            oyente.accept(l);
        }
    }

    public void info(String t) {
        escribir(Nivel.INFO, t);
    }

    public void evento(String t) {
        escribir(Nivel.EVENTO, t);
    }

    public void aviso(String t) {
        escribir(Nivel.AVISO, t);
    }

    public void error(String t) {
        escribir(Nivel.ERROR, t);
    }

    private void escribir(Nivel n, String t) {
        Linea l = new Linea(Util.ahora(), n, t);
        synchronized (this) {
            String marca = switch (n) {
                case INFO -> "  ";
                case EVENTO -> "* ";
                case AVISO -> "! ";
                case ERROR -> "X ";
            };
            if (consola) {
                System.out.println("[" + quien + "] " + Util.hora(l.ts()) + " " + marca + t);
            }
            if (archivo != null) {
                archivo.println(Util.hora(l.ts()) + " " + marca + t);
            }
            recientes.addLast(l);
            if (recientes.size() > 400) {
                recientes.pollFirst();
            }
        }
        for (Consumer<Linea> o : oyentes) {
            try {
                o.accept(l);
            } catch (RuntimeException ignored) {
                // la interfaz no debe romper la bitácora
            }
        }
    }
}
