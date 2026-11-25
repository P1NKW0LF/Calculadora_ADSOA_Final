package ads.celula.cliente;

import ads.celula.Directorio;
import ads.comun.CC;
import ads.comun.Mensaje;
import ads.comun.Util;
import ads.comun.Ventanas;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.JTextComponent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Interfaz de la célula cliente: calculadora + traza de sus propios eventos (folio, huella, ACK). */
final class VentanaCalculadora extends JFrame {

    private static final long serialVersionUID = 1L;

    private final transient CelulaCliente c;
    private final JTextField pantalla = new JTextField("0");
    private final JLabel subPantalla = new JLabel(" ");
    private final JLabel estadoConexion = new JLabel(" ");
    private final JLabel barra = new JLabel(" ");
    private final JPanel extras = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
    private final Map<String, JButton> botonesOp = new HashMap<>();
    private final Map<Integer, JButton> botonesExtra = new HashMap<>();
    private final List<EventoSolicitud> filas = new CopyOnWriteArrayList<>();
    private final ModeloEventos modelo = new ModeloEventos();
    private final JTable tabla = new JTable(modelo);
    private final JTextArea detalle = new JTextArea();
    private transient EventoSolicitud ultimoTeclado;
    private String operandoA;
    private Object[] opPendiente;
    private String entrada = "";
    private String ultimoResultado;

    static void mostrar(CelulaCliente c, String guion) {
        SwingUtilities.invokeLater(() -> {
            Ventanas.estilo();
            VentanaCalculadora v = new VentanaCalculadora(c);
            Ventanas.colocar(v, c.pos);
            v.setVisible(true);
            Ventanas.repintarLuego(v);
            if (guion != null && !guion.isBlank()) {
                v.teclearGuion(guion);
            }
        });
    }

    /**
     * Teclea operaciones en la calculadora como si se oprimieran las teclas ("5+3;0.1+0.2;espera:5;2^10").
     * Útil para grabar la demostración o para pruebas sin mouse.
     */
    void teclearGuion(String guion) {
        List<Runnable> pasos = new ArrayList<>();
        pasos.add(() -> { });
        for (String op : guion.split(";")) {
            String t = op.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.startsWith("espera:")) {
                int seg = Integer.parseInt(t.substring(7).trim());
                for (int i = 0; i < seg * 1000 / 150; i++) {
                    pasos.add(() -> { });
                }
                continue;
            }
            int k = -1;
            for (int i = 1; i < t.length(); i++) {
                if ("+-*/x^".indexOf(t.charAt(i)) >= 0) {
                    k = i;
                    break;
                }
            }
            if (k < 0) {
                continue;
            }
            String a = t.substring(0, k).trim();
            String b = t.substring(k + 1).trim();
            String nombre = switch (t.charAt(k)) {
                case '+' -> "SUMA";
                case '-' -> "RESTA";
                case '*', 'x' -> "MULTIPLICACION";
                case '/' -> "DIVISION";
                default -> "POTENCIA";
            };
            pasos.add(this::limpiar);
            for (char ch : a.toCharArray()) {
                pasos.add(() -> digito(String.valueOf(ch)));
            }
            pasos.add(() -> elegirOperacion(c.operacion(nombre)));
            for (char ch : b.toCharArray()) {
                pasos.add(() -> digito(String.valueOf(ch)));
            }
            pasos.add(this::igual);
            for (int i = 0; i < 8; i++) {
                pasos.add(() -> { });
            }
        }
        int[] i = {0};
        Timer t = new Timer(150, null);
        t.setInitialDelay(4000);
        t.addActionListener(e -> {
            if (i[0] >= pasos.size()) {
                t.stop();
                return;
            }
            pasos.get(i[0]++).run();
        });
        t.start();
    }

    private VentanaCalculadora(CelulaCliente c) {
        super("Calculadora ADS — célula cliente " + c.id);
        this.c = c;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                c.enviar(Mensaje.de(CC.CELULA_BAJA).con("celula", c.id).con("inicio", c.inicio)
                        .con("motivo", "calculadora cerrada por el usuario"));
                new Timer(300, x -> System.exit(0)).start();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                c.pos = Ventanas.posicionRelativa(VentanaCalculadora.this);
            }
        });

        JPanel cab = new JPanel(new BorderLayout());
        cab.setBackground(Ventanas.FONDO_ENCABEZADO);
        cab.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        cab.add(Ventanas.etiqueta("Célula cliente " + c.id, Ventanas.negrita(17), Ventanas.TEXTO_CLARO), BorderLayout.WEST);
        estadoConexion.setForeground(Ventanas.TEXTO_CLARO);
        estadoConexion.setFont(Ventanas.normal(12));
        cab.add(estadoConexion, BorderLayout.EAST);

        JSplitPane principal = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, panelCalculadora(), panelEventos());
        principal.setResizeWeight(0);
        principal.setDividerLocation(300);

        barra.setFont(Ventanas.normal(11));
        barra.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        add(cab, BorderLayout.NORTH);
        add(principal, BorderLayout.CENTER);
        add(barra, BorderLayout.SOUTH);
        setSize(960, 600);

        c.alEvento(ev -> SwingUtilities.invokeLater(() -> alCambiarEvento(ev)));
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this::tecla);
        new Timer(1000, e -> refrescar()).start();
        refrescar();
    }

    // ------------------------------------------------------------------ calculadora

    private JPanel panelCalculadora() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 4));
        pantalla.setEditable(false);
        pantalla.setHorizontalAlignment(SwingConstants.RIGHT);
        pantalla.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
        pantalla.setBackground(new Color(250, 251, 252));
        pantalla.setFocusable(false);
        subPantalla.setFont(Ventanas.normal(11));
        subPantalla.setForeground(Ventanas.GRIS);
        JPanel arriba = new JPanel(new BorderLayout());
        arriba.add(pantalla, BorderLayout.CENTER);
        arriba.add(subPantalla, BorderLayout.SOUTH);

        JPanel teclado = new JPanel(new GridLayout(5, 4, 4, 4));
        String[] teclas = {"C", "←", "±", "÷", "7", "8", "9", "×", "4", "5", "6", "-", "1", "2", "3", "+",
                "0", ".", "00", "="};
        for (String t : teclas) {
            JButton b = new JButton(t);
            b.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            b.setFocusable(false);
            b.setMargin(new java.awt.Insets(2, 2, 2, 2));
            switch (t) {
                case "+" -> operador(b, "SUMA");
                case "-" -> operador(b, "RESTA");
                case "×" -> operador(b, "MULTIPLICACION");
                case "÷" -> operador(b, "DIVISION");
                case "=" -> {
                    // fondo pintado a mano: el estilo nativo de Windows ignora setBackground en botones
                    b.setContentAreaFilled(false);
                    b.setOpaque(true);
                    b.setBackground(Ventanas.FONDO_ENCABEZADO);
                    b.setForeground(Color.WHITE);
                    b.addActionListener(e -> igual());
                }
                case "C" -> b.addActionListener(e -> limpiar());
                case "←" -> b.addActionListener(e -> borrar());
                case "±" -> b.addActionListener(e -> signo());
                default -> b.addActionListener(e -> digito(t));
            }
            teclado.add(b);
        }
        teclado.setPreferredSize(new Dimension(280, 230));

        JPanel abajo = new JPanel();
        abajo.setLayout(new BoxLayout(abajo, BoxLayout.Y_AXIS));
        extras.setBorder(Ventanas.titulo("Operaciones adicionales descubiertas"));
        extras.add(Ventanas.etiqueta("(ninguna adicional)", Ventanas.normal(11), Ventanas.GRIS));
        extras.setAlignmentX(LEFT_ALIGNMENT);
        abajo.add(extras);

        JPanel ack = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        ack.add(Ventanas.etiqueta("Min. # de ACK:", Ventanas.negrita(12), null));
        JSpinner sp = new JSpinner(new SpinnerNumberModel(c.minAck, 1, 16, 1));
        sp.addChangeListener(e -> {
            c.minAck = (Integer) sp.getValue();
            c.log.info("Min. # de ACK cambiado a " + c.minAck);
        });
        ack.add(sp);
        ack.setAlignmentX(LEFT_ALIGNMENT);
        abajo.add(ack);

        JPanel raf = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        JButton r1 = new JButton("Ráfaga (5)");
        r1.setToolTipText("Envía 5 operaciones aleatorias al mismo tiempo desde esta calculadora");
        r1.addActionListener(e -> c.rafaga(5));
        JButton r2 = new JButton("Ráfaga en todas las GUI");
        r2.setToolTipText("Todas las calculadoras del Campo de Datos envían 5 operaciones en el mismo instante");
        r2.addActionListener(e -> c.rafagaGlobal(5));
        for (JButton b : new JButton[]{r1, r2}) {
            b.setFont(Ventanas.normal(11));
            b.setFocusable(false);
            raf.add(b);
        }
        raf.setAlignmentX(LEFT_ALIGNMENT);
        abajo.add(raf);

        p.add(arriba, BorderLayout.NORTH);
        p.add(teclado, BorderLayout.CENTER);
        p.add(abajo, BorderLayout.SOUTH);
        return p;
    }

    private void operador(JButton b, String nombre) {
        botonesOp.put(nombre, b);
        b.setForeground(Ventanas.colorGen(nombre));
        b.addActionListener(e -> elegirOperacion(c.operacion(nombre)));
    }

    private void digito(String t) {
        if (".".equals(t) && entrada.contains(".")) {
            return;
        }
        if (entrada.length() > 18) {
            return;
        }
        entrada = (entrada.isEmpty() && ".".equals(t)) ? "0." : entrada + t;
        if (entrada.startsWith("00")) {
            entrada = "0";
        }
        mostrarEntrada();
    }

    private void borrar() {
        if (!entrada.isEmpty()) {
            entrada = entrada.substring(0, entrada.length() - 1);
        }
        mostrarEntrada();
    }

    private void signo() {
        if (entrada.isEmpty()) {
            return;
        }
        entrada = entrada.startsWith("-") ? entrada.substring(1) : "-" + entrada;
        mostrarEntrada();
    }

    private void limpiar() {
        entrada = "";
        operandoA = null;
        opPendiente = null;
        pantalla.setText("0");
        subPantalla.setText(" ");
    }

    private void elegirOperacion(Object[] op) {
        if (op == null) {
            return;
        }
        if (!entrada.isEmpty()) {
            operandoA = entrada;
            entrada = "";
        } else if (operandoA == null) {
            operandoA = ultimoResultado != null && esNumero(ultimoResultado) ? ultimoResultado : "0";
        }
        opPendiente = op;
        mostrarEntrada();
    }

    private void igual() {
        if (operandoA == null || opPendiente == null || entrada.isEmpty()) {
            return;
        }
        List<String> ops = List.of(operandoA, entrada);
        ultimoTeclado = c.solicitar((Integer) opPendiente[1], (String) opPendiente[0], (String) opPendiente[2], ops);
        pantalla.setText(operandoA + " " + opPendiente[2] + " " + entrada + " = …");
        subPantalla.setText("folio " + ultimoTeclado.folio + " · esperando ACK");
        operandoA = null;
        opPendiente = null;
        entrada = "";
    }

    private void mostrarEntrada() {
        String t = (operandoA != null ? operandoA + " " : "") + (opPendiente != null ? opPendiente[2] + " " : "") + entrada;
        pantalla.setText(t.isBlank() ? "0" : t.trim());
    }

    private static boolean esNumero(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private boolean tecla(KeyEvent e) {
        if (!isActive() || e.getID() != KeyEvent.KEY_TYPED) {
            return false;
        }
        Component f = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (f instanceof JTextComponent) {
            return false;
        }
        char ch = e.getKeyChar();
        if (Character.isDigit(ch) || ch == '.') {
            digito(String.valueOf(ch));
        } else if (ch == '+') {
            elegirOperacion(c.operacion("SUMA"));
        } else if (ch == '-') {
            elegirOperacion(c.operacion("RESTA"));
        } else if (ch == '*' || ch == 'x') {
            elegirOperacion(c.operacion("MULTIPLICACION"));
        } else if (ch == '/') {
            elegirOperacion(c.operacion("DIVISION"));
        } else if (ch == '\n' || ch == '=') {
            igual();
        } else if (ch == '\b') {
            borrar();
        } else if (ch == 27) {
            limpiar();
        } else {
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ eventos

    private JSplitPane panelEventos() {
        tabla.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        tabla.setRowHeight(22);
        tabla.setFont(Ventanas.normal(12));
        tabla.getTableHeader().setFont(Ventanas.negrita(12));
        int[] anchos = {150, 90, 100, 60, 125, 80, 45};
        for (int i = 0; i < anchos.length; i++) {
            tabla.getColumnModel().getColumn(i).setPreferredWidth(anchos[i]);
        }
        tabla.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int col) {
                Component x = super.getTableCellRendererComponent(t, v, sel, foc, r, col);
                if (!sel) {
                    String s = String.valueOf(v);
                    x.setForeground(s.startsWith("COMPLETADO") ? Ventanas.VERDE
                            : s.startsWith("RECHAZADO") || s.startsWith("FALLIDO") || s.startsWith("ERROR") ? Ventanas.ROJO
                            : Ventanas.AMBAR);
                }
                return x;
            }
        });
        tabla.getSelectionModel().addListSelectionListener(e -> mostrarDetalle());
        detalle.setEditable(false);
        detalle.setFont(Ventanas.mono(11));
        JScrollPane st = new JScrollPane(tabla);
        st.setBorder(Ventanas.titulo("Mis eventos (sólo las solicitudes de esta calculadora)"));
        JScrollPane sd = new JScrollPane(detalle);
        sd.setBorder(Ventanas.titulo("Detalle del evento: folio, huella, ACK, Min. # ACK y fases"));
        st.setPreferredSize(new Dimension(620, 190));
        sd.setPreferredSize(new Dimension(620, 300));
        JSplitPane s = new JSplitPane(JSplitPane.VERTICAL_SPLIT, st, sd);
        s.setResizeWeight(0.4);
        s.setDividerLocation(190);
        return s;
    }

    private void alCambiarEvento(EventoSolicitud ev) {
        if (!filas.contains(ev)) {
            filas.add(0, ev);
            while (filas.size() > 300) {
                filas.remove(filas.size() - 1);
            }
        }
        EventoSolicitud sel = ev == ultimoTeclado ? ev : seleccionado();
        modelo.fireTableDataChanged();
        if (sel != null) {
            int i = filas.indexOf(sel);
            if (i >= 0) {
                tabla.getSelectionModel().setSelectionInterval(i, i);
            }
        } else if (filas.size() == 1) {
            tabla.getSelectionModel().setSelectionInterval(0, 0);
        }
        if (ev == ultimoTeclado) {
            switch (ev.fase) {
                case COMPLETADO -> {
                    pantalla.setText(ev.valor);
                    ultimoResultado = ev.valor;
                    subPantalla.setText(ev.expresion() + " = " + ev.valor + "  ·  " + ev.ejecutor + " (" + ev.genResultado + " v"
                            + ev.versionResultado + ")  ·  ACK " + ev.numAcks() + "/" + ev.minAck);
                }
                case ERROR -> {
                    pantalla.setText("Error");
                    subPantalla.setText(ev.error);
                }
                case RECHAZADO, FALLIDO -> {
                    pantalla.setText(ev.fase.name());
                    subPantalla.setText(ev.error);
                }
                default -> subPantalla.setText("folio " + ev.folio + " · " + ev.fase + " · ACK " + ev.numAcks() + "/" + ev.minAck);
            }
        }
        if (ev == seleccionado()) {
            mostrarDetalle();
        }
    }

    private EventoSolicitud seleccionado() {
        int i = tabla.getSelectedRow();
        return i >= 0 && i < filas.size() ? filas.get(i) : null;
    }

    private void mostrarDetalle() {
        EventoSolicitud ev = seleccionado();
        if (ev == null) {
            detalle.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Folio .......... ").append(ev.folio).append('\n');
        sb.append("Firma (huella de origen) ").append(ev.firma).append('\n');
        sb.append("Código de Contenido  ").append(ev.cc).append(" (").append(ev.operacion).append(")   ")
                .append(ev.expresion()).append('\n');
        sb.append("Estado ......... ").append(ev.fase);
        if (ev.valor != null) {
            sb.append("  =  ").append(ev.valor);
        }
        if (ev.error != null) {
            sb.append("  (").append(ev.error).append(')');
        }
        sb.append("   [").append(ev.duracion()).append(" ms]\n");
        List<String> ids = new ArrayList<>();
        for (EventoSolicitud.Ack a : ev.copiaAcks()) {
            ids.add(a.celula + (a.tardio ? "(tardío)" : ""));
        }
        sb.append("ACK ............ ").append(ev.numAcks()).append(" recibidos, Min. # ACK = ").append(ev.minAck)
                .append("  ").append(ids).append('\n');
        sb.append("Esperadas ...... ").append(ev.esperados).append('\n');
        sb.append("Ausentes ....... ").append(ev.ausentes.isEmpty() ? "ninguna" : ev.ausentes).append('\n');
        sb.append("Ejecutora ...... ").append(ev.ejecutor == null ? "-" : ev.ejecutor)
                .append(ev.genResultado != null ? "  (" + ev.genResultado + " v" + ev.versionResultado + ")" : "").append('\n');
        if (!ev.huellaResultadoIda.isEmpty()) {
            sb.append("Huella ida ..... ").append(Mensaje.trayectoria(ev.huellaResultadoIda)).append(" → ")
                    .append(ev.ejecutor).append('\n');
            sb.append("Huella vuelta .. ").append(Mensaje.trayectoria(ev.huellaResultadoVuelta)).append(" → ")
                    .append(c.id).append('\n');
        }
        sb.append("\nFases del evento:\n");
        for (String l : ev.traza()) {
            sb.append(l).append('\n');
        }
        detalle.setText(sb.toString());
        detalle.setCaretPosition(0);
    }

    private void refrescar() {
        boolean con = c.conectada();
        estadoConexion.setText(con ? "● Conectada a " + c.nodoActual + " (" + c.dirActual.direccion() + ")"
                : "○ Sin conexión: buscando nodo...");
        estadoConexion.setForeground(con ? new Color(140, 230, 160) : new Color(255, 170, 170));
        Map<Integer, Directorio.InfoGen> ops = c.operacionesConocidas();
        StringBuilder sb = new StringBuilder("<html>Proveedores vivos: ");
        for (Object[] e : CelulaCliente.ESTANDAR) {
            Object[] real = c.operacion((String) e[0]);
            int n = c.directorio.proveedores((Integer) real[1]).size();
            JButton b = botonesOp.get((String) e[0]);
            if (b != null) {
                b.setText("<html><center>" + e[2] + "<br><font size='2'>" + n + "</font></center></html>");
                b.setToolTipText(e[0] + " · CC " + real[1] + " · " + n + " célula(s) con el gen");
            }
            sb.append("<b>").append(e[2]).append("</b> ").append(n).append(" &nbsp;");
        }
        for (Directorio.InfoGen g : ops.values()) {
            boolean estandar = false;
            for (Object[] e : CelulaCliente.ESTANDAR) {
                if (e[0].equals(g.nombre)) {
                    estandar = true;
                }
            }
            if (estandar) {
                continue;
            }
            int n = c.directorio.proveedores(g.cc).size();
            sb.append("<b>").append(g.simbolo).append("</b> ").append(n).append(" &nbsp;");
            JButton b = botonesExtra.get(g.cc);
            if (b == null) {
                if (botonesExtra.isEmpty()) {
                    extras.removeAll();
                }
                b = new JButton();
                b.setFont(Ventanas.negrita(12));
                b.setForeground(Ventanas.colorGen(g.nombre));
                b.setFocusable(false);
                String nombre = g.nombre;
                b.addActionListener(ev -> elegirOperacion(c.operacion(nombre)));
                botonesExtra.put(g.cc, b);
                extras.add(b);
                extras.revalidate();
            }
            b.setText(g.simbolo + "  " + g.nombre + " (" + n + ")");
            b.setToolTipText(g.descripcion + " · CC " + g.cc + " · v" + g.version);
        }
        sb.append("&nbsp;|&nbsp; Respuestas ajenas descartadas: <b>").append(c.ajenos.get())
                .append("</b> &nbsp;|&nbsp; Respuestas propias: ").append(c.propios.get())
                .append(" &nbsp;|&nbsp; Min. # ACK: ").append(c.minAck).append("</html>");
        barra.setText(sb.toString());
        modelo.fireTableRowsUpdated(0, Math.max(0, filas.size() - 1));
    }

    private final class ModeloEventos extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final String[] cols = {"Folio", "Operación", "Estado", "ACK/mín", "Resultado", "Ejecutora", "ms"};

        @Override
        public int getRowCount() {
            return filas.size();
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int i) {
            return cols[i];
        }

        @Override
        public Object getValueAt(int r, int col) {
            if (r >= filas.size()) {
                return "";
            }
            EventoSolicitud ev = filas.get(r);
            return switch (col) {
                case 0 -> ev.folio;
                case 1 -> ev.expresion();
                case 2 -> ev.fase.name();
                case 3 -> ev.numAcks() + "/" + ev.minAck;
                case 4 -> ev.valor != null ? ev.valor : ev.error != null ? ev.error : "…";
                case 5 -> ev.ejecutor == null ? "-" : ev.ejecutor;
                default -> String.valueOf(ev.duracion());
            };
        }
    }

    @SuppressWarnings("unused")
    private static String hora(long t) {
        return Util.horaCorta(t);
    }
}
