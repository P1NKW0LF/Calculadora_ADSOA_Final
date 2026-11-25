package ads.genes.division;

import ads.api.Microservicio;

/** Gen DIVISION v1.0. */
public final class Division implements Microservicio {

    @Override
    public String ejecutar(String... op) {
        if (op.length != 2) {
            throw new IllegalArgumentException("DIVISION requiere 2 operandos");
        }
        double b = numero(op[1]);
        if (b == 0) {
            throw new ArithmeticException("División entre cero");
        }
        return formato(numero(op[0]) / b);
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
