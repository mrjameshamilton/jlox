package com.craftinginterpreters.lox;

/**
 * Computes the expected stack size after executing instructions.
 */
public class StackSizeComputer implements Stmt.Visitor<Integer>, Expr.Visitor<Integer> {
    @Override
    public Integer visitAssignExpr(Expr.Assign expr) {
        return compute(
            /* before = */ expr.value.accept(this),
            /* consumes = */ 1,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitBinaryExpr(Expr.Binary expr) {
        return compute(
            /* before = */ expr.left.accept(this) + expr.right.accept(this),
            /* consumes = */ 2,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitCallExpr(Expr.Call expr) {
        return compute(
            /* before = */ expr.arguments.stream().map(it -> it.accept(this)).mapToInt(Integer::intValue).sum() + expr.callee.accept(this),
            /* consumes = */ expr.arguments.size() + 1,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitGetExpr(Expr.Get expr) {
        return compute(
            /* before = */ expr.object.accept(this),
            /* consumes = */ 1,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitGroupingExpr(Expr.Grouping expr) {
        return compute(
            /* before = */ expr.expression.accept(this),
            /* consumes = */ 0,
            /* produces = */ 0
        );
    }

    @Override
    public Integer visitLiteralExpr(Expr.Literal expr) {
        return compute(
            /* before = */ 0,
            /* consumes = */ 0,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitLogicalExpr(Expr.Logical expr) {
        return compute(
            /* before = */ expr.left.accept(this) + expr.right.accept(this),
            /* consumes = */ 2,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitSetExpr(Expr.Set expr) {
        return compute(
            /* before = */ expr.value.accept(this),
            /* consumes = */ expr.object.accept(this),
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitSuperExpr(Expr.Super expr) {
        return compute(
            /* before = */ 0,
            /* consumes = */ 0,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitThisExpr(Expr.This expr) {
        return compute(
            /* before = */ 0,
            /* consumes = */ 0,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitUnaryExpr(Expr.Unary expr) {
        return compute(
            /* before = */ expr.right.accept(this),
            /* consumes = */ 1,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitVariableExpr(Expr.Variable expr) {
        return compute(
            /* before = */ 0,
            /* consumes = */ 0,
            /* produces = */ 1
        );
    }

    @Override
    public Integer visitBlockStmt(Stmt.Block stmt) {
        return compute(
            /* before = */ stmt.statements.stream().map(it -> it.accept(this)).mapToInt(Integer::intValue).sum(),
            /* consumes = */ 0,
            /* produces = */ 0
        );
    }

    @Override
    public Integer visitClassStmt(Stmt.Class stmt) {
        return 0;
    }

    @Override
    public Integer visitExpressionStmt(Stmt.Expression stmt) {
        return compute(
            /* before = */ stmt.expression.accept(this),
            /* consumes = */ 0,
            /* produces = */ 0
        );
    }

    @Override
    public Integer visitFunctionStmt(Stmt.Function stmt) {
        return 0;
    }

    @Override
    public Integer visitIfStmt(Stmt.If stmt) {
        return compute(
            /* before = */ stmt.condition.accept(this) + stmt.thenBranch.accept(this) + (stmt.elseBranch != null ? stmt.elseBranch.accept(this) : 0),
            /* consumes = */ 1,
            /* produces = */ 0
        );
    }

    @Override
    public Integer visitPrintStmt(Stmt.Print stmt) {
        return compute(
            /* before = */ stmt.expression.accept(this),
            /* consumes = */ 1,
            /* produces = */ 0
        );
    }

    @Override
    public Integer visitReturnStmt(Stmt.Return stmt) {
        return 0;
    }

    @Override
    public Integer visitVarStmt(Stmt.Var stmt) {
        return 0;
    }

    @Override
    public Integer visitWhileStmt(Stmt.While stmt) {
        return 0;
    }

    private int compute(int before, int consumes, int produces) {
        return before - consumes + produces;
    }
}
