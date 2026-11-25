package ads.celula.servidor;

import ads.api.Microservicio;
import ads.celula.Directorio;
import ads.comun.Bitacora;
import ads.comun.CC;
import ads.comun.Util;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Genes activos de una célula y su carga dinámica desde disco.
 *
 * Ciclo de vida de un gen:
 * <ol>
 *   <li>LOCALIZAR: buscar en el repositorio (carpeta genes/) el .jar cuyo gen.properties declara
 *       el nombre y la versión pedidos (sin versión: la mayor).</li>
 *   <li>VALIDAR: descriptor completo, CC dentro del rango 100..199 y sin conflicto con otro gen activo,
 *       e integridad: el SHA-256 del .jar debe coincidir con su archivo .jar.sha256.</li>
 *   <li>CARGAR: un URLClassLoader exclusivo lee la clase desde el .jar y la instancia; se verifica que
 *       implemente {@link Microservicio}.</li>
 *   <li>AUTOPRUEBA: se ejecuta la prueba declarada en el descriptor; si falla, el gen se rechaza.</li>
 *   <li>ACTIVAR: se registra el CC → gen; desde ese instante la célula atiende ese Código de Contenido.</li>
 *   <li>DESCARGAR (baja): se retira del registro, se llama desactivar(), se cierra el ClassLoader
 *       (libera el .jar) y se suelta la referencia para que el recolector descargue las clases.</li>
 * </ol>
 */
public final class GestorGenes {

    /** Descriptor gen.properties de un gen en disco. */
    public static final class Descriptor {
        public final String nombre;
        public final String version;
        public final int cc;
        public final String clase;
        public final String simbolo;
        public final int aridad;
        public final String descripcion;
        public final String pruebaEntrada;
        public final String pruebaEsperado;
        public final Path jar;

        Descriptor(Properties p, Path jar) {
            this.nombre = p.getProperty("nombre", "").trim().toUpperCase(Locale.ROOT);
            this.version = p.getProperty("version", "1.0").trim();
            int c;
            try {
                c = Integer.parseInt(p.getProperty("cc", "-1").trim());
            } catch (NumberFormatException e) {
                c = -1;
            }
            this.cc = c;
            this.clase = p.getProperty("clase", "").trim();
            this.simbolo = p.getProperty("simbolo", nombre.isEmpty() ? "?" : nombre.substring(0, 1)).trim();
            int a;
            try {
                a = Integer.parseInt(p.getProperty("aridad", "2").trim());
            } catch (NumberFormatException e) {
                a = 2;
            }
            this.aridad = a;
            this.descripcion = p.getProperty("descripcion", "").trim();
            this.pruebaEntrada = p.getProperty("prueba.entrada");
            this.pruebaEsperado = p.getProperty("prueba.esperado");
            this.jar = jar;
        }

        public String etiqueta() {
            return nombre + " v" + version;
        }

        public Directorio.InfoGen info() {
            return new Directorio.InfoGen(nombre, version, cc, simbolo, descripcion);
        }
    }

    /** Gen cargado en memoria y activo. */
    public static final class GenActivo {
        public final Descriptor d;
        public final URLClassLoader cargador;
        public final Microservicio instancia;
        public final String sha256;
        public final long activadoEn = Util.ahora();
        public final AtomicLong ejecuciones = new AtomicLong();

        GenActivo(Descriptor d, URLClassLoader cargador, Microservicio instancia, String sha256) {
            this.d = d;
            this.cargador = cargador;
            this.instancia = instancia;
            this.sha256 = sha256;
        }
    }

    public static class ErrorGen extends Exception {
        private static final long serialVersionUID = 1L;

        public ErrorGen(String m) {
            super(m);
        }

        public ErrorGen(String m, Throwable causa) {
            super(m, causa);
        }
    }

    /** El gen no está en el repositorio local (se puede pedir por el Campo de Datos). */
    public static final class GenNoEncontrado extends ErrorGen {
        private static final long serialVersionUID = 1L;

        public GenNoEncontrado(String m) {
            super(m);
        }
    }

    private final Path repositorio;
    private final Bitacora log;
    private final Map<String, GenActivo> porNombre = new ConcurrentHashMap<>();
    private final Map<Integer, GenActivo> porCC = new ConcurrentHashMap<>();
    private final List<Runnable> oyentes = new CopyOnWriteArrayList<>();

    public GestorGenes(Path repositorio, Bitacora log) {
        this.repositorio = repositorio;
        this.log = log;
    }

    public Path repositorio() {
        return repositorio;
    }

    public void alCambiar(Runnable r) {
        oyentes.add(r);
    }

    private void notificar() {
        for (Runnable r : oyentes) {
            try {
                r.run();
            } catch (RuntimeException ignored) {
                // nada
            }
        }
    }

    // ------------------------------------------------------------------ consulta

    public GenActivo porCC(int cc) {
        return porCC.get(cc);
    }

    public GenActivo porNombre(String nombre) {
        return nombre == null ? null : porNombre.get(nombre.toUpperCase(Locale.ROOT));
    }

    public List<GenActivo> activos() {
        List<GenActivo> r = new ArrayList<>(new TreeMap<>(porNombre).values());
        return r;
    }

    public List<Directorio.InfoGen> infoActivos() {
        List<Directorio.InfoGen> r = new ArrayList<>();
        for (GenActivo g : activos()) {
            r.add(g.d.info());
        }
        return r;
    }

    // ------------------------------------------------------------------ repositorio en disco

    public static List<Descriptor> catalogo(Path repo) {
        List<Descriptor> r = new ArrayList<>();
        if (!Files.isDirectory(repo)) {
            return r;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(repo, "*.jar")) {
            for (Path p : ds) {
                try {
                    r.add(leerDescriptor(p));
                } catch (IOException e) {
                    System.err.println("Gen ignorado " + p.getFileName() + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("No se pudo leer el repositorio de genes " + repo + ": " + e.getMessage());
        }
        r.sort(Comparator.comparing((Descriptor d) -> d.nombre)
                .thenComparing((a, b) -> Util.compararVersiones(a.version, b.version)));
        return r;
    }

    public static Descriptor leerDescriptor(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            JarEntry e = jf.getJarEntry("gen.properties");
            if (e == null) {
                throw new IOException("el .jar no contiene gen.properties");
            }
            Properties p = new Properties();
            try (InputStream in = jf.getInputStream(e); Reader rd = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                p.load(rd);
            }
            return new Descriptor(p, jar);
        }
    }

    /** Huella de integridad registrada junto al .jar (archivo .jar.sha256), o null si no existe. */
    public static String integridadRegistrada(Path jar) {
        Path f = jar.resolveSibling(jar.getFileName().toString() + ".sha256");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            String t = Files.readString(f, StandardCharsets.UTF_8).trim();
            return t.isEmpty() ? null : t.split("\\s+")[0].toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return null;
        }
    }

    public Descriptor localizar(String nombre, String version) throws ErrorGen {
        Descriptor mejor = null;
        for (Descriptor d : catalogo(repositorio)) {
            if (!d.nombre.equalsIgnoreCase(nombre)) {
                continue;
            }
            if (version != null && !version.isBlank()) {
                if (Util.compararVersiones(d.version, version) == 0) {
                    return d;
                }
            } else if (mejor == null || Util.compararVersiones(d.version, mejor.version) > 0) {
                mejor = d;
            }
        }
        if (mejor == null) {
            throw new GenNoEncontrado("El gen " + nombre + (version == null || version.isBlank() ? "" : " v" + version)
                    + " no existe en el repositorio " + repositorio.toAbsolutePath());
        }
        return mejor;
    }

    // ------------------------------------------------------------------ ciclo de vida

    private String validar(Descriptor d, GenActivo reemplazado) throws ErrorGen {
        if (d.nombre.isEmpty() || d.clase.isEmpty()) {
            throw new ErrorGen("Descriptor incompleto en " + d.jar.getFileName() + " (faltan nombre o clase)");
        }
        if (!CC.esOperacion(d.cc)) {
            throw new ErrorGen("CC " + d.cc + " fuera del rango de operaciones (100..199)");
        }
        GenActivo otro = porCC.get(d.cc);
        if (otro != null && otro != reemplazado) {
            throw new ErrorGen("Conflicto: el CC " + d.cc + " ya lo atiende " + otro.d.etiqueta());
        }
        String calculada;
        try {
            calculada = Util.sha256(d.jar);
        } catch (IOException e) {
            throw new ErrorGen("No se pudo leer " + d.jar + ": " + e.getMessage(), e);
        }
        String registrada = integridadRegistrada(d.jar);
        if (registrada != null && !registrada.equalsIgnoreCase(calculada)) {
            throw new ErrorGen("Integridad comprometida: SHA-256 " + Util.corto(calculada)
                    + " no coincide con el registrado " + Util.corto(registrada));
        }
        if (registrada == null) {
            log.aviso("      " + d.jar.getFileName() + " no tiene huella .sha256 registrada; SHA-256 calculado "
                    + Util.corto(calculada));
        } else {
            log.info("      descriptor válido, CC " + d.cc + " sin conflicto, SHA-256 " + Util.corto(calculada) + " correcto");
        }
        return calculada;
    }

    private GenActivo cargar(Descriptor d, String sha) throws ErrorGen {
        URLClassLoader cl;
        try {
            cl = new URLClassLoader("gen-" + d.nombre + "-" + d.version, new URL[]{d.jar.toUri().toURL()},
                    Microservicio.class.getClassLoader());
        } catch (IOException e) {
            throw new ErrorGen("Ruta inválida " + d.jar, e);
        }
        try {
            Class<?> k = Class.forName(d.clase, true, cl);
            if (!Microservicio.class.isAssignableFrom(k)) {
                throw new ErrorGen("La clase " + d.clase + " no implementa ads.api.Microservicio");
            }
            Microservicio ms = (Microservicio) k.getDeclaredConstructor().newInstance();
            log.info("      clase " + k.getName() + " cargada por el ClassLoader '" + cl.getName() + "'");
            return new GenActivo(d, cl, ms, sha);
        } catch (ErrorGen e) {
            cerrar(cl);
            throw e;
        } catch (Throwable t) {
            cerrar(cl);
            throw new ErrorGen("No se pudo cargar " + d.clase + ": " + t, t);
        }
    }

    private void autoprueba(GenActivo g) throws ErrorGen {
        Descriptor d = g.d;
        if (d.pruebaEntrada == null || d.pruebaEsperado == null) {
            log.aviso("      el gen no declara autoprueba");
            return;
        }
        String[] ops = d.pruebaEntrada.split(",");
        for (int i = 0; i < ops.length; i++) {
            ops[i] = ops[i].trim();
        }
        String r;
        try {
            r = g.instancia.ejecutar(ops);
        } catch (Throwable t) {
            throw new ErrorGen("Autoprueba fallida: " + t.getMessage(), t);
        }
        if (!Util.mismoValor(r, d.pruebaEsperado)) {
            throw new ErrorGen("Autoprueba fallida: (" + d.pruebaEntrada + ") dio " + r + ", se esperaba " + d.pruebaEsperado);
        }
        log.info("      autoprueba (" + d.pruebaEntrada + ") = " + r + "  correcta");
    }

    private static void cerrar(URLClassLoader cl) {
        try {
            cl.close();
        } catch (IOException ignored) {
            // nada
        }
    }

    private void descargar(GenActivo g) {
        try {
            g.instancia.desactivar();
        } catch (Throwable t) {
            log.aviso("desactivar() de " + g.d.etiqueta() + " falló: " + t);
        }
        cerrar(g.cargador);
    }

    /** Alta de un gen: localizar → validar → cargar → autoprueba → activar. */
    public synchronized GenActivo alta(String nombre, String version) throws ErrorGen {
        GenActivo actual = porNombre(nombre);
        if (actual != null) {
            throw new ErrorGen("El gen " + actual.d.nombre + " ya está activo (v" + actual.d.version
                    + "); use MODIFICAR para sustituirlo");
        }
        log.info("[1/5] LOCALIZAR " + nombre.toUpperCase(Locale.ROOT)
                + (version == null || version.isBlank() ? " (versión más reciente)" : " v" + version)
                + " en " + repositorio);
        Descriptor d = localizar(nombre, version);
        log.info("      encontrado " + d.jar.getFileName());
        log.info("[2/5] VALIDAR " + d.etiqueta());
        String sha = validar(d, null);
        log.info("[3/5] CARGAR desde disco a memoria");
        GenActivo g = cargar(d, sha);
        log.info("[4/5] AUTOPRUEBA");
        try {
            autoprueba(g);
            g.instancia.activar();
        } catch (ErrorGen e) {
            descargar(g);
            throw e;
        } catch (Throwable t) {
            descargar(g);
            throw new ErrorGen("activar() falló: " + t, t);
        }
        porNombre.put(d.nombre, g);
        porCC.put(d.cc, g);
        log.evento("[5/5] ACTIVAR: " + d.etiqueta() + " atiende ahora el CC " + d.cc + " (" + d.simbolo + ")");
        notificar();
        return g;
    }

    /** Baja de un gen: desactivar → descargar de memoria. */
    public synchronized GenActivo baja(String nombre) throws ErrorGen {
        GenActivo g = porNombre(nombre);
        if (g == null) {
            throw new ErrorGen("El gen " + nombre + " no está activo en esta célula");
        }
        porNombre.remove(g.d.nombre, g);
        porCC.remove(g.d.cc, g);
        log.info("[1/2] DESACTIVAR " + g.d.etiqueta() + ": deja de atender el CC " + g.d.cc);
        descargar(g);
        log.evento("[2/2] DESCARGAR: ClassLoader '" + g.cargador.getName() + "' cerrado; " + g.d.etiqueta()
                + " fuera de memoria");
        notificar();
        return g;
    }

    /** Sustitución en caliente: se carga y prueba la versión nueva antes de retirar la anterior. */
    public synchronized GenActivo modificar(String nombre, String version) throws ErrorGen {
        GenActivo viejo = porNombre(nombre);
        if (viejo == null) {
            throw new ErrorGen("El gen " + nombre + " no está activo en esta célula; use ALTA");
        }
        log.info("[1/6] LOCALIZAR " + viejo.d.nombre
                + (version == null || version.isBlank() ? " (versión más reciente)" : " v" + version));
        Descriptor d = localizar(nombre, version);
        log.info("      encontrado " + d.jar.getFileName());
        log.info("[2/6] VALIDAR " + d.etiqueta());
        String sha = validar(d, viejo);
        if (sha.equalsIgnoreCase(viejo.sha256)) {
            throw new ErrorGen("La versión solicitada (" + d.etiqueta() + ") ya es la activa");
        }
        log.info("[3/6] CARGAR la versión nueva en un ClassLoader separado");
        GenActivo nuevo = cargar(d, sha);
        log.info("[4/6] AUTOPRUEBA de la versión nueva");
        try {
            autoprueba(nuevo);
            nuevo.instancia.activar();
        } catch (ErrorGen e) {
            descargar(nuevo);
            throw e;
        } catch (Throwable t) {
            descargar(nuevo);
            throw new ErrorGen("activar() falló: " + t, t);
        }
        porNombre.put(d.nombre, nuevo);
        porCC.remove(viejo.d.cc, viejo);
        porCC.put(d.cc, nuevo);
        log.info("[5/6] SUSTITUIR: el CC " + d.cc + " pasa de " + viejo.d.etiqueta() + " a " + d.etiqueta());
        descargar(viejo);
        log.evento("[6/6] DESCARGAR versión anterior " + viejo.d.etiqueta() + " (ClassLoader cerrado)");
        notificar();
        return nuevo;
    }

    public synchronized void descargarTodo() {
        for (GenActivo g : activos()) {
            porNombre.remove(g.d.nombre);
            porCC.remove(g.d.cc);
            descargar(g);
        }
    }

    // ------------------------------------------------------------------ transferencia de genes

    /** Guarda en el repositorio local un gen recibido por el Campo de Datos (verificando su SHA-256). */
    public Path guardarRecibido(String archivo, byte[] contenido, String shaEsperado) throws ErrorGen {
        String sha = Util.sha256(contenido);
        if (shaEsperado != null && !sha.equalsIgnoreCase(shaEsperado)) {
            throw new ErrorGen("El gen recibido está corrupto (SHA-256 no coincide)");
        }
        String nombre = Path.of(archivo).getFileName().toString();
        if (!nombre.endsWith(".jar")) {
            throw new ErrorGen("Nombre de archivo inválido: " + archivo);
        }
        try {
            Files.createDirectories(repositorio);
            Path destino = repositorio.resolve(nombre);
            Path tmp = repositorio.resolve(nombre + ".tmp");
            Files.write(tmp, contenido);
            Files.move(tmp, destino, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(repositorio.resolve(nombre + ".sha256"), sha + "  " + nombre + System.lineSeparator());
            return destino;
        } catch (IOException e) {
            throw new ErrorGen("No se pudo guardar el gen recibido: " + e.getMessage(), e);
        }
    }
}
