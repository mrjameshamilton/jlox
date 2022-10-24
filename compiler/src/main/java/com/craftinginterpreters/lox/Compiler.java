package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.CompilerResolver.VarDef;
import lox.LoxNative;
import org.jetbrains.annotations.Nullable;
import proguard.classfile.ClassPool;
import proguard.classfile.ProgramClass;
import proguard.classfile.ProgramMethod;
import proguard.classfile.attribute.Attribute;
import proguard.classfile.attribute.BootstrapMethodInfo;
import proguard.classfile.attribute.visitor.AllAttributeVisitor;
import proguard.classfile.attribute.visitor.AttributeNameFilter;
import proguard.classfile.attribute.visitor.MultiAttributeVisitor;
import proguard.classfile.editor.BootstrapMethodsAttributeAdder;
import proguard.classfile.editor.ClassBuilder;
import proguard.classfile.editor.CompactCodeAttributeComposer;
import proguard.classfile.editor.CompactCodeAttributeComposer.Label;
import proguard.classfile.editor.ConstantPoolEditor;
import proguard.classfile.editor.LineNumberTableAttributeTrimmer;
import proguard.classfile.io.ProgramClassReader;
import proguard.classfile.visitor.AllMethodVisitor;
import proguard.classfile.visitor.ClassPrinter;
import proguard.classfile.visitor.ClassVersionFilter;
import proguard.preverify.CodePreverifier;

import java.io.DataInputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.craftinginterpreters.lox.Lox.hadError;
import static com.craftinginterpreters.lox.Lox.hadRuntimeError;
import static com.craftinginterpreters.lox.LoxConstants.LOX_CALLABLE;
import static com.craftinginterpreters.lox.LoxConstants.LOX_CAPTURED;
import static com.craftinginterpreters.lox.LoxConstants.LOX_CLASS;
import static com.craftinginterpreters.lox.LoxConstants.LOX_FUNCTION;
import static com.craftinginterpreters.lox.LoxConstants.LOX_INSTANCE;
import static com.craftinginterpreters.lox.LoxConstants.LOX_INVOKER;
import static com.craftinginterpreters.lox.LoxConstants.LOX_MAIN_CLASS;
import static com.craftinginterpreters.lox.LoxConstants.LOX_METHOD;
import static com.craftinginterpreters.lox.LoxConstants.LOX_NATIVE;
import static com.craftinginterpreters.lox.TokenType.FUN;
import static com.craftinginterpreters.lox.TokenType.IDENTIFIER;
import static java.util.Collections.emptyList;
import static proguard.classfile.AccessConstants.FINAL;
import static proguard.classfile.AccessConstants.PRIVATE;
import static proguard.classfile.AccessConstants.PUBLIC;
import static proguard.classfile.AccessConstants.STATIC;
import static proguard.classfile.AccessConstants.VARARGS;
import static proguard.classfile.VersionConstants.CLASS_VERSION_1_8;
import static proguard.classfile.constant.MethodHandleConstant.REF_INVOKE_STATIC;
import static proguard.classfile.util.ClassUtil.internalClassName;

public class Compiler {

    private static final boolean DEBUG = System.getProperty("jlox.compiler.debug") != null;
    private final ClassPool programClassPool = new ClassPool();
    private final CompilerResolver resolver = new CompilerResolver();
    private final VariableAllocator allocator = new VariableAllocator(resolver);


    public @Nullable ClassPool compile(List<Stmt> program) {

        addClass(
            programClassPool,
            lox.LoxCallable.class,
            lox.LoxCaptured.class,
            lox.LoxClass.class,
            lox.LoxException.class,
            lox.LoxFunction.class,
            lox.LoxInstance.class,
            lox.LoxInvoker.class,
            lox.LoxMethod.class
        );

        var mainFunction = new Stmt.Function(
            new Token(FUN, LOX_MAIN_CLASS, null, 0),
            emptyList(),
            prependNative(program)
        );

        resolver.resolve(mainFunction);

        if (hadError || hadRuntimeError) return null;

        mainFunction = new Optimizer(resolver).execute(mainFunction, 3);

        if (hadError || hadRuntimeError) return null;

        allocator.resolve(mainFunction);

        var mainMethodClass = new FunctionCompiler().compile(mainFunction);

        new ClassBuilder(mainMethodClass)
            .addMethod(PUBLIC | STATIC, "main", "([Ljava/lang/String;)V", 65_535, composer -> {
                LoxComposer loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
                var error = loxComposer.createLabel();
                //noinspection unchecked
                loxComposer
                    .try_(it -> it
                        .new_(it.getTargetClass().getName())
                        .dup()
                        .aconst_null()
                        .invokespecial(it.getTargetClass().getName(), "<init>", "(L" + LOX_CALLABLE + ";)V")
                        .aconst_null()
                        .invokeinterface(LOX_CALLABLE, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;")
                        .pop()
                        .return_(),
                     __ -> __
                    .catch_("java/lang/StackOverflowError", it -> it
                        .pop()
                        .getstatic("java/lang/System", "err", "Ljava/io/PrintStream;")
                        .ldc("Stack overflow.")
                        .invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V")
                        .goto_(error))
                    .catchAll(it -> {
                        if (DEBUG) it.dup();
                        it.getstatic("java/lang/System", "err", "Ljava/io/PrintStream;")
                          .swap()
                          .invokevirtual("java/lang/Throwable", "getMessage", "()Ljava/lang/String;")
                          .invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V");
                        if (DEBUG) it.invokevirtual("java/lang/Throwable", "printStackTrace", "()V");
                        return it.goto_(error);
                    }))
                    .label(error)
                    .iconst(70)
                    .invokestatic("java/lang/System", "exit", "(I)V")
                    .return_();
                });

        programClassPool.addClass(mainMethodClass);

        ClassPool classPool = preverify(programClassPool);
        if (DEBUG) classPool.classesAccept(new ClassPrinter());
        return classPool;
    }

    private ClassPool preverify(ClassPool programClassPool) {
        programClassPool.classesAccept(clazz -> {
            try {
                clazz.accept(
                    new ClassVersionFilter(CLASS_VERSION_1_8,
                    new AllMethodVisitor(
                    new AllAttributeVisitor(
                    new AttributeNameFilter(Attribute.CODE,
                    new MultiAttributeVisitor(
                    new CodePreverifier(false),
                    // TODO: see local_mutual_recursion.loxisEven;
                    //  unreachable code is removed by CodePreverifier and the line numbers are not updated
                    new AllAttributeVisitor(new LineNumberTableAttributeTrimmer())))))));
            } catch (Exception e) {
                clazz.accept(new ClassPrinter());
                throw e;
            }
        });

        return programClassPool;
    }

    private class FunctionCompiler implements Stmt.Visitor<LoxComposer>, Expr.Visitor<LoxComposer> {

        private LoxComposer composer;
        private Stmt.Function currentFunction;
        private Stmt.Class currentClass;

        public ProgramClass compile(Stmt.Function functionStmt) {
            return compile(null, functionStmt);
        }

        private ProgramClass compile(Stmt.Class classStmt, Stmt.Function functionStmt) {
            currentFunction = functionStmt;
            currentClass = classStmt;
            var programClass = createFunctionClass(classStmt, functionStmt);
            var invokeMethod = (ProgramMethod) programClass.findMethod("invoke", null);
            composer = new LoxComposer(new CompactCodeAttributeComposer(programClass), programClassPool, resolver, allocator);
            composer.beginCodeFragment(65_535);

            if (functionStmt instanceof NativeFunction) {
                for (int i = 0; i < functionStmt.params.size(); i++) composer.aload(i);
                composer
                    .invokestatic(LOX_NATIVE, functionStmt.name.lexeme, "(" + "Ljava/lang/Object;".repeat(functionStmt.params.size()) + ")Ljava/lang/Object;")
                    .areturn();
            } else {
                var params = functionStmt
                    .params
                    .stream()
                    .map(resolver::varDef)
                    .filter(VarDef::isRead)
                    .toList();

                if (!params.isEmpty()) composer
                    .aload_1()
                    .unpack(
                        params.size(),
                        (composer, n) -> composer.declare(params.get(n))
                    );

                resolver
                    .captured(functionStmt)
                    .stream()
                    .filter(it -> !it.isGlobal())
                    .filter(VarDef::isRead)
                    .forEach(captured -> composer
                        .aload_0()
                        .getfield(composer.getTargetClass().getName(), captured.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                        .astore(allocator.slot(functionStmt, captured))
                    );

                functionStmt.body.forEach(
                    stmt -> stmt.accept(this)
                );

                if (functionStmt.body.stream().noneMatch(stmt -> stmt instanceof Stmt.Return)) {
                    if (classStmt != null && functionStmt.name.lexeme.equals("init")) {
                        composer
                            .aload_0()
                            .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";")
                            .areturn();
                    } else {
                        composer
                            .aconst_null()
                            .areturn();
                    }
                }
            }
            composer.endCodeFragment();
            try {
                composer.addCodeAttribute(programClass, invokeMethod);
            } catch (Exception e) {
                composer.getCodeAttribute().accept(programClass, invokeMethod, new ClassPrinter());
                throw e;
            }

            return programClass;
        }

        private ProgramClass createFunctionClass(Stmt.Class classStmt, Stmt.Function function) {
            boolean isMain = resolver.javaClassName(function).equals(LOX_MAIN_CLASS);
            boolean isMethod = classStmt != null;
            var classBuilder = new ClassBuilder(
                CLASS_VERSION_1_8,
                PUBLIC,
                resolver.javaClassName(function),
                isMethod ? LOX_METHOD : LOX_FUNCTION
            )
            .addMethod(PUBLIC, "getName", "()Ljava/lang/String;", 10, composer -> composer
                .ldc(function.name.lexeme)
                .areturn())
            .addMethod(PUBLIC, "arity", "()I", 10, composer -> composer
                .pushInt(function.params.size())
                .ireturn())
            .addMethod(PUBLIC | VARARGS, "invoke", "([Ljava/lang/Object;)Ljava/lang/Object;");

            var variables = resolver.variables(function);
            var variablesCapturedByFunction = resolver.captured(function);
            var capturedVariablesDeclaredInFunction = variables.stream().filter(VarDef::isCaptured);
            var lateInitVars = variables.stream().filter(VarDef::isLateInit).toList();

            if (isMain) {
                // main can't capture variables, but variables declared in main can be captured.
                capturedVariablesDeclaredInFunction.forEach(global -> classBuilder.
                    addField(PUBLIC | STATIC, global.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                );
            } else {
                Stream.concat(variablesCapturedByFunction.stream(), capturedVariablesDeclaredInFunction)
                    .distinct()
                    .forEach(captured -> classBuilder
                        .addField(PUBLIC, captured.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                    );
            }

            Function<LoxComposer, LoxComposer> captureComposer = composer -> {
                if (!variablesCapturedByFunction.isEmpty()) {
                    composer.aload_0();
                    variablesCapturedByFunction.forEach(varDef -> {
                        if (varDef.isGlobal()) {
                            composer
                                .dup()
                                .getstatic(LOX_MAIN_CLASS, varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                                .putfield(resolver.javaClassName(function), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                        } else {
                            composer
                                .dup()
                                .aload_0()
                                .iconst(varDef.distanceTo(function))
                                .invokeinterface(LOX_CALLABLE, "getEnclosing", "(I)L" + LOX_CALLABLE + ";")
                                .checkcast(resolver.javaClassName(varDef.function()))
                                .getfield(resolver.javaClassName(varDef.function()), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";")
                                .putfield(resolver.javaClassName(function), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";");
                        }
                    });
                    composer.pop();
                }
                return composer;
            };

            if (isMain) {
                if (!lateInitVars.isEmpty()) {
                    classBuilder.addMethod(PRIVATE | STATIC, "<clinit>", "()V", 65535, composer -> {
                        var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
                        lateInitVars.forEach(varDef -> loxComposer
                            .aconst_null()
                            .box(varDef)
                            .putstatic(LOX_MAIN_CLASS, varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";"));
                        composer.return_();
                    });
                }
            }

            classBuilder
                .addMethod(PUBLIC, "<init>", "(L" + (isMethod ? LOX_CLASS : LOX_CALLABLE) + ";)V", 100, composer -> {
                    var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);
                    loxComposer
                        .aload_0()
                        .aload_1()
                        .invokespecial(isMethod ? LOX_METHOD : LOX_FUNCTION, "<init>", "(L" + (isMethod ? LOX_CLASS : LOX_CALLABLE) + ";)V");

                    if (!isMain) {
                        lateInitVars.forEach(varDef -> loxComposer
                            .aload_0()
                            .aconst_null()
                            .box(varDef)
                            .putfield(loxComposer.getTargetClass().getName(), varDef.getJavaFieldName(), "L" + LOX_CAPTURED + ";"));
                    }

                    // Capturing a method's variables can be done here, since we don't need
                    // store the method before capturing as a method itself cannot be captured.
                    if (isMethod) captureComposer.apply(loxComposer);

                    loxComposer.return_();
                });

            if (!isMethod && !variablesCapturedByFunction.isEmpty()) {
                classBuilder.addMethod(PUBLIC, "capture", "()V", 65_535, composer -> captureComposer
                    .apply(new LoxComposer(composer, programClassPool, resolver, allocator))
                    .return_());
            }

            if (function instanceof NativeFunction) {
                // For compatibility with Lox all native functions print `<native fn>`.
                classBuilder.addMethod(
                        PUBLIC, "toString", "()Ljava/lang/String;", 10, composer -> composer
                                .ldc("<native fn>")
                                .areturn()
                );
            }

            var programClass = classBuilder.getProgramClass();

            if (new FunctionCallCounter().count(function) > 0) {
                addBootstrapMethod(programClass);
            }

            programClassPool.addClass(programClass);

            return programClass;
        }

        private ProgramClass createClass(Stmt.Class classStmt) {
            ClassBuilder classBuilder = new ClassBuilder(
                CLASS_VERSION_1_8,
                PUBLIC,
                resolver.javaClassName(classStmt),
                LOX_CLASS
            );

            Function<LoxComposer, LoxComposer> methodInitializer = composer -> {
                for (var method : classStmt.methods) {
                    classBuilder.addField(PRIVATE | FINAL, resolver.javaFieldName(method), "L" + LOX_METHOD + ";");
                    var methodClazz = new FunctionCompiler().compile(classStmt, method);

                    composer
                        .line(method.name.line)
                        .aload_0()
                        .new_(methodClazz)
                        .dup()
                        .aload_0()
                        .invokespecial(methodClazz.getName(), "<init>", "(L" + LOX_CLASS + ";)V")
                        .putfield(composer.getTargetClass().getName(), resolver.javaFieldName(method), "L" + LOX_METHOD + ";");
                }
                return composer;
            };

            classBuilder.addMethod(PUBLIC, "<init>", "(L" + LOX_CALLABLE + ";" + (classStmt.superclass != null ? "L" + LOX_CLASS + ";" : "") + ")V", 65_535, composer -> new LoxComposer(composer, programClassPool, resolver, allocator)
                .aload_0()
                .aload_1()
                .also(__ -> classStmt.superclass != null ? __.aload_2() : __.aconst_null())
                .invokespecial(LOX_CLASS, "<init>", "(L" + LOX_CALLABLE + ";L" + LOX_CLASS + ";)V")
                .also(methodInitializer::apply)
                .return_());

            classBuilder
                    .addMethod(PUBLIC, "findMethod", "(Ljava/lang/String;)L" + LOX_METHOD + ";", 500, composer -> new LoxComposer(composer, programClassPool, resolver, allocator)
                        .aload_1()
                        .switch_(2, switchBuilder -> {
                            classStmt.methods.forEach(method -> switchBuilder.case_(
                                method.name.lexeme,
                                caseComposer -> caseComposer
                                    .aload_0()
                                    .getfield(classBuilder.getProgramClass().getName(), resolver.javaFieldName(method), "L" + LOX_METHOD + ";")
                                    .areturn()
                            ));
                            //noinspection SwitchStatementWithTooFewBranches
                            return switchBuilder.default_(defaultComposer -> switch (classStmt.superclass) {
                                case null -> defaultComposer
                                    .aconst_null()
                                    .areturn();
                                default -> defaultComposer
                                    .aload_0()
                                    .invokevirtual(classBuilder.getProgramClass().getName(), "getSuperClass", "()L" + LOX_CLASS + ";")
                                    .aload_1()
                                    .invokevirtual(LOX_CLASS, "findMethod", "(Ljava/lang/String;)L" + LOX_METHOD + ";")
                                    .areturn();
                            });
                        }))
                    .addMethod(PUBLIC, "getName", "()Ljava/lang/String;", 10, composer -> composer
                            .ldc(classStmt.name.lexeme)
                            .areturn());

            var clazz = classBuilder.getProgramClass();
            programClassPool.addClass(clazz);
            return clazz;
        }

        @Override
        public LoxComposer visitBlockStmt(Stmt.Block blockStmt) {
            blockStmt.statements.forEach(stmt -> stmt.accept(this));
            return composer;
        }

        @Override
        public LoxComposer visitClassStmt(Stmt.Class classStmt) {
            var clazz = createClass(classStmt);

            composer
                .new_(clazz)
                .dup()
                .aload_0();

            if (classStmt.superclass != null) {
                classStmt.superclass.accept(this);
                var isClass = composer.createLabel();
                composer
                    .line(classStmt.superclass.name.line)
                    .dup()
                    .instanceof_(LOX_CLASS)
                    .ifne(isClass)
                    .pop()
                    .loxthrow("Superclass must be a class.")

                    .label(isClass)
                    .checkcast(LOX_CLASS)
                    .invokespecial(clazz.getName(), "<init>", "(L" + LOX_CALLABLE + ";L" + LOX_CLASS + ";)V");
            } else {
                composer.invokespecial(clazz.getName(), "<init>", "(L" + LOX_CALLABLE + ";)V");
            }

            return composer
                .line(classStmt.name.line)
                .declare(resolver.varDef(classStmt.name));
        }

        @Override
        public LoxComposer visitExpressionStmt(Stmt.Expression expressionStmt) {
            expressionStmt.expression.accept(this);
            var expectedStackSize = expressionStmt.expression.accept(new StackSizeComputer());
            for (int i = 0; i < expectedStackSize; i++) composer.pop();
            return composer;
        }

        @Override
        public LoxComposer visitFunctionStmt(Stmt.Function functionStmt) {
            var functionClazz = new FunctionCompiler().compile(functionStmt);

            boolean capturesAnyVariables = resolver.captured(functionStmt).isEmpty();
            composer
                .new_(functionClazz)
                .also(loxComposer -> capturesAnyVariables ? loxComposer.dup() : loxComposer.dup().dup())
                .aload_0()
                .invokespecial(functionClazz.getName(), "<init>", "(L" + LOX_CALLABLE + ";)V");

            composer
                .line(functionStmt.name.line)
                .declare(resolver.varDef(functionStmt.name));

            if (!capturesAnyVariables)
                composer.invokevirtual(functionClazz.getName(), "capture", "()V");

            return composer;
        }

        @Override
        public LoxComposer visitIfStmt(Stmt.If stmt) {
            var endLabel = composer.createLabel();
            var elseBranch = composer.createLabel();
            return stmt.condition.accept(this)
                    .ifnottruthy(elseBranch)
                    .also(composer -> stmt.thenBranch.accept(this))
                    .goto_(endLabel)
                    .label(elseBranch)
                    .also(composer -> stmt.elseBranch != null ? stmt.elseBranch.accept(this) : composer)
                    .label(endLabel);
        }

        @Override
        public LoxComposer visitPrintStmt(Stmt.Print stmt) {
            return composer
                .also(composer -> stmt.expression.accept(this))
                .outline(programClassPool, LOX_MAIN_CLASS, "println", "(Ljava/lang/Object;)V", composer -> {
                    var nonNull = composer.createLabel();
                    var isObject = composer.createLabel();
                    var end = composer.createLabel();
                    // Stringify before printing.
                    composer
                        .dup()
                        // O, O
                        .ifnonnull(nonNull)
                        // O
                        .pop()
                        //
                        .ldc("nil")
                        // "nil"
                        .goto_(end)

                        .label(nonNull)
                        // O
                        .dup()
                        // O, O
                        .instanceof_("java/lang/Double", null)
                        // O, I
                        .ifeq(isObject)
                        // O
                        .invokevirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                        // O.toString
                        .dup()
                        // O.toString, O.toString
                        .ldc(".0")
                        // O.toString, O.toString, ".0"
                        .invokevirtual("java/lang/String", "endsWith", "(Ljava/lang/String;)Z")
                        // O.toString, Z
                        .ifeq(end)
                        // O.toString
                        .dup()
                        // O.toString, O.toString
                        .iconst_0()
                        // O.toString, O.toString, 0
                        .swap()
                        // O.toString, 0, O.toString
                        .invokevirtual("java/lang/String", "length", "()I")
                        // O.toString, 0, O.toString.length
                        .iconst_2()
                        // O.toString, 0, O.toString.length, 2
                        .isub()
                        // O.toString, 0, O.toString.length - 2
                        .invokevirtual("java/lang/String", "substring", "(II)Ljava/lang/String;")
                        // S
                        .goto_(end)

                        .label(isObject)
                        // O
                        .invokevirtual("java/lang/Object", "toString", "()Ljava/lang/String;")
                        // S
                        .label(end)
                        .getstatic("java/lang/System", "out", "Ljava/io/PrintStream;")
                        .swap()
                        .invokevirtual("java/io/PrintStream", "println", "(Ljava/lang/Object;)V");
                    }
                );
        }

        @Override
        public LoxComposer visitReturnStmt(Stmt.Return stmt) {
            if (stmt.value != null)
                return stmt.value.accept(this)
                        .line(stmt.keyword.line)
                        .areturn();
            else if (currentClass != null && currentFunction.name.lexeme.equals("init"))
                return composer
                        .aload_0()
                        .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";")
                        .line(stmt.keyword.line)
                        .areturn();
            else
                return composer
                        .aconst_null()
                        .line(stmt.keyword.line)
                        .areturn();
        }

        @Override
        public LoxComposer visitVarStmt(Stmt.Var stmt) {
            if (stmt.initializer != null) stmt.initializer.accept(this);
            else composer.aconst_null();

            return composer
                    .line(stmt.name.line)
                    .declare(resolver.varDef(stmt.name));
        }

        @Override
        public LoxComposer visitWhileStmt(Stmt.While stmt) {
            var condition = composer.createLabel();
            var body = composer.createLabel();
            var end = composer.createLabel();

            return composer
                .label(condition)
                .also(composer -> stmt.condition.accept(this))
                .ifnottruthy(end)
                .label(body)
                .also(composer -> stmt.body.accept(this))
                .goto_(condition)
                .label(end);
        }

        @Override
        public LoxComposer visitAssignExpr(Expr.Assign expr) {
            composer.line(expr.name.line);
            resolver.varDef(expr).ifPresentOrElse(
                varDef -> expr.value
                    .accept(this)
                    .dup()
                    .line(expr.name.line)
                    .store(currentFunction, varDef.token()),
                () -> composer.loxthrow("Undefined variable '" + expr.name.lexeme + "'.")
            );
            return composer;
        }

        @Override
        public LoxComposer visitBinaryExpr(Expr.Binary expr) {
            switch (expr.operator.type) {
                // These don't require number operands.
                case EQUAL_EQUAL, BANG_EQUAL, PLUS -> {
                    expr.left.accept(this);
                    expr.right.accept(this);
                }
                // These require 2 number operands.
                case MINUS, SLASH, STAR, GREATER, GREATER_EQUAL, LESS, LESS_EQUAL -> {
                     expr.left.accept(this)
                    .line(expr.operator.line)
                    .unbox("java/lang/Double", "Operands must be numbers.");
                     expr.right.accept(this)
                    .unbox("java/lang/Double", "Operands must be numbers.");
                }
            }

            composer.line(expr.operator.line);

            BiFunction<String, Function<LoxComposer, LoxComposer>, LoxComposer> binaryNumberOp = (resultType, op) ->
                     op.apply(composer).box(resultType);

            Function<BiFunction<LoxComposer, Label, LoxComposer>, LoxComposer> comparisonOp = op -> binaryNumberOp.apply("java/lang/Boolean", composer -> {
                var falseBranch = composer.createLabel();
                var end = composer.createLabel();
                return op.apply(composer, falseBranch)
                         .iconst_1()
                         .goto_(end)
                         .label(falseBranch)
                         .iconst_0()
                         .label(end);
            });

            return switch (expr.operator.type) {
                case EQUAL_EQUAL -> composer
                    .invokestatic("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                    .box("java/lang/Boolean");
                case BANG_EQUAL -> composer
                    .invokestatic("java/util/Objects", "equals", "(Ljava/lang/Object;Ljava/lang/Object;)Z")
                    .iconst_1()
                    .ixor()
                    .box("java/lang/Boolean");
                case PLUS -> composer.outline(programClassPool, LOX_MAIN_CLASS, "add", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", outlineComposer -> {
                    var composer = new LoxComposer(outlineComposer, programClassPool, resolver, allocator);
                    var bothDouble = composer.createLabel();
                    var checkAbIsString = composer.createLabel();
                    var checkBaIsString = composer.createLabel();
                    var bothString = composer.createLabel();
                    var throwException = composer.createLabel();
                    var throwExceptionPop = composer.createLabel();
                    var end = composer.createLabel();
                    //noinspection unchecked
                    composer
                            .dup()
                            // A, B, B
                            .instanceof_("java/lang/Double")
                            // A, B, Z
                            .ifeq(checkAbIsString)
                            // A, B
                            .swap()
                            // B, A
                            .dup()
                            // B, A, A
                            .instanceof_("java/lang/Double")
                            // B, A, Z
                            .ifeq(checkBaIsString)
                            // B, A
                            .swap()
                            // A, B
                            .label(bothDouble)
                            .unbox("java/lang/Double")
                            // A, Ba, Bb
                            .dup2_x1()
                            // Ba, Bb, A, Ba, Bb
                            .pop2()
                            // Ba, Bb, A
                            .unbox("java/lang/Double")
                            // Ba, Bb, Aa, Ab
                            .dadd()
                            // Ba, Bb + Aa, Ab
                            .box("java/lang/Double")
                            // A + B
                            .goto_(end)

                            .label(checkBaIsString)
                            .swap()
                            .label(checkAbIsString)
                            // A, B
                            .dup()
                            // A, B, B
                            .instanceof_("java/lang/String")
                            // A, B, Z
                            .ifeq(throwExceptionPop)
                            // A, B
                            .swap()
                            // B, A
                            .instanceof_("java/lang/String")
                            // B, Z
                            .ifeq(throwException)
                            // B
                            .pop()
                            //
                            .label(bothString)
                            .concat(
                                CompactCodeAttributeComposer::aload_0,
                                CompactCodeAttributeComposer::aload_1
                            )
                            .goto_(end)

                            .label(throwExceptionPop)
                            .pop()
                            .label(throwException)
                            .pop()
                            .loxthrow("Operands must be two numbers or two strings.")

                            .label(end);
                    }
                );

                case MINUS -> binaryNumberOp.apply("java/lang/Double", Composer::dsub);
                case SLASH -> binaryNumberOp.apply("java/lang/Double", Composer::ddiv);
                case STAR -> binaryNumberOp.apply("java/lang/Double", Composer::dmul);

                case GREATER -> comparisonOp.apply((composer, label) -> composer
                    .dcmpl()
                    .ifle(label)
                );
                case GREATER_EQUAL -> comparisonOp.apply((composer, label) -> composer
                    .dcmpl()
                    .iflt(label)
                );
                case LESS -> comparisonOp.apply((composer, label) -> composer
                    .dcmpg()
                    .ifge(label)
                );
                case LESS_EQUAL -> comparisonOp.apply((composer, label) -> composer
                    .dcmpg()
                    .ifgt(label)
                );

                default -> throw new IllegalStateException("Unexpected value: " + expr.operator);
            };
        }

        @Override
        public LoxComposer visitCallExpr(Expr.Call expr) {
            return expr.callee.accept(this)
                .also(composer -> {
                    expr.arguments.forEach(it -> it.accept(this));
                    return composer;
                })
                .line(expr.paren.line)
                .invokedynamic(
                        0,
                        "invoke", "(Ljava/lang/Object;" + ("Ljava/lang/Object;".repeat(expr.arguments.size())) + ")Ljava/lang/Object;",
                        null);
        }

        @Override
        public LoxComposer visitGetExpr(Expr.Get expr) {
            return composer
                .ldc(expr.name.lexeme)
                .also(composer -> expr.object.accept(this))
                .line(expr.name.line)
                .outline(programClassPool, LOX_MAIN_CLASS, "get", "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;", composer -> {
                   var notInstance = composer.createLabel();
                   var end = composer.createLabel();
                   var loxComposer = new LoxComposer(composer, programClassPool, resolver, allocator);

                   loxComposer
                       .dup()
                       .instanceof_(LOX_INSTANCE)
                       .ifeq(notInstance)
                       .checkcast(LOX_INSTANCE)
                       .swap()
                       .invokevirtual(LOX_INSTANCE, "get", "(Ljava/lang/String;)Ljava/lang/Object;")
                       .goto_(end)

                       .label(notInstance)
                       .pop()
                       .loxthrow("Only instances have properties.")

                       .label(end);
                });
        }

        @Override
        public LoxComposer visitGroupingExpr(Expr.Grouping expr) {
            return expr.expression.accept(this);
        }

        @Override
        public LoxComposer visitLiteralExpr(Expr.Literal expr) {
            return switch (expr.value) {
                case Boolean b && b == true -> composer.getstatic("java/lang/Boolean", "TRUE", "Ljava/lang/Boolean;");
                case Boolean b && b == false -> composer.getstatic("java/lang/Boolean", "FALSE", "Ljava/lang/Boolean;");
                case String s -> composer.ldc(s);
                case Double d -> composer.pushDouble(d).box("java/lang/Double");
                case null -> composer.aconst_null();
                default -> throw new IllegalArgumentException("Unknown literal type: " + expr.value);
            };
        }

        @Override
        public LoxComposer visitLogicalExpr(Expr.Logical expr) {
            var end = composer.createLabel();
            return switch (expr.operator.type) {
                case OR -> expr.left.accept(this)
                        .dup()
                        .iftruthy(end)
                        .pop()
                        .also(composer -> expr.right.accept(this))
                        .label(end);
                case AND -> expr.left.accept(this)
                        .dup()
                        .ifnottruthy(end)
                        .pop()
                        .also(composer -> expr.right.accept(this))
                        .label(end);
                default -> throw new IllegalArgumentException("Unsupported logical expr type: " + expr.operator.type);
            };
        }

        @Override
        public LoxComposer visitSetExpr(Expr.Set expr) {
            var notInstance = composer.createLabel();
            var end = composer.createLabel();
            return expr.object.accept(this)
                    .dup()
                    .instanceof_(LOX_INSTANCE)
                    .ifeq(notInstance)
                    .checkcast(LOX_INSTANCE)
                    .line(expr.name.line)
                    .ldc(expr.name.lexeme)
                    .also(composer1 -> expr.value.accept(this))
                    .dup_x2()
                    .invokevirtual(LOX_INSTANCE, "set", "(Ljava/lang/String;Ljava/lang/Object;)V")
                    .goto_(end)

                    .label(notInstance)
                    .pop()
                    .loxthrow("Only instances have fields.")

                    .label(end);
        }

        @Override
        public LoxComposer visitSuperExpr(Expr.Super expr) {
            return composer
                .line(expr.method.line)
                // First get the closest method
                .aload_0()
                .iconst(resolver.varDef(expr).orElseThrow().distanceTo(currentFunction))
                .invokeinterface(LOX_CALLABLE, "getEnclosing", "(I)L" + LOX_CALLABLE +";")
                .checkcast(LOX_METHOD)
                .dup() // thismethod, thismethod
                // Then get the class in which the method is defined
                .invokevirtual(LOX_METHOD, "getLoxClass", "()L" + LOX_CLASS + ";")
                .ldc(expr.method.lexeme) // thismethod, class, fieldname
                // Then find the super method
                .invokevirtual(LOX_CLASS, "findSuperMethod", "(Ljava/lang/String;)L" + LOX_METHOD + ";") // thismethod, supermethod
                .swap() // supermethod, thismethod
                .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";") // supermethod, thisinstance
                // Finally, bind the instance to the super method
                .invokevirtual(LOX_METHOD, "bind", "(L" + LOX_INSTANCE + ";)L" + LOX_METHOD + ";"); // []
        }

        @Override
        public LoxComposer visitThisExpr(Expr.This expr) {
            return composer
                    .aload_0()
                    .iconst(resolver.varDef(expr).orElseThrow().distanceTo(currentFunction))
                    .invokeinterface(LOX_CALLABLE, "getEnclosing", "(I)L" + LOX_CALLABLE + ";")
                    .checkcast(LOX_METHOD)
                    .invokevirtual(LOX_METHOD, "getReceiver", "()L" + LOX_INSTANCE + ";");
        }

        @Override
        public LoxComposer visitUnaryExpr(Expr.Unary expr) {
            composer.line(expr.operator.line);
            expr.right.accept(this);
            switch (expr.operator.type) {
                case BANG -> {
                    var isTruthy = composer.createLabel();
                    var end = composer.createLabel();
                    composer
                        .iftruthy(isTruthy)
                        .TRUE()
                        .goto_(end)
                        .label(isTruthy)
                        .FALSE()
                        .label(end);
                }
                case MINUS -> composer
                        .unbox("java/lang/Double", "Operand must be a number.")
                        .dneg()
                        .box("java/lang/Double");

                default -> throw new IllegalArgumentException("Unsupported op: " + expr.operator.type);
            }

            return composer;
        }

        @Override
        public LoxComposer visitVariableExpr(Expr.Variable expr) {
            return composer
                .line(expr.name.line)
                .load(currentFunction, expr);
        }
    }

    private static void addBootstrapMethod(ProgramClass programClass) {
        var constantPoolEditor = new ConstantPoolEditor(programClass);
        var bootstrapMethodsAttributeAdder = new BootstrapMethodsAttributeAdder(programClass);
        var bootstrapMethodInfo = new BootstrapMethodInfo(
            constantPoolEditor.addMethodHandleConstant(
                    REF_INVOKE_STATIC,
                    constantPoolEditor.addMethodrefConstant(
                        LOX_INVOKER,
                        "bootstrap",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                        null,
                        null
                    )
            ),
            0,
            new int[0]
        );

        bootstrapMethodsAttributeAdder.visitBootstrapMethodInfo(programClass, bootstrapMethodInfo);
    }

    private static void addClass(ClassPool classPool, Class<?>...classes) {
        for (var clazz : classes) {
            var is = Compiler.class.getClassLoader().getResourceAsStream(internalClassName(clazz.getName()) + ".class");
            assert is != null;
            var classReader = new ProgramClassReader(new DataInputStream(is));
            var programClass = new ProgramClass();
            programClass.accept(classReader);
            classPool.addClass(programClass);
        }
    }


    public static class NativeFunction extends Stmt.Function {
        NativeFunction(Token name, List<Token> params) {
            super(name, params, emptyList());
        }
    }

    private List<Stmt> prependNative(List<Stmt> stmts) {
        var nativeFunctions = new ArrayList<Stmt>();
        for (Method declaredMethod : LoxNative.class.getDeclaredMethods()) {
            var list = new ArrayList<Token>();
            for (int j = 0, parametersLength = declaredMethod.getParameters().length; j < parametersLength; j++) {
                list.add(new Token(IDENTIFIER, j + "", null, 0));
            }
            nativeFunctions.add(new NativeFunction(
                new Token(IDENTIFIER, declaredMethod.getName(), null, 0),
                list
            ));
        }
        return Stream.concat(nativeFunctions.stream(), stmts.stream()).toList();
    }
}
