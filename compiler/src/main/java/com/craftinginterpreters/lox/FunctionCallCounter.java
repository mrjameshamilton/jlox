package com.craftinginterpreters.lox;

/**
 * Counts how many function calls there are.
 * <p>
 * Used to know if a BootstrapMethod attribute should be added to the class.
 */
public class FunctionCallCounter implements Expr.Visitor<Integer>, Stmt.Visitor<Integer> {

    public int count(Stmt.Function functionStmt) {
        return functionStmt.body
            .stream()
            .mapToInt(it -> it.accept(this))
            .sum();
    }

    @Override
    public Integer visitAssignExpr(Expr.Assign expr) {
        return expr.value.accept(this);
    }

    @Override
    public Integer visitBinaryExpr(Expr.Binary expr) {
        return expr.left.accept(this)
            + expr.right.accept(this);
    }

    @Override
    public Integer visitCallExpr(Expr.Call expr) {
        return expr.arguments
            .stream()
            .mapToInt(it -> it.accept(this))
            .sum()
            + expr.callee.accept(this)
            + 1;
    }

    @Override
    public Integer visitGetExpr(Expr.Get expr) {
        return expr.object.accept(this);
    }

    @Override
    public Integer visitGroupingExpr(Expr.Grouping expr) {
        return expr.expression.accept(this);
    }

    @Override
    public Integer visitLiteralExpr(Expr.Literal expr) {
        return 0;
    }

    @Override
    public Integer visitLogicalExpr(Expr.Logical expr) {
        return expr.left.accept(this)
            + expr.right.accept(this);
    }

    @Override
    public Integer visitSetExpr(Expr.Set expr) {
        return expr.object.accept(this)
            + expr.value.accept(this);
    }

    @Override
    public Integer visitSuperExpr(Expr.Super expr) {
        return 0;
    }

    @Override
    public Integer visitThisExpr(Expr.This expr) {
        return 0;
    }

    @Override
    public Integer visitUnaryExpr(Expr.Unary expr) {
        return expr.right.accept(this);
    }

    @Override
    public Integer visitVariableExpr(Expr.Variable expr) {
        return 0;
    }

    @Override
    public Integer visitBlockStmt(Stmt.Block stmt) {
        return stmt.statements
            .stream()
            .mapToInt(it -> it.accept(this))
            .sum();
    }

    @Override
    public Integer visitClassStmt(Stmt.Class stmt) {
        return 0;
    }

    @Override
    public Integer visitExpressionStmt(Stmt.Expression stmt) {
        return stmt.expression.accept(this);
    }

    @Override
    public Integer visitFunctionStmt(Stmt.Function stmt) {
        return 0;
    }

    @Override
    public Integer visitIfStmt(Stmt.If stmt) {
        return stmt.condition.accept(this)
            + stmt.thenBranch.accept(this)
            + (stmt.elseBranch != null ? stmt.elseBranch.accept(this) : 0);
    }

    @Override
    public Integer visitPrintStmt(Stmt.Print stmt) {
        return stmt.expression.accept(this);
    }

    @Override
    public Integer visitReturnStmt(Stmt.Return stmt) {
        return stmt.value != null ? stmt.value.accept(this) : 0;
    }

    @Override
    public Integer visitVarStmt(Stmt.Var stmt) {
        return stmt.initializer != null ? stmt.initializer.accept(this) : 0;
    }

    @Override
    public Integer visitWhileStmt(Stmt.While stmt) {
        return stmt.condition.accept(this)
            + stmt.body.accept(this);
    }
}
