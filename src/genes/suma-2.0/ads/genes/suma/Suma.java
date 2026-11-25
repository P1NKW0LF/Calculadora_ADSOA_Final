package ads.genes.suma;

import ads.api.Microservicio;

import java.math.BigDecimal;

/** Gen SUMA v2.0: suma decimal exacta con BigDecimal (0.1 + 0.2 = 0.3). */
public final class Suma implements Microservicio {

    @Override
    public String ejecutar(String... op) {
        if (op.length != 2) {
            throw new IllegalArgumentException("SUMA requiere 2 operandos");
        }
        BigDecimal r = numero(op[0]).add(numero(op[1]));
        String t = r.stripTrailingZeros().toPlainString();
        return "-0".equals(t) ? "0" : t;
    }

    private static BigDecimal numero(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Operando inválido: " + s);
        }
    }
}
