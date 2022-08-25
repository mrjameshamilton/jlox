package lox;

public abstract class LoxClass implements LoxCallable {

    private final LoxCallable $enclosing;
    private final LoxClass $superClass;

    public LoxClass(LoxCallable enclosing) {
        this(enclosing, null);
    }

    public LoxClass(LoxCallable enclosing, LoxClass superClass) {
        $enclosing = enclosing;
        $superClass = superClass;
        initialize();
    }

    protected abstract void initialize();

    public abstract LoxMethod findMethod(String name);

    public LoxMethod findSuperMethod(String name) {
        if (getSuperClass() == null) throw new LoxException("Undefined property '" + name + "'.");

        LoxMethod method = getSuperClass().findMethod(name);

        if (method == null) throw new LoxException("Undefined property '" + name + "'.");

        return method;
    }

    public abstract String getName();

    public LoxClass getSuperClass() {
        return $superClass;
    }

    @Override
    public int arity() {
        LoxMethod init = findMethod("init");
        return init != null ? init.arity() : 0;
    }

    @Override
    public LoxCallable getEnclosing() {
        return $enclosing;
    }

    @Override
    public Object invoke(Object[] args) {
        LoxInstance loxInstance = new LoxInstance(this);
        LoxMethod init = findMethod("init");
        if (init != null) {
            init.bind(loxInstance).invoke(args);
        }
        return loxInstance;
    }

    @Override
    public String toString() {
        return getName();
    }
}
