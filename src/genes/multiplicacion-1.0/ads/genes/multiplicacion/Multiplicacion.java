package ads.genes.multiplicacion;

import ads.api.Microservicio;

/** Gen MULTIPLICACION v1.0. */
public final class Multiplicacion implements Microservicio {

    @Override
    public String ejecutar(String... op) {
        if (op.length != 2) {
            throw new IllegalArgumentException("MULTIPLICACION requiere 2 operandos");
        }
        return formato(numero(op[0]) * numero(op[1]));
    }

    private static double numero(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Operando inválido: " + s);
        }
    }

    private static String formato(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
