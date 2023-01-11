package lox;

import java.io.IOException;

public class LoxNative {
    public static Object clock() {
        return (double)System.currentTimeMillis() / 1000.0;
    }

    public static Object read() throws IOException {
        int b = System.in.read();
        return b != -1 ? (double)b : null;
    }

    public static Object b2c(Object o) {
        return o == null ? null : String.valueOf((char) ((double) o));
    }
}
