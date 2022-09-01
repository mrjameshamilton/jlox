package com.craftinginterpreters.lox;

import java.util.Collections;
import java.util.stream.Collectors;

public class ConstantExprSimplifier implements Stmt.Visitor<Stmt>, Expr.Visitor<Expr> {

    @Override
    public Expr visitAssignExpr(Expr.Assign expr) {
        return new Expr.Assign(expr.name, expr.value.accept(this));
    }

    @Override
    public Expr visitBinaryExpr(Expr.Binary expr) {
        var left = expr.left;
        var right = expr.right;

        switch (expr.operator.type) {
            case PLUS -> {
                if (left instanceof Expr.Literal a && right instanceof Expr.Literal b) {
                    if (a.value instanceof String s1 && b.value instanceof String s2)
                        return new Expr.Literal(s1 + s2);
                    else if (a.value instanceof Double d1 && b.value instanceof Double d2)
                        return new Expr.Literal(d1 + d2);
                }
            }
            case MINUS -> {
                if (left instanceof Expr.Literal a && right instanceof Expr.Literal b &&
                    a.value instanceof Double d1 && b.value instanceof Double d2) {
                    return new Expr.Literal(d1 - d2);
                }
            }
           case SLASH -> {
                if (left instanceof Expr.Literal a && right instanceof Expr.Literal b &&
                    a.value instanceof Double d1 && b.value instanceof Double d2) {
                    return new Expr.Literal(d1 / d2);
                }
            }
            case STAR -> {
                if (left instanceof Expr.Literal a && a.value instanceof Double d1 && d1 == 0 ||
                    right instanceof Expr.Literal b && b.value instanceof Double d2 && d2 == 0) {
                    return new Expr.Literal(0.0);
                }
            }
        }

        return new Expr.Binary(
            expr.left.accept(this),
            expr.operator,
            expr.right.accept(this)
        );
    }

    @Override
    public Expr visitCallExpr(Expr.Call expr) {
        return new Expr.Call(
            expr.callee.accept(this),
            expr.paren,
            expr.arguments
                .stream()
                .map(it -> it.accept(this))
                .collect(Collectors.toList())
        );
    }

    @Override
    public Expr visitGetExpr(Expr.Get expr) {
        return new Expr.Get(expr.object.accept(this), expr.name);
    }

    @Override
    public Expr visitGroupingExpr(Expr.Grouping expr) {
        return switch (expr.expression) {
            case Expr.Literal literalExpr -> literalExpr;
            // Recursively unwrap nested groupings
            case Expr.Grouping groupingExpr -> visitGroupingExpr(groupingExpr);
            default -> new Expr.Grouping(expr.expression.accept(this));
        };
    }

    @Override
    public Expr visitLiteralExpr(Expr.Literal expr) {
        return expr;
    }

    @Override
    public Expr visitLogicalExpr(Expr.Logical expr) {
        return new Expr.Logical(
            expr.left.accept(this),
            expr.operator,
            expr.right.accept(this)
        );
    }

    @Override
    public Expr visitSetExpr(Expr.Set expr) {
        return new Expr.Set(
            expr.object.accept(this),
            expr.name,
            expr.value.accept(this)
        );
    }

    @Override
    public Expr visitSuperExpr(Expr.Super expr) {
        return expr;
    }

    @Override
    public Expr visitThisExpr(Expr.This expr) {
        return expr;
    }

    @Override
    public Expr visitUnaryExpr(Expr.Unary expr) {
        return new Expr.Unary(expr.operator, expr.right.accept(this));
    }

    @Override
    public Expr visitVariableExpr(Expr.Variable expr) {
        return expr;
    }

    @Override
    public Stmt visitBlockStmt(Stmt.Block stmt) {
        return new Stmt.Block(
            stmt.statements
                    .stream()
                    .map(it -> it.accept(this))
                    .collect(Collectors.toList())
        );
    }

    @Override
    public Stmt visitClassStmt(Stmt.Class stmt) {
        return new Stmt.Class(
            stmt.name,
            stmt.superclass == null ? null : (Expr.Variable) stmt.superclass.accept(this),
            stmt.methods
                .stream()
                .map(it -> (Stmt.Function)it.accept(this))
                .collect(Collectors.toList())
        );
    }

    @Override
    public Stmt visitExpressionStmt(Stmt.Expression stmt) {
        return new Stmt.Expression(stmt.expression.accept(this));
    }

    @Override
    public Stmt visitFunctionStmt(Stmt.Function stmt) {
        return new Stmt.Function(
            stmt.name,
            stmt.params,
            stmt.body
                .stream()
                .map(it -> it.accept(this))
                .collect(Collectors.toList())
        );
    }

    @Override
    public Stmt visitIfStmt(Stmt.If stmt) {
        if (stmt.condition instanceof Expr.Literal l) {
            if (l.value == null) {
                if (stmt.elseBranch != null)
                    return stmt.elseBranch.accept(this);
                else
                    return new Stmt.Block(Collections.emptyList());
            } else if (l.value instanceof Boolean b) {
                if (b) return stmt.thenBranch.accept(this);
                else if (stmt.elseBranch != null) return stmt.elseBranch.accept(this);
                else return new Stmt.Block(Collections.emptyList());
            } else {
                return stmt.thenBranch.accept(this);
            }
        }
        return new Stmt.If(
            stmt.condition.accept(this),
            stmt.thenBranch.accept(this),
            stmt.elseBranch == null ? null : stmt.elseBranch.accept(this)
        );
    }

    @Override
    public Stmt visitPrintStmt(Stmt.Print stmt) {
        return new Stmt.Print(stmt.expression.accept(this));
    }

    @Override
    public Stmt visitReturnStmt(Stmt.Return stmt) {
        return stmt.value != null ? new Stmt.Return(stmt.keyword, stmt.value.accept(this)) : new Stmt.Return(stmt.keyword, null);
    }

    @Override
    public Stmt visitVarStmt(Stmt.Var stmt) {
        return stmt.initializer != null ? new Stmt.Var(stmt.name, stmt.initializer.accept(this)) : new Stmt.Var(stmt.name, null);
    }

    @Override
    public Stmt visitWhileStmt(Stmt.While stmt) {
        return new Stmt.While(
            stmt.condition.accept(this),
            stmt.body.accept(this)
        );
    }
}
