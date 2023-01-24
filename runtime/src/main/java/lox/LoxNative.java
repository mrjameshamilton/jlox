package lox;

import java.io.IOException;

import static java.nio.charset.StandardCharsets.UTF_8;

public class LoxNative {
    public static Object clock() {
        return (double)System.currentTimeMillis() / 1000.0;
    }

    public static Object read() throws IOException {
        int b = System.in.read();
        return b != -1 ? (double)b : null;
    }

    /**
     * Converts the given bytes into a UTF character String.
     * The first byte must be non-null.
     * Called with a single byte is equivalent to ASCII encoding.
     *
     * @param o1 Double  First character byte
     * @param o2 Double? Second character byte
     * @param o3 Double? Third character byte
     * @param o4 Double? Fourth character byte
     * @return A UTF character as a String
     */
    public static Object utf(Object o1, Object o2, Object o3, Object o4) {
        byte[] bytes = new byte[] {
            o1 == null ? 0 : ((Double)o1).byteValue(),
            o2 == null ? 0 : ((Double)o2).byteValue(),
            o3 == null ? 0 : ((Double)o3).byteValue(),
            o4 == null ? 0 : ((Double)o4).byteValue()
        };
        int count = 0;
        if (o1 != null) count++;
        if (o2 != null) count++;
        if (o3 != null) count++;
        if (o4 != null) count++;
        return new String(bytes, 0, count, UTF_8);
    }

    public static Object exit(Object o) {
        System.exit(((Double)o).intValue());
        return null;
    }

    public static Object printerr(Object o) {
        System.err.println(o);
        return null;
    }
}
