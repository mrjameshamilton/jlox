package com.craftinginterpreters.lox;

import java.util.List;
import java.util.stream.Collectors;

public class Optimizer {

    public List<Stmt> execute(List<Stmt> stmt, int passes) {
        var stmtStream = stmt.stream();
        for (int i = 0; i < passes; i++) {
            stmtStream = stmtStream.map(it -> it.accept(new ConstantExprSimplifier()));
        }
        return stmtStream.collect(Collectors.toList());
    }
}
