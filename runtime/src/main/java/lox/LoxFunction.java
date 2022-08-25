package lox;

public abstract class LoxFunction implements LoxCallable {

    private final LoxCallable $enclosing;

    public LoxFunction() {
        this(null);
    }

    public LoxFunction(LoxCallable enclosing) {
        $enclosing = enclosing;
    }

    public LoxCallable getEnclosing() {
        return $enclosing;
    }

    public abstract String getName();
    public abstract int arity();
    public abstract Object invoke(Object[] args);

    public String toString() {
        return "<fn " + getName() + ">";
    }
}
