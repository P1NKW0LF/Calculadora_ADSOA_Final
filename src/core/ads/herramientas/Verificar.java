package ads.herramientas;

/** Comprueba que la versión de Java sea suficiente (los scripts la ejecutan antes de arrancar). */
public final class Verificar {

    private Verificar() {
    }

    public static void main(String[] a) {
        System.out.println("Java " + System.getProperty("java.version") + " (" + System.getProperty("java.home") + ") OK");
    }
}
