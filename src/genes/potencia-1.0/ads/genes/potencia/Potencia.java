package ads.genes.potencia;

import ads.api.Microservicio;

/** Gen POTENCIA v1.0: a^b. */
public final class Potencia implements Microservicio {

    @Override
    public String ejecutar(String... op) {
        if (op.length != 2) {
            throw new IllegalArgumentException("POTENCIA requiere 2 operandos");
        }
        double r = Math.pow(numero(op[0]), numero(op[1]));
        if (Double.isNaN(r) || Double.isInfinite(r)) {
            throw new ArithmeticException("Resultado fuera de rango");
        }
        if (r == Math.rint(r) && Math.abs(r) < 1e15) {
            return String.valueOf((long) r);
        }
        return String.valueOf(r);
    }

    private static double numero(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Operando inválido: " + s);
        }
    }
}
