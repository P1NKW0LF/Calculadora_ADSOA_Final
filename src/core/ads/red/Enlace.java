package ads.red;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Enlace TCP punto a punto (nodo-nodo o célula-nodo).
 * Encapsulado: una línea de texto UTF-8 por mensaje (JSON sin saltos de línea).
 * Un hilo lector entrega cada línea al manejador; un hilo escritor vacía la cola de salida,
 * de modo que enviar() nunca bloquea a quien difunde.
 */
public final class Enlace {

    public enum Tipo { NODO, CELULA, DESCONOCIDO }

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;
    private final LinkedBlockingQueue<String> cola = new LinkedBlockingQueue<>(20000);
    private final AtomicBoolean abierto = new AtomicBoolean(true);
    private final AtomicLong enviados = new AtomicLong();
    private final AtomicLong recibidos = new AtomicLong();
    private final AtomicLong descartados = new AtomicLong();

    public volatile String idRemoto;
    public volatile String claseRemota;
    public volatile Tipo tipo = Tipo.DESCONOCIDO;
    /** true si este extremo abrió la conexión (se usa para resolver enlaces duplicados). */
    public volatile boolean iniciadoPorMi;
    public final long creado = System.currentTimeMillis();
    /** Instante del último mensaje recibido (sirve para distinguir un enlace vivo de uno a medio cerrar). */
    public volatile long ultimaRecepcion = System.currentTimeMillis();

    private Thread escritor;
    private Consumer<String> manejador;
    private Consumer<Enlace> alCerrar;

    public Enlace(Socket s) throws IOException {
        this.socket = s;
        s.setTcpNoDelay(true);
        s.setKeepAlive(true);
        in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
    }

    /** Lectura síncrona usada sólo durante el saludo (protocolo de conexión). */
    public String leerLinea(int timeoutMs) throws IOException {
        socket.setSoTimeout(timeoutMs);
        try {
            return in.readLine();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(0);
        }
    }

    /** Escritura síncrona usada sólo durante el saludo. */
    public void escribirAhora(String linea) throws IOException {
        synchronized (out) {
            out.write(linea);
            out.write('\n');
            out.flush();
        }
    }

    public void iniciar(Consumer<String> manejador, Consumer<Enlace> alCerrar, String nombre) {
        this.manejador = manejador;
        this.alCerrar = alCerrar;
        escritor = new Thread(this::bucleEscritura, "esc-" + nombre);
        escritor.setDaemon(true);
        escritor.start();
        Thread lector = new Thread(this::bucleLectura, "lec-" + nombre);
        lector.setDaemon(true);
        lector.start();
    }

    private void bucleLectura() {
        try {
            String l;
            while (abierto.get() && (l = in.readLine()) != null) {
                if (l.isBlank()) {
                    continue;
                }
                recibidos.incrementAndGet();
                ultimaRecepcion = System.currentTimeMillis();
                try {
                    manejador.accept(l);
                } catch (RuntimeException e) {
                    System.err.println("Error procesando mensaje de " + idRemoto + ": " + e);
                }
            }
        } catch (IOException e) {
            // conexión cerrada o perdida
        } finally {
            cerrar();
        }
    }

    private void bucleEscritura() {
        try {
            while (abierto.get()) {
                String l = cola.poll(1, TimeUnit.SECONDS);
                if (l == null) {
                    continue;
                }
                synchronized (out) {
                    out.write(l);
                    out.write('\n');
                    if (cola.isEmpty()) {
                        out.flush();
                    }
                }
                enviados.incrementAndGet();
            }
        } catch (IOException | InterruptedException e) {
            // conexión cerrada
        } finally {
            cerrar();
        }
    }

    /** Encola una línea para su envío; nunca bloquea. */
    public boolean enviar(String linea) {
        if (!abierto.get()) {
            return false;
        }
        boolean ok = cola.offer(linea);
        if (!ok) {
            descartados.incrementAndGet();
        }
        return ok;
    }

    public boolean abierto() {
        return abierto.get();
    }

    public void cerrar() {
        if (!abierto.compareAndSet(true, false)) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // nada que hacer
        }
        if (escritor != null) {
            escritor.interrupt();
        }
        if (alCerrar != null) {
            try {
                alCerrar.accept(this);
            } catch (RuntimeException e) {
                System.err.println("Error al cerrar enlace: " + e);
            }
        }
    }

    public String direccionRemota() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    public long enviados() {
        return enviados.get();
    }

    public long recibidos() {
        return recibidos.get();
    }

    public long descartados() {
        return descartados.get();
    }
}
