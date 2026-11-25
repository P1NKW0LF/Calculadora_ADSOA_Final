package ads.comun;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.text.BadLocationException;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;

/** Utilidades de interfaz compartidas (estilo, colores por gen, colocación de ventanas). */
public final class Ventanas {

    public static final Color FONDO_ENCABEZADO = new Color(23, 43, 77);
    public static final Color TEXTO_CLARO = new Color(245, 247, 250);
    public static final Color VERDE = new Color(26, 127, 55);
    public static final Color ROJO = new Color(200, 40, 40);
    public static final Color AMBAR = new Color(191, 135, 0);
    public static final Color GRIS = new Color(110, 118, 129);
    public static final Color FONDO_CLON = new Color(255, 244, 214);

    private Ventanas() {
    }

    public static void estilo() {
        try {
            if (Util.esWindows() || System.getProperty("os.name", "").toLowerCase().contains("mac")) {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } else {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                UIManager.put("swing.boldMetal", Boolean.FALSE);
            }
        } catch (Exception ignored) {
            // se queda el estilo por defecto
        }
    }

    public static Rectangle areaUtil() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        } catch (RuntimeException e) {
            return new Rectangle(0, 0, 1366, 768);
        }
    }

    /** Coloca la ventana en una posición expresada como fracción del área útil: "0.5,0.25". */
    public static void colocar(Window w, String pos) {
        Rectangle a = areaUtil();
        if (w.getWidth() > a.width) {
            w.setSize(a.width, w.getHeight());
        }
        if (w.getHeight() > a.height) {
            w.setSize(w.getWidth(), a.height);
        }
        if (pos == null || pos.isBlank()) {
            w.setLocationRelativeTo(null);
            return;
        }
        try {
            String[] p = pos.split(",");
            double fx = Double.parseDouble(p[0].trim());
            double fy = Double.parseDouble(p[1].trim());
            int x = a.x + (int) Math.round(fx * a.width);
            int y = a.y + (int) Math.round(fy * a.height);
            x = Math.min(x, a.x + a.width - w.getWidth());
            y = Math.min(y, a.y + a.height - w.getHeight());
            w.setLocation(Math.max(a.x, x), Math.max(a.y, y));
        } catch (RuntimeException e) {
            w.setLocationRelativeTo(null);
        }
    }

    /** Posición actual de la ventana como fracción del área útil (se hereda a los clones). */
    public static String posicionRelativa(Window w) {
        Rectangle a = areaUtil();
        double fx = (w.getX() - a.x) / (double) Math.max(1, a.width);
        double fy = (w.getY() - a.y) / (double) Math.max(1, a.height);
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f", Math.max(0, fx), Math.max(0, fy));
    }

    /** Fuerza un repintado completo poco después de mostrar la ventana (evita ventanas en blanco). */
    public static void repintarLuego(Window w) {
        for (int ms : new int[]{300, 1500}) {
            javax.swing.Timer t = new javax.swing.Timer(ms, e -> {
                w.invalidate();
                w.validate();
                w.repaint();
            });
            t.setRepeats(false);
            t.start();
        }
    }

    public static Font mono(int tam) {
        return new Font(Font.MONOSPACED, Font.PLAIN, tam);
    }

    public static Font negrita(int tam) {
        return new Font(Font.SANS_SERIF, Font.BOLD, tam);
    }

    public static Font normal(int tam) {
        return new Font(Font.SANS_SERIF, Font.PLAIN, tam);
    }

    public static Color colorGen(String nombre) {
        if (nombre == null) {
            return GRIS;
        }
        return switch (nombre.toUpperCase()) {
            case "SUMA" -> new Color(46, 160, 67);
            case "RESTA" -> new Color(31, 111, 235);
            case "MULTIPLICACION" -> new Color(219, 109, 40);
            case "DIVISION" -> new Color(137, 87, 229);
            case "POTENCIA" -> new Color(0, 150, 160);
            default -> Color.getHSBColor((Math.abs(nombre.hashCode()) % 360) / 360f, 0.55f, 0.75f);
        };
    }

    public static JLabel etiqueta(String texto, Font f, Color c) {
        JLabel l = new JLabel(texto);
        l.setFont(f);
        if (c != null) {
            l.setForeground(c);
        }
        return l;
    }

    public static Border titulo(String t) {
        return BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(t),
                BorderFactory.createEmptyBorder(2, 4, 4, 4));
    }

    public static JTextArea areaBitacora(int filas) {
        JTextArea a = new JTextArea(filas, 40);
        a.setEditable(false);
        a.setFont(mono(11));
        a.setLineWrap(false);
        return a;
    }

    /** Agrega una línea a un área de texto conservando sólo las últimas 'max' líneas. */
    public static void agregarLinea(JTextArea a, String linea, int max) {
        SwingUtilities.invokeLater(() -> {
            a.append(linea + "\n");
            int sobran = a.getLineCount() - max;
            if (sobran > 0) {
                try {
                    a.getDocument().remove(0, a.getLineEndOffset(sobran - 1));
                } catch (BadLocationException ignored) {
                    // nada
                }
            }
            a.setCaretPosition(a.getDocument().getLength());
        });
    }

    public static void relleno(JComponent c, int px) {
        c.setBorder(BorderFactory.createEmptyBorder(px, px, px, px));
    }
}
