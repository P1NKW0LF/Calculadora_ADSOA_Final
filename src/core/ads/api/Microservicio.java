package ads.api;

/**
 * Contrato de un microservicio (gen).
 *
 * El programa base de la célula servidor NO contiene la lógica de ninguna operación: sólo conoce
 * esta interfaz. Cada gen vive en su propio archivo .jar dentro del repositorio de genes (disco),
 * acompañado de un descriptor gen.properties:
 *
 * <pre>
 * nombre=SUMA                 nombre lógico del servicio
 * version=1.0                 versión del gen
 * cc=101                      Código de Contenido que atiende (100..199)
 * simbolo=+                   símbolo para la calculadora
 * clase=ads.genes.suma.Suma   clase que implementa Microservicio
 * aridad=2                    número de operandos
 * descripcion=...             texto libre
 * prueba.entrada=2,3          autoprueba que la célula ejecuta antes de activar el gen
 * prueba.esperado=5
 * </pre>
 *
 * La célula localiza el .jar, valida su integridad (SHA-256) y su descriptor, lo carga en memoria
 * con un ClassLoader propio, ejecuta la autoprueba y sólo entonces lo activa. Al darlo de baja
 * llama a {@link #desactivar()}, cierra el ClassLoader y libera la referencia.
 */
public interface Microservicio {

    /** Ejecuta la operación. Los operandos y el resultado viajan como texto decimal. */
    String ejecutar(String... operandos) throws Exception;

    /** Se invoca justo después de cargar el gen (antes de atender solicitudes). */
    default void activar() {
    }

    /** Se invoca antes de descargar el gen de memoria. */
    default void desactivar() {
    }
}
