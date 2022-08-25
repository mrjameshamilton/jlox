package lox;

import java.util.HashMap;
import java.util.Map;

public class LoxInstance {
    private final LoxClass $klass;
    private final Map<String, Object> $fields = new HashMap<>();

    public LoxInstance(LoxClass klass) {
        $klass = klass;
    }

    public LoxClass getKlass() {
        return $klass;
    }

    public Object get(String name) {
        if ($fields.containsKey(name)) {
            return $fields.get(name);
        }

        LoxMethod method = $klass.findMethod(name);

        if (method == null) {
            throw new LoxException("Undefined property '" + name + "'.");
        }

        return method.bind(this);
    }

    public void set(String name, Object value) {
        $fields.put(name, value);
    }

    public String toString() {
        return $klass.getName() + " instance";
    }
}
