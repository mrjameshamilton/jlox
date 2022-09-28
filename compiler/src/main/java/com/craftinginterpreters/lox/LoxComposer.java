package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.CompilerResolver.VarDef;
import proguard.classfile.ClassPool;
import proguard.classfile.editor.CompactCodeAttributeComposer;

import java.util.Arrays;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.craftinginterpreters.lox.LoxConstants.*;
import static proguard.classfile.util.ClassUtil.internalPrimitiveTypeFromNumericClassName;

public class LoxComposer extends Composer<LoxComposer> {
    private final ClassPool programClassPool;
    private final CompilerResolver resolver;
    private final VariableAllocator allocator;

    public LoxComposer(CompactCodeAttributeComposer delegate, ClassPool programClassPool, CompilerResolver resolver, VariableAllocator allocator) {
        super(delegate);
        this.programClassPool = programClassPool;
        this.resolver = resolver;
        this.allocator = allocator;
    }

    public LoxComposer also(Consumer<LoxComposer> block) {
        block.accept(this);
        return this;
    }

    public LoxComposer iftruthy(Label isTruthy) {
        return isTruthy(true, isTruthy);
    }

    public LoxComposer ifnottruthy(Label isNotTruthy) {
        return isTruthy(false, isNotTruthy);
    }

    private LoxComposer isTruthy(boolean jumpIfTrue, Label jumpTo) {
        outline(programClassPool, LOX_MAIN_CLASS, "isTruthy", "(Ljava/lang/Object;)Z", composer -> {
            var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
            var nonNull = loxComposer.createLabel();
            var isTruthy = loxComposer.createLabel();
            var isNotTruthy = loxComposer.createLabel();
            var isBool = loxComposer.createLabel();
            var end = loxComposer.createLabel();

            loxComposer
                .dup()
                .ifnonnull(nonNull)
                .pop()
                .goto_(isNotTruthy)

                .label(nonNull)
                .dup()
                .instanceof_("java/lang/Boolean")
                .ifne(isBool)
                .pop()
                .goto_(isTruthy)

                .label(isBool)
                .unbox("java/lang/Boolean")
                .ifne(isTruthy)

                .label(isNotTruthy)
                .iconst_0()
                .goto_(end)

                .label(isTruthy)
                .iconst_1()
                .label(end);
        });

        if (jumpIfTrue) ifne(jumpTo); else ifeq(jumpTo);

        return this;
    }

    public LoxComposer loxthrow(String message) {
        new_(LOX_EXCEPTION);
        dup();
        ldc(message);
        invokespecial(LOX_EXCEPTION, "<init>", "(Ljava/lang/String;)V");
        athrow();
        return this;
    }

    public LoxComposer unbox(String expectedType, String exceptionMessage) {
        char returnType = internalPrimitiveTypeFromNumericClassName(expectedType);
        String name = "unbox$" + returnType;
        ldc(exceptionMessage);
        return outline(programClassPool, LOX_MAIN_CLASS, name, "(Ljava/lang/Object;Ljava/lang/String;)" + returnType, composer -> {
            var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
            var notInstance = createLabel();
            var end = createLabel();
            loxComposer
                .swap()
                .dup()
                .instanceof_(expectedType)
                .ifne(end)
                .label(notInstance)
                .pop()

                .new_(LOX_EXCEPTION)
                .dup_x1()
                .swap()
                .invokespecial(LOX_EXCEPTION, "<init>", "(Ljava/lang/String;)V")
                .athrow()

                .label(end)
                .unbox(expectedType);
        });
    }

    public LoxComposer box(VarDef varDef) {
        if (!varDef.isCaptured()) throw new IllegalArgumentException("Cannot box a non-captured variable.");

        return new_(LOX_CAPTURED)
                .dup_x1()
                .swap()
                .invokespecial(LOX_CAPTURED, "<init>", "(Ljava/lang/Object;)V");
    }

    public LoxComposer unbox(VarDef varDef) {
        if (!varDef.isCaptured()) throw new IllegalArgumentException("Cannot unbox a non-captured variable.");

        checkcast(LOX_CAPTURED);
        invokevirtual(LOX_CAPTURED, "getValue", "()Ljava/lang/Object;");
        return this;
    }

    public LoxComposer declare(VarDef varDef) {
        if (!varDef.isRead()) return this;

        if (varDef.isGlobal()) {
            if (varDef.isCaptured()) {
                if (varDef.isLateInit()) {
                    // Late init vars already have an initial value set in the function's constructor
                    getstatic(resolver.javaClassName(varDef.function()), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                    swap();
                    invokevirtual(LOX_CAPTURED, "setValue", "(Ljava/lang/Object;)V");
                } else {
                    box(varDef);
                    putstatic(resolver.javaClassName(varDef.function()), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                }
                // Don't need to store captured globals in a local.
                return this;
            } else {
                // Global but not captured, must be in the main class.
                assert isTargetMainClass();
            }
        } else if (varDef.isLateInit()) {
            // Late init vars already have an initial value set in the function's constructor
            aload_0();
            getfield(resolver.javaClassName(varDef.function()), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
            dup_x1();
            swap();
            invokevirtual(LOX_CAPTURED, "setValue", "(Ljava/lang/Object;)V");
        } else if (varDef.isCaptured()) {
            box(varDef);
            dup();
            aload_0();
            swap();
            putfield(resolver.javaClassName(varDef.function()), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
        }

        astore(allocator.slot(varDef.function(), varDef));

        return this;
    }

    public LoxComposer load(Stmt.Function function, Expr.Variable varAccess) {
        resolver
            .varDef(varAccess)
            .ifPresentOrElse(varDef -> {
                if (varDef.isGlobal()) {
                    if (varDef.isCaptured()) {
                        if (isTargetMainClass()) {
                            getstatic(getTargetClass().getName(), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                        } else {
                            aload_0();
                            getfield(getTargetClass().getName(), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                        }
                        unbox(varDef);
                    } else {
                        assert isTargetMainClass();
                        aload(allocator.slot(function, varDef));
                    }
                } else {
                    aload(allocator.slot(function, varDef));
                    if (varDef.isCaptured()) unbox(varDef);
                }
            },
            () -> loxthrow("Undefined variable '" + varAccess.name.lexeme + "'.")
        );

        return this;
    }

    public LoxComposer store(Stmt.Function function, Token token) {
        var varDef = resolver.varDef(token);
        if (varDef.isGlobal()) {
            if (varDef.isCaptured()) {
                if (isTargetMainClass()) {
                    getstatic(getTargetClass().getName(), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                } else {
                    aload_0();
                    getfield(getTargetClass().getName(), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                }
                swap();
                invokevirtual(LOX_CAPTURED, "setValue", "(Ljava/lang/Object;)V");
            } else {
                // Non-captured globals are stored as local vars.
                assert isTargetMainClass();
                astore(allocator.slot(function, varDef));
            }
        } else if (varDef.isCaptured()) {
            aload(allocator.slot(function, varDef));
            swap();
            invokevirtual(LOX_CAPTURED, "setValue", "(Ljava/lang/Object;)V");
        } else {
            // Local var
            astore(allocator.slot(function, varDef));
        }

        return this;
    }

    public LoxComposer try_(Function<LoxComposer, LoxComposer> tryBlock, Function<CatchBuilder, CatchBuilder>...catchBuilder) {
        var tryStart = createLabel();
        var tryEnd = createLabel();
        label(tryStart);
        also(tryBlock::apply);
        label(tryEnd);
        Arrays.stream(catchBuilder).forEach(it -> it.apply(new CatchBuilder(this, tryStart, tryEnd)));
        return this;
    }

    public static class CatchBuilder {

        private final LoxComposer delegate;
        private final Label tryStart;
        private final Label tryEnd;

        private CatchBuilder(LoxComposer delegate, Label tryStart, Label tryEnd) {
            this.delegate = delegate;
            this.tryStart = tryStart;
            this.tryEnd = tryEnd;
        }


        public CatchBuilder catch_(String type, Function<LoxComposer, LoxComposer> block) {
            delegate.catch_(tryStart, tryEnd, type, null);
            block.apply(delegate);
            return this;
        }

        public CatchBuilder catchAll(Function<LoxComposer, LoxComposer> block) {
            delegate.catchAll(tryStart, tryEnd);
            block.apply(delegate);
            return this;
        }
    }


    private boolean isTargetMainClass() {
        return getTargetClass().getName().equals(LOX_MAIN_CLASS);
    }
}
