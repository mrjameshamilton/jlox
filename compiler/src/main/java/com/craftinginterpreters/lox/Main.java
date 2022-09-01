package com.craftinginterpreters.lox;

import org.jetbrains.annotations.Nullable;
import proguard.classfile.ClassPool;
import proguard.classfile.util.ClassPoolClassLoader;
import proguard.io.util.IOUtil;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Paths;

import static com.craftinginterpreters.lox.Compiler.LOX_MAIN_CLASS;
import static com.craftinginterpreters.lox.Lox.hadError;
import static com.craftinginterpreters.lox.Lox.hadRuntimeError;

public class Main {
    public static void main(String[] args) throws IOException {
        if (args.length > 2) {
            System.out.println("Usage: jlox [script]");
            System.exit(64); // [64]
        } else if (args.length == 1 || args.length == 2) {
            var classPool = compileFile(args[0]);
            if (hadRuntimeError) System.exit(70);
            if (hadError || classPool == null) System.exit(65);
            if (args.length == 1) runClassPool(classPool, args);
            else IOUtil.writeJar(classPool, args[1], LOX_MAIN_CLASS);
        } else {
            Lox.main(args);
        }
    }


    private static void runClassPool(ClassPool programClassPool, String[] args) throws RuntimeException {
        var clazzLoader = new ClassPoolClassLoader(programClassPool);
        try {
            clazzLoader
                .loadClass(LOX_MAIN_CLASS)
                .getDeclaredMethod("main", String[].class)
                .invoke(null, (Object) args);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static ClassPool compileFile(String path) throws IOException {
        byte[] bytes = Files.readAllBytes(Paths.get(path));
        return compile(new String(bytes));
    }

    public static @Nullable ClassPool compile(String source) {
        var scanner = new Scanner(source);
        var tokens = scanner.scanTokens();

        var parser = new Parser(tokens);

        var statements = parser.parse();

        if (hadError) return null;

        new Checker().execute(statements);

        if (hadError) return null;

         statements = new Optimizer().execute(statements, 5);

        var classPool = new Compiler().compile(statements);

        if (hadError) return null;

        return classPool;
    }
}