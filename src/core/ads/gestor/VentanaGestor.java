package ads.gestor;

import ads.celula.Directorio;
import ads.celula.Directorio.InfoCelula;
import ads.celula.Directorio.InfoGen;
import ads.celula.Directorio.InfoNodo;
import ads.celula.servidor.GestorGenes;
import ads.comun.Util;
import ads.comun.Ventanas;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/** Interfaz del Gestor de Servicios: topología, catálogo de genes y alta/baja/modificación de servicios. */
final class VentanaGestor extends JFrame {

    private static final long serialVersionUID = 1L;

    private final transient GestorServicios g;
    private final PanelMapa mapa = new PanelMapa();
    private final JLabel estado = new JLabel(" ");
    private String seleccion;

    private final JLabel selId = new JLabel("Seleccione una célula en el mapa o en la tabla");
    private final JLabel selDetalle = new JLabel(" ");
    private final JComboBox<String> comboGen = new JComboBox<>();
    private final JComboBox<String> comboVersion = new JComboBox<>();
    private final JComboBox<String> comboActivo = new JComboBox<>();
    private final JComboBox<String> comboNuevaVersion = new JComboBox<>();
    private final JButton btnAlta = new JButton("Dar de alta");
    private final JButton btnBaja = new JButton("Dar de baja");
    private final JButton btnSustituir = new JButton("Sustituir");
    private final JButton btnSilenciar = new JButton("Silenciar ACK");
    private final JButton btnFalla = new JButton("Simular falla");
    private final JButton btnRetirar = new JButton("Retiro ordenado");
    private final JComboBox<String> comboNodo = new JComboBox<>();
    private final JComboBox<String> comboNodoFalla = new JComboBox<>();

    private final DefaultTableModel tCelulas = modelo("Id", "Clase", "Nodo", "Estado", "Genes activos", "Gen.", "Padre",
            "Reemplaza a", "ACK", "Activa desde");
    private final DefaultTableModel tNodos = modelo("Nodo", "Dirección", "Estado", "Vecinos (enlaces)", "Células conectadas",
            "Recibidos", "Duplicados descartados");
    private final DefaultTableModel tCatalogo = modelo("Gen", "Versión", "CC", "Símbolo", "Archivo en disco", "SHA-256",
            "Integridad", "Descripción");
    private final DefaultTableModel tServicios = modelo("Servicio", "CC", "Activas", "Deudas", "Requerido", "Estado");
    private final DefaultTableModel tDeudas = modelo("Célula perdida", "Genes", "Estado", "Reservada por", "Clon");
    private final DefaultTableModel tComandos = modelo("Folio", "Comando", "Estado", "ACK", "Detalle", "ms");
    private final JTable tablaCelulas = tabla(tCelulas);
    private transient List<GestorGenes.Descriptor> catalogo = new ArrayList<>();

    static void mostrar(GestorServicios g) {
        SwingUtilities.invokeLater(() -> {
            Ventanas.estilo();
            VentanaGestor v = new VentanaGestor(g);
            Rectangle a = Ventanas.areaUtil();
            v.setSize(Math.min(1320, a.width), Math.min(860, a.height));
            Ventanas.colocar(v, g.pos);
            v.setVisible(true);
            Ventanas.repintarLuego(v);
        });
    }

    private VentanaGestor(GestorServicios g) {
        super("Gestor de Servicios ADS");
        this.g = g;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                System.exit(0); // el sistema sigue funcionando sin el gestor
            }
        });

        JPanel cab = new JPanel(new BorderLayout());
        cab.setBackground(Ventanas.FONDO_ENCABEZADO);
        cab.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JPanel izq = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        izq.setOpaque(false);
        izq.add(Ventanas.etiqueta("Gestor de Servicios", Ventanas.negrita(18), Ventanas.TEXTO_CLARO));
        estado.setForeground(Ventanas.TEXTO_CLARO);
        estado.setFont(Ventanas.normal(12));
        izq.add(estado);
        JPanel der = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        der.setOpaque(false);
        JButton consultar = new JButton("Consultar topología");
        consultar.setToolTipText("Difunde CONSULTA_TOPOLOGIA: todos los nodos y células responden con su latido");
        consultar.addActionListener(e -> g.consultarTopologia());
        JButton desplegar = new JButton("Despliegue inicial");
        desplegar.setToolTipText("En cada nodo asigna SUMA, RESTA, MULTIPLICACIÓN y DIVISIÓN a las células sin genes");
        desplegar.addActionListener(e -> {
            int n = g.despliegueInicial().size();
            if (n == 0) {
                JOptionPane.showMessageDialog(this, "No hay células sin genes a las que asignar operaciones.",
                        "Despliegue inicial", JOptionPane.INFORMATION_MESSAGE);
            }
        });
        der.add(consultar);
        der.add(desplegar);
        cab.add(izq, BorderLayout.WEST);
        cab.add(der, BorderLayout.EAST);

        mapa.alSeleccionar(this::seleccionar);
        JPanel pm = new JPanel(new BorderLayout());
        pm.setBorder(Ventanas.titulo("Topología del Campo de Datos (clic en una célula para seleccionarla)"));
        pm.add(mapa, BorderLayout.CENTER);

        JSplitPane arriba = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, pm, panelDerecho());
        arriba.setResizeWeight(0.62);
        arriba.setDividerLocation(800);

        JTabbedPane pestañas = new JTabbedPane();
        tablaCelulas.getSelectionModel().addListSelectionListener(e -> {
            int i = tablaCelulas.getSelectedRow();
            if (!e.getValueIsAdjusting() && i >= 0) {
                seleccionar(String.valueOf(tCelulas.getValueAt(i, 0)));
            }
        });
        pestañas.addTab("Células", new JScrollPane(tablaCelulas));
        pestañas.addTab("Nodos", new JScrollPane(tabla(tNodos)));
        pestañas.addTab("Catálogo de genes (disco)", new JScrollPane(tabla(tCatalogo)));
        pestañas.addTab("Comandos (folios)", new JScrollPane(tabla(tComandos)));
        JTextArea log = Ventanas.areaBitacora(8);
        g.log.alEscribir(l -> Ventanas.agregarLinea(log, l.formato(), 800));
        pestañas.addTab("Bitácora del Campo de Datos", new JScrollPane(log));
        pestañas.setSelectedIndex(4);

        JPanel abajo = new JPanel(new BorderLayout());
        abajo.add(pestañas, BorderLayout.CENTER);
        abajo.add(barraTopologia(), BorderLayout.SOUTH);

        JSplitPane principal = new JSplitPane(JSplitPane.VERTICAL_SPLIT, arriba, abajo);
        principal.setResizeWeight(0.68);
        principal.setDividerLocation(540);

        add(cab, BorderLayout.NORTH);
        add(principal, BorderLayout.CENTER);

        comboGen.addActionListener(e -> llenarVersiones());
        comboActivo.addActionListener(e -> llenarNuevasVersiones());
        g.alComando(c -> SwingUtilities.invokeLater(this::refrescarComandos));
        new Timer(1000, e -> refrescar()).start();
        refrescarCatalogo();
        refrescar();
    }

    // ------------------------------------------------------------------ paneles

    private JPanel panelDerecho() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        JPanel sel = new JPanel(new BorderLayout());
        sel.setBorder(Ventanas.titulo("Célula seleccionada"));
        selDetalle.setPreferredSize(new Dimension(400, 34));
        selId.setFont(Ventanas.negrita(14));
        selDetalle.setFont(Ventanas.normal(11));
        sel.add(selId, BorderLayout.NORTH);
        sel.add(selDetalle, BorderLayout.CENTER);
        p.add(sel);

        JPanel genes = new JPanel(new GridBagLayout());
        genes.setBorder(Ventanas.titulo("Genes activos de la célula (carga dinámica de microservicios)"));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(2, 3, 2, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridy = 0;
        c.gridx = 0;
        genes.add(new JLabel("Alta:"), c);
        c.gridx = 1;
        c.weightx = 1;
        genes.add(comboGen, c);
        c.gridx = 2;
        c.weightx = 0;
        genes.add(comboVersion, c);
        c.gridx = 3;
        genes.add(btnAlta, c);
        c.gridy = 1;
        c.gridx = 0;
        genes.add(new JLabel("Activo:"), c);
        c.gridx = 1;
        c.weightx = 1;
        genes.add(comboActivo, c);
        c.gridx = 2;
        c.weightx = 0;
        genes.add(comboNuevaVersion, c);
        c.gridx = 3;
        genes.add(btnSustituir, c);
        c.gridy = 2;
        c.gridx = 3;
        genes.add(btnBaja, c);
        btnAlta.addActionListener(e -> {
            if (seleccion != null && comboGen.getSelectedItem() != null) {
                g.alta(seleccion, (String) comboGen.getSelectedItem(), (String) comboVersion.getSelectedItem());
            }
        });
        btnBaja.addActionListener(e -> {
            if (seleccion != null && comboActivo.getSelectedItem() != null) {
                g.baja(seleccion, nombreActivo());
            }
        });
        btnSustituir.addActionListener(e -> {
            if (seleccion != null && comboActivo.getSelectedItem() != null && comboNuevaVersion.getSelectedItem() != null) {
                g.modificar(seleccion, nombreActivo(), (String) comboNuevaVersion.getSelectedItem());
            }
        });
        p.add(genes);

        JPanel acc = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        acc.setBorder(Ventanas.titulo("Acciones sobre la célula (demostración)"));
        btnSilenciar.setToolTipText("La célula sigue viva pero deja de enviar ACK (escenario Min. # de ACK)");
        btnSilenciar.addActionListener(e -> {
            InfoCelula x = seleccion == null ? null : g.directorio.celula(seleccion);
            if (x != null) {
                g.silenciar(x.id, !x.silenciada);
            }
        });
        btnFalla.setToolTipText("La célula termina abruptamente sin avisar: dispara la autorrecuperación");
        btnFalla.addActionListener(e -> {
            if (seleccion != null) {
                g.simularFalla(seleccion);
            }
        });
        btnRetirar.setToolTipText("Baja ordenada: la célula avisa y termina; no se clona");
        btnRetirar.addActionListener(e -> {
            if (seleccion != null) {
                g.retirar(seleccion);
            }
        });
        acc.add(btnSilenciar);
        acc.add(btnFalla);
        acc.add(btnRetirar);
        p.add(acc);

        JScrollPane ss = new JScrollPane(tabla(tServicios));
        ss.setBorder(Ventanas.titulo("Disponibilidad por servicio (requerido = activas + deudas)"));
        ss.setPreferredSize(new Dimension(420, 175));
        p.add(ss);
        JScrollPane sd = new JScrollPane(tabla(tDeudas));
        sd.setBorder(Ventanas.titulo("Deudas de recuperación (capacidad perdida por fallas)"));
        sd.setPreferredSize(new Dimension(420, 110));
        p.add(sd);
        for (JPanel fijo : new JPanel[]{sel, genes, acc}) {
            fijo.setMaximumSize(new Dimension(Integer.MAX_VALUE, fijo.getPreferredSize().height));
        }
        return p;
    }

    private JPanel barraTopologia() {
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        b.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(210, 214, 220)));
        b.add(Ventanas.etiqueta("Topología:", Ventanas.negrita(12), null));
        JButton nueva = new JButton("Nueva célula servidor (sin genes) en");
        nueva.addActionListener(e -> {
            String n = (String) comboNodo.getSelectedItem();
            if (n == null) {
                return;
            }
            try {
                String id = g.nuevaCelulaServidor(n, false);
                seleccionarLuego(id);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Nueva célula", JOptionPane.ERROR_MESSAGE);
            }
        });
        b.add(nueva);
        b.add(comboNodo);
        JButton nodo = new JButton("Agregar nodo");
        nodo.addActionListener(e -> {
            try {
                g.nuevoNodo(false);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, ex.getMessage(), "Agregar nodo", JOptionPane.ERROR_MESSAGE);
            }
        });
        b.add(nodo);
        JButton fallaNodo = new JButton("Simular falla del nodo");
        fallaNodo.addActionListener(e -> {
            String n = (String) comboNodoFalla.getSelectedItem();
            if (n != null) {
                g.simularFalla(n);
            }
        });
        b.add(fallaNodo);
        b.add(comboNodoFalla);
        JButton apagar = new JButton("Apagar sistema");
        apagar.setForeground(Ventanas.ROJO);
        apagar.addActionListener(e -> {
            int r = JOptionPane.showConfirmDialog(this, "Se pedirá a TODOS los nodos y células terminar (sin clonación).\n¿Continuar?",
                    "Apagar sistema", JOptionPane.OK_CANCEL_OPTION);
            if (r == JOptionPane.OK_OPTION) {
                g.apagarSistema();
            }
        });
        b.add(Box.glue());
        b.add(apagar);
        return b;
    }

    private void seleccionarLuego(String id) {
        Timer t = new Timer(2500, e -> seleccionar(id));
        t.setRepeats(false);
        t.start();
    }

    // ------------------------------------------------------------------ selección

    private void seleccionar(String id) {
        seleccion = id;
        mapa.seleccionar(id);
        for (int i = 0; i < tCelulas.getRowCount(); i++) {
            if (Objects.equals(tCelulas.getValueAt(i, 0), id) && tablaCelulas.getSelectedRow() != i) {
                tablaCelulas.getSelectionModel().setSelectionInterval(i, i);
            }
        }
        refrescarSeleccion();
    }

    private String nombreActivo() {
        String s = (String) comboActivo.getSelectedItem();
        return s == null ? null : s.split(" ")[0];
    }

    private void refrescarSeleccion() {
        InfoCelula c = seleccion == null ? null : g.directorio.celula(seleccion);
        boolean servidor = c != null && c.esServidor() && c.viva();
        for (Component x : new Component[]{btnAlta, btnBaja, btnSustituir, btnSilenciar, btnFalla, btnRetirar}) {
            x.setEnabled(servidor || (c != null && c.viva() && (x == btnFalla)));
        }
        if (c == null) {
            selId.setText(seleccion == null ? "Seleccione una célula en el mapa o en la tabla" : seleccion + " (ya no está)");
            selDetalle.setText(" ");
            comboActivo.setModel(new DefaultComboBoxModel<>());
            return;
        }
        selId.setText(c.id + "   [" + c.clase + "]   " + c.estado);
        selDetalle.setText("<html>Nodo <b>" + c.nodo + "</b> · pid " + c.pid + " · generación " + c.generacion
                + (c.reemplaza != null ? " · <b>clon</b> de " + c.padre + " que reemplaza a <b>" + c.reemplaza + "</b>" : "")
                + "<br>Genes activos: <b>" + c.genesTexto() + "</b>"
                + (c.silenciada ? " · <font color='#c82828'><b>ACK silenciados</b></font>" : "") + "</html>");
        btnSilenciar.setText(c.silenciada ? "Reactivar ACK" : "Silenciar ACK");
        List<String> activos = new ArrayList<>();
        for (InfoGen gen : c.genes) {
            activos.add(gen.nombre + " v" + gen.version);
        }
        Object prev = comboActivo.getSelectedItem();
        if (!activos.equals(itemsDe(comboActivo))) {
            comboActivo.setModel(new DefaultComboBoxModel<>(activos.toArray(new String[0])));
            if (prev != null && activos.contains(prev)) {
                comboActivo.setSelectedItem(prev);
            }
            llenarNuevasVersiones();
        }
    }

    private static List<String> itemsDe(JComboBox<String> cb) {
        List<String> r = new ArrayList<>();
        for (int i = 0; i < cb.getItemCount(); i++) {
            r.add(cb.getItemAt(i));
        }
        return r;
    }

    private void llenarVersiones() {
        String gen = (String) comboGen.getSelectedItem();
        List<String> vs = new ArrayList<>();
        for (GestorGenes.Descriptor d : catalogo) {
            if (d.nombre.equals(gen)) {
                vs.add(d.version);
            }
        }
        comboVersion.setModel(new DefaultComboBoxModel<>(vs.toArray(new String[0])));
    }

    private void llenarNuevasVersiones() {
        String gen = nombreActivo();
        List<String> vs = new ArrayList<>();
        for (GestorGenes.Descriptor d : catalogo) {
            if (d.nombre.equals(gen)) {
                vs.add(d.version);
            }
        }
        comboNuevaVersion.setModel(new DefaultComboBoxModel<>(vs.toArray(new String[0])));
        String actual = (String) comboActivo.getSelectedItem();
        for (String v : vs) {
            if (actual != null && !actual.endsWith("v" + v)) {
                comboNuevaVersion.setSelectedItem(v);
                break;
            }
        }
    }

    // ------------------------------------------------------------------ refresco

    private void refrescarCatalogo() {
        catalogo = g.catalogo();
        tCatalogo.setRowCount(0);
        TreeSet<String> nombres = new TreeSet<>();
        for (GestorGenes.Descriptor d : catalogo) {
            nombres.add(d.nombre);
            String sha;
            try {
                sha = Util.sha256(d.jar);
            } catch (Exception e) {
                sha = "?";
            }
            String reg = GestorGenes.integridadRegistrada(d.jar);
            tCatalogo.addRow(new Object[]{d.nombre, d.version, d.cc, d.simbolo, d.jar.getFileName().toString(), Util.corto(sha),
                    reg == null ? "sin registrar" : reg.equalsIgnoreCase(sha) ? "correcta" : "ALTERADO", d.descripcion});
        }
        Object prev = comboGen.getSelectedItem();
        comboGen.setModel(new DefaultComboBoxModel<>(nombres.toArray(new String[0])));
        if (prev != null) {
            comboGen.setSelectedItem(prev);
        }
        llenarVersiones();
    }

    private int ciclo;

    private void refrescar() {
        ciclo++;
        if (ciclo % 10 == 0) {
            refrescarCatalogo();
        }
        List<InfoNodo> nodos = g.directorio.nodos();
        List<InfoCelula> celulas = g.directorio.celulas();
        List<InfoCelula> paraMapa = new ArrayList<>(celulas);
        if (g.nodoActual != null) {
            paraMapa.add(Directorio.sintetica(g.id + " (este)", "GESTOR", g.nodoActual, g.inicio));
        }
        mapa.actualizar(nodos, paraMapa);
        int vivas = 0;
        for (InfoCelula c : celulas) {
            if (c.viva()) {
                vivas++;
            }
        }
        int nodosVivos = 0;
        for (InfoNodo n : nodos) {
            if (!n.caido) {
                nodosVivos++;
            }
        }
        estado.setText((g.conectada() ? "● conectado a " + g.nodoActual : "○ sin conexión") + "   ·   " + nodosVivos
                + " nodos   ·   " + vivas + " células vivas (además del gestor)");

        int sel = tablaCelulas.getSelectedRow();
        String selId0 = sel >= 0 ? String.valueOf(tCelulas.getValueAt(sel, 0)) : null;
        tCelulas.setRowCount(0);
        int fila = -1;
        for (InfoCelula c : celulas) {
            tCelulas.addRow(new Object[]{c.id, c.clase, c.nodo, c.estado, c.esServidor() ? c.genesTexto() : "-", c.generacion,
                    c.padre == null ? "-" : c.padre, c.reemplaza == null ? "-" : c.reemplaza,
                    c.silenciada ? "silenciados" : "normal", c.inicio > 0 ? Util.horaCorta(c.inicio) : "-"});
            if (c.id.equals(selId0) || c.id.equals(seleccion)) {
                fila = tCelulas.getRowCount() - 1;
            }
        }
        if (fila >= 0) {
            tablaCelulas.getSelectionModel().setSelectionInterval(fila, fila);
        }

        tNodos.setRowCount(0);
        List<String> ids = new ArrayList<>();
        for (InfoNodo n : nodos) {
            if (!n.caido) {
                ids.add(n.id);
            }
            tNodos.addRow(new Object[]{n.id, n.direccion(), n.caido ? "SIN LATIDO" : "activo", String.join(", ", n.vecinos),
                    n.celulas.size() + "  " + n.celulas, n.stats.getOrDefault("recibidos", "-"),
                    n.stats.getOrDefault("duplicados", "-")});
        }
        if (!ids.equals(itemsDe(comboNodo))) {
            comboNodo.setModel(new DefaultComboBoxModel<>(ids.toArray(new String[0])));
            comboNodoFalla.setModel(new DefaultComboBoxModel<>(ids.toArray(new String[0])));
        }

        tServicios.setRowCount(0);
        Map<String, int[]> disp = g.disponibilidad();
        Map<Integer, InfoGen> ops = g.directorio.operaciones();
        for (Map.Entry<String, int[]> en : disp.entrySet()) {
            int cc = 0;
            for (InfoGen x : ops.values()) {
                if (x.nombre.equals(en.getKey())) {
                    cc = x.cc;
                }
            }
            int act = en.getValue()[0];
            int deu = en.getValue()[1];
            tServicios.addRow(new Object[]{en.getKey(), cc == 0 ? "-" : cc, act, deu, act + deu,
                    deu == 0 ? "OK" : act == 0 ? "SIN SERVICIO" : "RECUPERANDO"});
        }
        tDeudas.setRowCount(0);
        for (GestorServicios.DeudaVista d : g.deudas()) {
            tDeudas.addRow(new Object[]{d.celula(), d.genes(), d.estado() + (d.motivo() != null && !"null".equals(d.motivo())
                    && !d.motivo().isEmpty() ? " (" + d.motivo() + ")" : ""),
                    d.reservadaPor() == null ? "-" : d.reservadaPor(), d.clon() == null ? "-" : d.clon()});
        }
        refrescarSeleccion();
        refrescarComandos();
    }

    private void refrescarComandos() {
        tComandos.setRowCount(0);
        for (GestorServicios.Comando c : g.comandos()) {
            tComandos.addRow(new Object[]{c.folio, c.descripcion(), c.estado, c.ack ? "sí" : "no", c.detalle,
                    Util.ahora() - c.t0 > 60_000 ? "-" : String.valueOf(Util.ahora() - c.t0)});
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static DefaultTableModel modelo(String... cols) {
        return new DefaultTableModel(cols, 0) {
            private static final long serialVersionUID = 1L;

            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }
        };
    }

    private static JTable tabla(DefaultTableModel m) {
        JTable t = new JTable(m);
        t.setRowHeight(20);
        t.setFont(Ventanas.normal(12));
        t.getTableHeader().setFont(Ventanas.negrita(11));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        t.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable tb, Object v, boolean s, boolean f, int r, int c) {
                Component x = super.getTableCellRendererComponent(tb, v, s, f, r, c);
                if (!s) {
                    String txt = String.valueOf(v);
                    x.setForeground(switch (txt) {
                        case "MUERTA", "SIN LATIDO", "ALTERADO", "SIN SERVICIO", "ERROR", "SIN ACK", "SIN RESPUESTA" -> Ventanas.ROJO;
                        case "SOSPECHOSA", "RECUPERANDO", "PENDIENTE", "RESERVADA", "silenciados", "ENVIADO", "ACK" -> Ventanas.AMBAR;
                        case "VIVA", "OK", "COMPLETADO", "correcta", "activo" -> Ventanas.VERDE;
                        case "RETIRADA" -> Ventanas.GRIS;
                        default -> Color.BLACK;
                    });
                }
                return x;
            }
        });
        return t;
    }

    private static final class Box {
        static Component glue() {
            return javax.swing.Box.createHorizontalStrut(30);
        }
    }

    @SuppressWarnings("unused")
    private static Directorio.Estado estado(InfoCelula c) {
        return c.estado;
    }
}
