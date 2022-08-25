package com.craftinginterpreters.lox;

import java.util.List;
import java.util.Objects;


import static com.craftinginterpreters.lox.Lox.error;


public class Checker implements Stmt.Visitor<Void>, Expr.Visitor<Void> {
    private ClassType currentClassType = ClassType.NONE;
    private FunctionType currentFunctionType = FunctionType.NONE;

    public void execute(List<Stmt> statements) {
        statements.forEach(it -> it.accept(this));
    }

    private void checkFunction(Stmt.Function function, FunctionType type) {
        var enclosingFunctionType = currentFunctionType;
        currentFunctionType = type;
        function.body.forEach(it -> it.accept(this));
        currentFunctionType = enclosingFunctionType;
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        expr.value.accept(this);
        return null;
    }

    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        expr.left.accept(this);
        expr.right.accept(this);
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        expr.callee.accept(this);
        expr.arguments.forEach(it -> it.accept(this));
        return null;
    }

    @Override
    public Void visitGetExpr(Expr.Get expr) {
        expr.object.accept(this);
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        expr.expression.accept(this);
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        expr.left.accept(this);
        expr.right.accept(this);
        return null;
    }

    @Override
    public Void visitSetExpr(Expr.Set expr) {
        expr.value.accept(this);
        expr.object.accept(this);
        return null;
    }

    @Override
    public Void visitSuperExpr(Expr.Super expr) {
        if (currentClassType == ClassType.NONE) {
            error(expr.keyword, "Can't use 'super' outside of a class.");
        } else if (currentClassType != ClassType.SUBCLASS) {
            error(expr.keyword, "Can't use 'super' in a class with no superclass.");
        }
        return null;
    }

    @Override
    public Void visitThisExpr(Expr.This expr) {
        if (currentClassType == ClassType.NONE) {
            error(expr.keyword, "Can't use 'this' outside of a class.");
        }
        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        expr.right.accept(this);
        return null;
    }

    @Override
    public Void visitVariableExpr(Expr.Variable expr) {
        return null;
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        stmt.statements.forEach(it -> it.accept(this));
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        var enclosingClass = currentClassType;
        currentClassType = ClassType.CLASS;
        if (stmt.superclass != null) {
            currentClassType = ClassType.SUBCLASS;
            if (Objects.equals(stmt.name.lexeme, stmt.superclass.name.lexeme)) {
                error(stmt.superclass.name, "A class can't inherit from itself.");
            }
            stmt.superclass.accept(this);
        }
        stmt.methods.forEach(it -> checkFunction(it, it.name.lexeme.equals("init") ? FunctionType.INIT : FunctionType.METHOD));
        currentClassType = enclosingClass;
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        stmt.expression.accept(this);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        checkFunction(stmt, FunctionType.FUNCTION);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        stmt.condition.accept(this);
        stmt.thenBranch.accept(this);
        if (stmt.elseBranch != null) stmt.elseBranch.accept(this);
        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        stmt.expression.accept(this);
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return returnStmt) {
        if (returnStmt.value != null) {
            if (currentFunctionType == FunctionType.NONE) {
                error(returnStmt.keyword, "Can't return from top-level code.");
            } else if (currentFunctionType == FunctionType.INIT) {
                error(returnStmt.keyword, "Can't return a value from an initializer.");
            } else returnStmt.value.accept(this);
        }
        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        if (stmt.initializer != null) stmt.initializer.accept(this);
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        stmt.condition.accept(this);
        stmt.body.accept(this);
        return null;
    }

    private enum ClassType {
        NONE,
        CLASS,
        SUBCLASS
    }

    private enum FunctionType {
        NONE,
        FUNCTION,
        METHOD,
        INIT
    }
}
