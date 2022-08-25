package lox;

import java.lang.invoke.*;

import static java.lang.invoke.MethodType.*;

public class LoxInvoker {

    public static CallSite bootstrap(MethodHandles.Lookup lookup, String name, MethodType type) throws NoSuchMethodException, IllegalAccessException {
        if ("invoke".equals(name)) {
            MethodHandle mh = lookup.findStatic(LoxInvoker.class, name, methodType(Object.class, Object.class, Object[].class));
            if (type.parameterCount() == 1) {
                return new ConstantCallSite(mh.asType(mh.type().dropParameterTypes(1, 2)).asType(type));
            } else {
                return new ConstantCallSite(mh.asVarargsCollector(mh.type().parameterType(1)).asType(type));
            }
        } else {
            throw new LoxException("Invalid dynamic method call '" + name + "'.");
        }
    }

    public static Object invoke(Object o, Object...args) {
        if (!(o instanceof LoxCallable)) {
            throw new LoxException("Can only call functions and classes.");
        }

        LoxCallable loxCallable = (LoxCallable) o;

        int arity = loxCallable.arity();

        if (arity != args.length) {
            throw new LoxException("Expected " + arity + " arguments but got " + args.length + ".");
        }

        return loxCallable.invoke(args);
    }
}
