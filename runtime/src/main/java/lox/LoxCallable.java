package lox;

public interface LoxCallable {
    String getName();
    int arity();
    LoxCallable getEnclosing();

    default LoxCallable getEnclosing(int depth) {
        LoxCallable enclosing = this;
        for (int i = 0; i < depth; i++) enclosing = enclosing.getEnclosing();
        return enclosing;
    }

    Object invoke(Object[] args);
}
