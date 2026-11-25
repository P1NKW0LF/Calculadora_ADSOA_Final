package ads.gestor;

import ads.celula.Directorio;
import ads.celula.Directorio.InfoCelula;
import ads.celula.Directorio.InfoGen;
import ads.celula.Directorio.InfoNodo;
import ads.comun.Util;
import ads.comun.Ventanas;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/** Dibujo de la topología: nodos en malla, sus enlaces y las células conectadas a cada nodo. */
final class PanelMapa extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int ALTO_CELULA = 24;
    private static final int ANCHO_CELULA = 190;

    private transient List<InfoNodo> nodos = new ArrayList<>();
    private transient List<InfoCelula> celulas = new ArrayList<>();
    private final transient Map<String, Rectangle> cajas = new LinkedHashMap<>();
    private String seleccion;
    private transient Consumer<String> alSeleccionar = s -> { };

    PanelMapa() {
        setPreferredSize(new Dimension(780, 520));
        setBackground(Color.WHITE);
        setOpaque(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                for (Map.Entry<String, Rectangle> en : cajas.entrySet()) {
                    if (en.getValue().contains(e.getPoint())) {
                        seleccion = en.getKey();
                        alSeleccionar.accept(seleccion);
                        repaint();
                        return;
                    }
                }
            }
        });
    }

    void alSeleccionar(Consumer<String> c) {
        alSeleccionar = c;
    }

    void seleccionar(String id) {
        seleccion = id;
        repaint();
    }

    void actualizar(List<InfoNodo> n, List<InfoCelula> c) {
        nodos = n;
        celulas = c;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, getWidth(), getHeight());
        cajas.clear();

        Map<String, InfoNodo> porId = new TreeMap<>();
        for (InfoNodo n : nodos) {
            porId.put(n.id, n);
        }
        List<String> ids = new ArrayList<>(porId.keySet());
        if (ids.isEmpty()) {
            g.setColor(Ventanas.GRIS);
            g.setFont(Ventanas.normal(14));
            g.drawString("Esperando latidos de los nodos del Campo de Datos...", 30, 40);
            g.dispose();
            return;
        }
        Map<String, Point> pos = posiciones(ids);

        // enlaces entre nodos
        for (int i = 0; i < ids.size(); i++) {
            for (int j = i + 1; j < ids.size(); j++) {
                InfoNodo a = porId.get(ids.get(i));
                InfoNodo b = porId.get(ids.get(j));
                boolean ab = a.vecinos.contains(b.id) && !a.caido;
                boolean ba = b.vecinos.contains(a.id) && !b.caido;
                if (!ab && !ba) {
                    continue;
                }
                Point p = pos.get(a.id);
                Point q = pos.get(b.id);
                g.setColor(ab && ba ? new Color(40, 60, 100) : new Color(190, 120, 0));
                g.setStroke(ab && ba ? new BasicStroke(2f)
                        : new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 5f}, 0f));
                g.drawLine(p.x, p.y, q.x, q.y);
            }
        }

        // células por nodo
        Map<String, List<InfoCelula>> porNodo = new TreeMap<>();
        for (InfoCelula c : celulas) {
            if (c.nodo != null) {
                porNodo.computeIfAbsent(c.nodo, k -> new ArrayList<>()).add(c);
            }
        }
        int filas = Math.max(1, (ids.size() + 1) / 2);
        int disponible = (getHeight() - 30) / filas - 6;
        for (int idx = 0; idx < ids.size(); idx++) {
            String nid = ids.get(idx);
            Point p = pos.get(nid);
            List<InfoCelula> lista = new ArrayList<>(porNodo.getOrDefault(nid, List.of()));
            lista.sort(Comparator.<InfoCelula>comparingInt(c -> c.esServidor() ? 1 : 0).thenComparing(c -> c.id));
            boolean izquierda = idx % 2 == 0 && p.x != getWidth() / 2;
            int x = izquierda ? p.x - 70 - ANCHO_CELULA : p.x + 70;
            x = Math.max(4, Math.min(getWidth() - ANCHO_CELULA - 4, x));
            int paso = lista.isEmpty() ? ALTO_CELULA + 4 : Math.max(16, Math.min(ALTO_CELULA + 4, disponible / lista.size()));
            int altoCelda = Math.min(ALTO_CELULA, paso - 3);
            int alto = lista.size() * paso;
            int y0 = p.y - alto / 2;
            y0 = Math.max(4, Math.min(getHeight() - alto - 30, y0));
            for (int k = 0; k < lista.size(); k++) {
                InfoCelula c = lista.get(k);
                Rectangle r = new Rectangle(x, y0 + k * paso, ANCHO_CELULA, altoCelda);
                g.setColor(new Color(200, 205, 212));
                g.setStroke(new BasicStroke(1f));
                int ax = izquierda ? r.x + r.width : r.x;
                g.drawLine(ax, r.y + r.height / 2, izquierda ? p.x - 45 : p.x + 45, p.y);
                dibujarCelula(g, c, r);
                cajas.put(c.id, r);
            }
        }

        // nodos encima de las líneas
        for (String nid : ids) {
            dibujarNodo(g, porId.get(nid), pos.get(nid));
        }
        leyenda(g);
        g.dispose();
    }

    /**
     * Nodos en dos columnas (impares a la izquierda, pares a la derecha), como la Figura 1 del documento;
     * las células de cada nodo se listan hacia afuera. Funciona para cualquier número de nodos.
     */
    private Map<String, Point> posiciones(List<String> ids) {
        Map<String, Point> r = new LinkedHashMap<>();
        int w = getWidth();
        int h = getHeight() - 30;
        int filas = Math.max(1, (ids.size() + 1) / 2);
        for (int i = 0; i < ids.size(); i++) {
            int fila = i / 2;
            boolean izquierda = i % 2 == 0;
            boolean solo = ids.size() % 2 == 1 && i == ids.size() - 1 && ids.size() > 1;
            int x = solo ? w / 2 : izquierda ? w * 38 / 100 : w * 62 / 100;
            int y = (int) ((fila + 0.5) * h / filas);
            r.put(ids.get(i), new Point(x, y));
        }
        return r;
    }

    private void dibujarNodo(Graphics2D g, InfoNodo n, Point p) {
        int w = 90;
        int h = 46;
        Color fondo = n.caido ? Ventanas.ROJO : Ventanas.FONDO_ENCABEZADO;
        g.setColor(fondo);
        g.fillRoundRect(p.x - w / 2, p.y - h / 2, w, h, 14, 14);
        g.setColor(Color.WHITE);
        g.setFont(Ventanas.negrita(16));
        centrado(g, n.id, p.x, p.y - 2);
        g.setFont(Ventanas.normal(10));
        centrado(g, n.caido ? "SIN LATIDO" : ":" + n.puerto + " · " + n.celulas.size() + " cél.", p.x, p.y + 15);
    }

    private void dibujarCelula(Graphics2D g, InfoCelula c, Rectangle r) {
        boolean viva = c.viva();
        Color borde = switch (c.estado) {
            case VIVA -> new Color(120, 130, 145);
            case SOSPECHOSA -> Ventanas.AMBAR;
            case MUERTA -> Ventanas.ROJO;
            case RETIRADA -> new Color(190, 190, 190);
        };
        Color fondo = !viva ? new Color(253, 236, 236) : c.reemplaza != null ? Ventanas.FONDO_CLON : new Color(247, 249, 252);
        if (c.estado == Directorio.Estado.RETIRADA) {
            fondo = new Color(242, 242, 242);
        }
        g.setColor(fondo);
        g.fillRoundRect(r.x, r.y, r.width, r.height, 8, 8);
        Stroke s = c.estado == Directorio.Estado.RETIRADA
                ? new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{4f, 3f}, 0f)
                : new BasicStroke(c.id.equals(seleccion) ? 3f : 1.2f);
        g.setStroke(s);
        g.setColor(c.id.equals(seleccion) ? new Color(30, 110, 240) : borde);
        g.drawRoundRect(r.x, r.y, r.width, r.height, 8, 8);

        g.setFont(Ventanas.negrita(11));
        g.setColor(viva ? new Color(30, 35, 45) : Ventanas.ROJO);
        String nombre = c.id;
        String etiqueta = switch (c.clase) {
            case "CLIENTE" -> "GUI";
            case "GESTOR" -> "GESTOR";
            default -> null;
        };
        int tx = r.x + 6;
        FontMetrics fm = g.getFontMetrics();
        if (nombre.length() > 15) {
            nombre = nombre.substring(0, 14) + "…";
        }
        int base = r.y + r.height / 2 + 4;
        g.drawString(nombre, tx, base);
        tx += fm.stringWidth(nombre) + 6;
        if (!viva) {
            g.setFont(Ventanas.negrita(10));
            g.drawString(c.estado == Directorio.Estado.MUERTA ? "FALLA" : "RETIRADA", tx, base);
            return;
        }
        if (etiqueta != null) {
            insignia(g, etiqueta, new Color(90, 100, 115), tx, r.y + (r.height - 16) / 2);
            return;
        }
        if (c.reemplaza != null) {
            tx = insignia(g, "R" + c.generacion, new Color(170, 110, 0), tx, r.y + (r.height - 16) / 2);
        }
        if (c.genes.isEmpty()) {
            g.setFont(Ventanas.normal(10));
            g.setColor(Ventanas.GRIS);
            g.drawString("sin genes", tx, base);
        }
        for (InfoGen gen : c.genes) {
            String t = gen.simbolo + (gen.version.equals("1.0") ? "" : " v" + gen.version);
            tx = insignia(g, t, Ventanas.colorGen(gen.nombre), tx, r.y + (r.height - 16) / 2);
        }
        if (c.silenciada) {
            g.setFont(Ventanas.negrita(10));
            g.setColor(Ventanas.ROJO);
            g.drawString("mudo", Math.min(tx, r.x + r.width - 30), base);
        }
    }

    private int insignia(Graphics2D g, String t, Color c, int x, int y) {
        g.setFont(Ventanas.negrita(10));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(t) + 10;
        g.setColor(c);
        g.fillRoundRect(x, y, w, 16, 8, 8);
        g.setColor(Color.WHITE);
        g.drawString(t, x + 5, y + 12);
        return x + w + 3;
    }

    private void leyenda(Graphics2D g) {
        int y = getHeight() - 20;
        int x = 8;
        g.setFont(Ventanas.normal(10));
        String[][] gs = {{"SUMA", "+"}, {"RESTA", "-"}, {"MULTIPLICACION", "×"}, {"DIVISION", "÷"}, {"POTENCIA", "^"}};
        for (String[] e : gs) {
            x = insignia(g, e[1], Ventanas.colorGen(e[0]), x, y - 2);
            g.setColor(Ventanas.GRIS);
            g.setFont(Ventanas.normal(10));
            g.drawString(e[0].charAt(0) + e[0].substring(1).toLowerCase(), x, y + 10);
            x += g.getFontMetrics().stringWidth(e[0]) + 4;
        }
        x = insignia(g, "R1", new Color(170, 110, 0), x + 6, y - 2);
        g.setColor(Ventanas.GRIS);
        g.drawString("clon (generación)", x, y + 10);
        x += 100;
        g.setColor(Ventanas.ROJO);
        g.drawString("rojo = falla detectada", x, y + 10);
        g.setColor(Ventanas.GRIS);
        g.drawString("· actualizado " + Util.horaCorta(Util.ahora()), x + 130, y + 10);
    }

    private static void centrado(Graphics2D g, String t, int x, int y) {
        Font f = g.getFont();
        FontMetrics fm = g.getFontMetrics(f);
        g.drawString(t, x - fm.stringWidth(t) / 2, y + fm.getAscent() / 2 - 2);
    }
}
