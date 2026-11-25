package ads.nodo;

import ads.comun.DirNodo;
import ads.comun.Util;
import ads.comun.Ventanas;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Map;
import java.util.Set;

/** Ventana de monitoreo de un nodo del Campo de Datos. */
final class VentanaNodo extends JFrame {

    private final Nodo nodo;
    private final DefaultListModel<String> modeloEnlaces = new DefaultListModel<>();
    private final DefaultListModel<String> modeloCelulas = new DefaultListModel<>();
    private final JLabel estadisticas = new JLabel(" ");
    private final JLabel subtitulo = new JLabel(" ");
    private final JLabel tituloCelulas = new JLabel("Células conectadas");

    static void mostrar(Nodo n, String pos) {
        SwingUtilities.invokeLater(() -> {
            Ventanas.estilo();
            VentanaNodo v = new VentanaNodo(n);
            Ventanas.colocar(v, pos);
            v.setVisible(true);
            Ventanas.repintarLuego(v);
        });
    }

    private VentanaNodo(Nodo n) {
        super("Nodo " + n.id + " — Campo de Datos (:" + n.puerto + ")");
        this.nodo = n;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                int r = JOptionPane.showConfirmDialog(VentanaNodo.this,
                        "Cerrar la ventana detiene el nodo " + n.id + ".\n"
                                + "Sus células buscarán otro nodo automáticamente. ¿Continuar?",
                        "Detener nodo", JOptionPane.OK_CANCEL_OPTION);
                if (r == JOptionPane.OK_OPTION) {
                    nodo.detenerAbruptamente();
                }
            }
        });

        JPanel cab = new JPanel(new BorderLayout());
        cab.setBackground(Ventanas.FONDO_ENCABEZADO);
        cab.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JLabel t = Ventanas.etiqueta("Nodo " + n.id, Ventanas.negrita(16), Ventanas.TEXTO_CLARO);
        subtitulo.setForeground(Ventanas.TEXTO_CLARO);
        subtitulo.setFont(Ventanas.normal(11));
        cab.add(t, BorderLayout.WEST);
        cab.add(subtitulo, BorderLayout.EAST);

        JList<String> enlaces = new JList<>(modeloEnlaces);
        enlaces.setFont(Ventanas.mono(11));
        JList<String> cels = new JList<>(modeloCelulas);
        cels.setFont(Ventanas.mono(11));
        JPanel listas = new JPanel(new GridLayout(1, 2, 6, 0));
        JPanel p1 = new JPanel(new BorderLayout());
        p1.add(Ventanas.etiqueta("Enlaces con otros nodos", Ventanas.negrita(11), null), BorderLayout.NORTH);
        p1.add(new JScrollPane(enlaces), BorderLayout.CENTER);
        JPanel p2 = new JPanel(new BorderLayout());
        tituloCelulas.setFont(Ventanas.negrita(11));
        p2.add(tituloCelulas, BorderLayout.NORTH);
        p2.add(new JScrollPane(cels), BorderLayout.CENTER);
        listas.add(p1);
        listas.add(p2);
        listas.setPreferredSize(new Dimension(320, 96));

        JTextArea log = Ventanas.areaBitacora(3);
        n.log.alEscribir(l -> Ventanas.agregarLinea(log, l.formato(), 300));

        estadisticas.setFont(Ventanas.normal(10));
        JPanel centro = new JPanel();
        centro.setLayout(new BoxLayout(centro, BoxLayout.Y_AXIS));
        centro.setBorder(BorderFactory.createEmptyBorder(6, 8, 4, 8));
        centro.add(listas);
        JPanel est = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
        est.add(estadisticas);
        centro.add(est);
        JScrollPane sl = new JScrollPane(log);
        sl.setBorder(Ventanas.titulo("Bitácora del nodo"));
        centro.add(sl);

        JButton detener = new JButton("Detener nodo (simular falla)");
        detener.setFont(Ventanas.normal(11));
        detener.addActionListener(e -> nodo.detenerAbruptamente());
        JPanel abajo = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        abajo.add(detener);

        add(cab, BorderLayout.NORTH);
        add(centro, BorderLayout.CENTER);
        add(abajo, BorderLayout.SOUTH);
        setSize(350, 300);
        new Timer(1000, e -> refrescar()).start();
        refrescar();
    }

    private void refrescar() {
        subtitulo.setText(nodo.host + ":" + nodo.puerto + "  ·  activo " + Util.duracion(Util.ahora() - nodo.inicio));
        Set<String> vec = nodo.vecinos();
        modeloEnlaces.clear();
        for (Map.Entry<String, DirNodo> en : nodo.conocidos().entrySet()) {
            boolean ok = vec.contains(en.getKey());
            modeloEnlaces.addElement((ok ? "● " : "○ ") + en.getKey() + (ok ? "  enlazado" : "  sin enlace"));
        }
        modeloCelulas.clear();
        for (String[] c : nodo.celulasConectadas()) {
            modeloCelulas.addElement(c[0] + "  [" + c[1] + "]");
        }
        tituloCelulas.setText("Células conectadas (" + modeloCelulas.size() + ")");
        estadisticas.setText("<html>Recibidos " + nodo.recibidos.get() + " · Entregados " + nodo.entregados.get()
                + " · Reenviados " + nodo.reenviados.get() + "<br>Duplicados descartados (anti-ciclos): <b>"
                + nodo.duplicados.get() + "</b> · TTL agotado: " + nodo.ttlAgotados.get() + "</html>");
    }
}
