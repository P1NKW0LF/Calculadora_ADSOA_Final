package ads.comun;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Creación de procesos hijos (clones de células, células y nodos nuevos desde el gestor). */
public final class Procesos {

    private Procesos() {
    }

    /** Ejecutable de Java de la instalación actual (javaw en Windows para no abrir consola). */
    public static String java() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (Util.esWindows()) {
            Path w = bin.resolve("javaw.exe");
            return Files.exists(w) ? w.toString() : bin.resolve("java.exe").toString();
        }
        return bin.resolve("java").toString();
    }

    public static Process lanzar(String claseMain, List<String> argumentos, Path dirLogs, String nombre)
            throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(java());
        cmd.add("-Xmx96m");
        cmd.add("-XX:+UseSerialGC");
        cmd.add("-XX:TieredStopAtLevel=1");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dads.consola=false");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(claseMain);
        cmd.addAll(argumentos);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        Files.createDirectories(dirLogs);
        String limpio = nombre.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(dirLogs.resolve(limpio + ".salida.log").toFile()));
        Process p = pb.start();
        try {
            p.getOutputStream().close();
        } catch (IOException ignored) {
            // nada
        }
        return p;
    }
}
