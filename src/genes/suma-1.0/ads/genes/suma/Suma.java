package ads.genes.suma;

import ads.api.Microservicio;

/** Gen SUMA v1.0: suma con aritmética double (0.1 + 0.2 = 0.30000000000000004). */
public final class Suma implements Microservicio {

    @Override
    public String ejecutar(String... op) {
        if (op.length != 2) {
            throw new IllegalArgumentException("SUMA requiere 2 operandos");
        }
        return formato(numero(op[0]) + numero(op[1]));
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
