package ads.herramientas;

import ads.celula.servidor.GestorGenes;
import ads.comun.Util;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Registra la huella de integridad (SHA-256) de cada gen del repositorio en un archivo
 * &lt;gen&gt;.jar.sha256. La célula la verifica antes de cargar el gen.
 * Uso: java -cp lib/ads-core.jar ads.herramientas.FirmarGenes [carpeta-de-genes]
 */
public final class FirmarGenes {

    private FirmarGenes() {
    }

    public static void main(String[] a) throws Exception {
        Path dir = Path.of(a.length > 0 ? a[0] : "genes");
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.jar")) {
            for (Path p : ds) {
                GestorGenes.Descriptor d = GestorGenes.leerDescriptor(p);
                String sha = Util.sha256(p);
                Files.writeString(p.resolveSibling(p.getFileName() + ".sha256"),
                        sha + "  " + p.getFileName() + System.lineSeparator(), StandardCharsets.UTF_8);
                System.out.printf("   %-28s %-16s CC %d  SHA-256 %s%n", p.getFileName(), d.etiqueta(), d.cc, Util.corto(sha));
                n++;
            }
        }
        System.out.println("   " + n + " genes registrados en " + dir.toAbsolutePath());
    }
}
