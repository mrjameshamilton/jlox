package lox;

public class LoxNative {
    public static Object clock() {
        return (double)System.currentTimeMillis() / 1000.0;
    }
}
