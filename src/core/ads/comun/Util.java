package ads.comun;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HexFormat;

/** Utilidades generales. */
public final class Util {

    private static final SecureRandom AZAR = new SecureRandom();
    private static final String B36 = "0123456789abcdefghijklmnopqrstuvwxyz";

    private Util() {
    }

    public static long ahora() {
        return System.currentTimeMillis();
    }

    public static String hora(long ms) {
        return new SimpleDateFormat("HH:mm:ss.SSS").format(new Date(ms));
    }

    public static String horaCorta(long ms) {
        return new SimpleDateFormat("HH:mm:ss").format(new Date(ms));
    }

    /** Cadena aleatoria en base 36 (para identificadores únicos de sesión). */
    public static String nonce(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(B36.charAt(AZAR.nextInt(B36.length())));
        }
        return sb.toString();
    }

    /** Letras minúsculas aleatorias (sufijo legible de los clones). */
    public static String letras(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) ('a' + AZAR.nextInt(26)));
        }
        return sb.toString();
    }

    public static int azar(int max) {
        return AZAR.nextInt(Math.max(1, max));
    }

    public static String sha256(byte[] datos) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(datos));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String texto) {
        return sha256(texto.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static String sha256(Path archivo) throws IOException {
        try (InputStream in = Files.newInputStream(archivo)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String corto(String hex) {
        if (hex == null) {
            return "-";
        }
        return hex.length() <= 12 ? hex : hex.substring(0, 12);
    }

    /** Compara versiones "1.0", "1.10", "2.0.1" por componentes numéricos. */
    public static int compararVersiones(String a, String b) {
        if (a == null) {
            return b == null ? 0 : -1;
        }
        if (b == null) {
            return 1;
        }
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c;
            try {
                c = Long.compare(Long.parseLong(p), Long.parseLong(q));
            } catch (NumberFormatException e) {
                c = p.compareTo(q);
            }
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    /** Igualdad numérica tolerante ("5" == "5.0"); si no son números, compara texto. */
    public static boolean mismoValor(String a, String b) {
        if (a == null || b == null) {
            return a == b;
        }
        try {
            return new BigDecimal(a.trim()).compareTo(new BigDecimal(b.trim())) == 0;
        } catch (NumberFormatException e) {
            return a.trim().equals(b.trim());
        }
    }

    public static String duracion(long ms) {
        if (ms < 1000) {
            return ms + " ms";
        }
        long s = ms / 1000;
        if (s < 60) {
            return s + " s";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " min " + (s % 60) + " s";
        }
        return (m / 60) + " h " + (m % 60) + " min";
    }

    public static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean esWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
