package ads.celula.servidor;

import ads.comun.Util;
import ads.comun.Ventanas;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/** Ventana compacta de una célula servidor. Cerrarla equivale a una falla abrupta. */
final class VentanaServidor extends JFrame {

    private static final long serialVersionUID = 1L;

    private final transient CelulaServidor c;
    private final JPanel cabecera = new JPanel(new BorderLayout());
    private final JLabel titulo = new JLabel();
    private final JLabel estado = new JLabel();
    private final JPanel insignias = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1)) {
        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    };
    private final JPanel centro = new JPanel();
    private final JPanel botones = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
    private final JLabel contadores = new JLabel(" ");
    private final JLabel linaje = new JLabel(" ");
    private final JToggleButton silenciar = new JToggleButton("Silenciar ACK");
    private String firmaGenes = "";

    static void mostrar(CelulaServidor c) {
        SwingUtilities.invokeLater(() -> {
            Ventanas.estilo();
            VentanaServidor v = new VentanaServidor(c);
            Ventanas.colocar(v, c.pos);
            v.setVisible(true);
            Ventanas.repintarLuego(v);
            c.pos = Ventanas.posicionRelativa(v);
        });
    }

    private VentanaServidor(CelulaServidor c) {
        super("Célula servidor " + c.id);
        this.c = c;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                c.simularFalla();
            }
        });
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentMoved(ComponentEvent e) {
                c.pos = Ventanas.posicionRelativa(VentanaServidor.this);
            }
        });

        cabecera.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
        titulo.setFont(Ventanas.negrita(14));
        titulo.setForeground(Color.WHITE);
        estado.setFont(Ventanas.normal(11));
        estado.setForeground(Color.WHITE);
        cabecera.add(titulo, BorderLayout.WEST);
        cabecera.add(estado, BorderLayout.EAST);

        centro.setLayout(new BoxLayout(centro, BoxLayout.Y_AXIS));
        centro.setBorder(BorderFactory.createEmptyBorder(4, 6, 2, 6));
        insignias.setAlignmentX(LEFT_ALIGNMENT);
        insignias.setOpaque(false);
        JPanel filaGenes = insignias;
        contadores.setFont(Ventanas.normal(11));
        contadores.setAlignmentX(LEFT_ALIGNMENT);
        linaje.setFont(Ventanas.normal(11));
        linaje.setAlignmentX(LEFT_ALIGNMENT);
        centro.add(filaGenes);
        centro.add(contadores);
        centro.add(linaje);

        JTextArea log = Ventanas.areaBitacora(3);
        log.setFont(Ventanas.mono(10));
        c.log.alEscribir(l -> Ventanas.agregarLinea(log, Util.horaCorta(l.ts()) + " " + l.texto(), 250));
        JScrollPane sl = new JScrollPane(log);
        sl.setAlignmentX(LEFT_ALIGNMENT);
        sl.setPreferredSize(new Dimension(280, 62));
        centro.add(sl);

        silenciar.setToolTipText("La célula sigue viva (late) pero deja de enviar ACK: escenario Min. # de ACK");
        silenciar.addActionListener(e -> {
            c.silenciada = silenciar.isSelected();
            c.log.evento(c.silenciada ? "SILENCIADA desde su ventana: no enviará ACK" : "Reactivada: vuelve a enviar ACK");
            c.enviarLatido();
        });
        JButton falla = new JButton("Simular falla");
        falla.setToolTipText("Termina el proceso de golpe, sin avisar (provoca la autorrecuperación)");
        falla.addActionListener(e -> c.simularFalla());
        JButton retirar = new JButton("Retirar");
        retirar.setToolTipText("Baja ordenada: avisa al Campo de Datos y termina (no provoca clonación)");
        retirar.addActionListener(e -> c.retirarse("solicitada desde la ventana de la célula"));
        for (javax.swing.AbstractButton b : new javax.swing.AbstractButton[]{silenciar, falla, retirar}) {
            b.setFont(Ventanas.normal(10));
            b.setMargin(new java.awt.Insets(1, 4, 1, 4));
            botones.add(b);
        }

        add(cabecera, BorderLayout.NORTH);
        add(centro, BorderLayout.CENTER);
        add(botones, BorderLayout.SOUTH);
        setSize(300, 225);
        new Timer(700, e -> refrescar()).start();
        refrescar();
    }

    private void refrescar() {
        var activos = c.genes.activos();
        Color color = activos.isEmpty() ? Ventanas.GRIS : Ventanas.colorGen(activos.get(0).d.nombre);
        cabecera.setBackground(color);
        titulo.setText(c.id);
        boolean con = c.conectada();
        estado.setText((con ? "● " + c.nodoActual : "○ sin nodo") + "  ·  gen " + c.generacion);
        StringBuilder firma = new StringBuilder();
        for (var g : activos) {
            firma.append(g.d.etiqueta()).append(';');
        }
        if (!firma.toString().equals(firmaGenes)) {
            firmaGenes = firma.toString();
            insignias.removeAll();
            insignias.add(Ventanas.etiqueta("Genes:", Ventanas.negrita(11), null));
            if (activos.isEmpty()) {
                insignias.add(Ventanas.etiqueta("ninguno (célula sin diferenciar)", Ventanas.normal(11), Ventanas.GRIS));
            }
            for (var g : activos) {
                JLabel b = new JLabel(" " + g.d.simbolo + "  " + g.d.etiqueta() + " · CC " + g.d.cc + " ");
                b.setOpaque(true);
                b.setBackground(Ventanas.colorGen(g.d.nombre));
                b.setForeground(Color.WHITE);
                b.setFont(Ventanas.negrita(11));
                insignias.add(b);
            }
            insignias.revalidate();
            insignias.repaint();
        }
        contadores.setText("ACK " + c.acks.get() + " · Ejecutadas " + c.ejecutadas.get() + " · Ignoradas "
                + c.ignoradas.get() + (c.deudasAbiertas() > 0 ? " · Deudas " + c.deudasAbiertas() : ""));
        if (c.reemplaza != null) {
            linaje.setText("<html><b>Clon</b> de " + c.padre + " · reemplaza a <b>" + c.reemplaza + "</b></html>");
            centro.setBackground(Ventanas.FONDO_CLON);
            botones.setBackground(Ventanas.FONDO_CLON);
        } else {
            linaje.setText("Célula original · activa " + Util.duracion(Util.ahora() - c.inicio));
        }
        if (silenciar.isSelected() != c.silenciada) {
            silenciar.setSelected(c.silenciada);
        }
        silenciar.setText(c.silenciada ? "ACK silenciados" : "Silenciar ACK");
    }
}
