package com.craftinginterpreters.lox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.craftinginterpreters.lox.Lox.runtimeError;
import static com.craftinginterpreters.lox.TokenType.MINUS;

public class Optimizer {

    private final CompilerResolver resolver;


    public Optimizer(CompilerResolver resolver) {
        this.resolver = resolver;
    }

    public Stmt.Function execute(Stmt.Function function, int passes) {
        return new Stmt.Function(
            function.name,
            function.params,
            execute(function.body, passes)
        );
    }

    public List<Stmt> execute(List<Stmt> stmt, int passes) {
        var stmtStream = stmt.stream();
        for (int i = 0; i < passes; i++) {
            var codeSimplifier = new CodeSimplifier();
            stmtStream = stmtStream
                .map(it -> it.accept(codeSimplifier))
                .filter(Objects::nonNull);
        }
        return stmtStream.collect(Collectors.toList());
    }

    private class CodeSimplifier implements Stmt.Visitor<Stmt>, Expr.Visitor<Expr> {
        private final Map<Token, Expr> varExprReplacements = new HashMap<>();

        @Override
        public Expr visitAssignExpr(Expr.Assign expr) {
            var value = expr.value.accept(this);
            var optionalVarDef = resolver.varDef(expr);
            if (optionalVarDef.isEmpty()) {
                runtimeError(new RuntimeError(expr.name, "Undefined variable '" + expr.name.lexeme + "'."));
            } else {
                var varDef = optionalVarDef.get();
                if (!varDef.isRead()) {
                    if (value.accept(new SideEffectCounter()) == 0) {
                        return null;
                    } else {
                        return value;
                    }
                }

                // TODO: copy propagation
            }

            return new Expr.Assign(expr.name, value);
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
                    } else if (left instanceof Expr.Literal a && a.value instanceof Double d1 && d1 == 0) {
                        return expr.right;
                    } else if (right instanceof Expr.Literal b && b.value instanceof Double d2 && d2 == 0) {
                        return expr.left;
                    }
                }
                case MINUS -> {
                    if (left instanceof Expr.Literal a && right instanceof Expr.Literal b &&
                        a.value instanceof Double d1 && b.value instanceof Double d2) {
                        return new Expr.Literal(d1 - d2);
                    } else if (left instanceof Expr.Literal a && a.value instanceof Double d1 && d1 == 0) {
                        return new Expr.Unary(new Token(MINUS, "-", null, expr.operator.line), expr.right);
                    } else if (right instanceof Expr.Literal b && b.value instanceof Double d2 && d2 == 0) {
                        return expr.left;
                    }
                }
                case SLASH -> {
                     if (left instanceof Expr.Literal a && right instanceof Expr.Literal b &&
                         a.value instanceof Double d1 && b.value instanceof Double d2) {
                         return new Expr.Literal(d1 / d2);
                     }
                 }
                case STAR -> {
                     if (left instanceof Expr.Literal a && a.value instanceof Double d1 &&
                         right instanceof Expr.Literal b && b.value instanceof Double d2) {
                         return new Expr.Literal(d1 * d2);
                     }
                     if (left instanceof Expr.Literal a && a.value instanceof Double d1 && d1 == 0 ||
                         right instanceof Expr.Literal b && b.value instanceof Double d2 && d2 == 0) {
                         return new Expr.Literal(0.0);
                     }
                }
                case GREATER -> {
                     if (left instanceof Expr.Literal a && a.value instanceof Double d1 &&
                         right instanceof Expr.Literal b && b.value instanceof Double d2) {
                         return new Expr.Literal(d1 > d2);
                     }
                }
                case GREATER_EQUAL -> {
                     if (left instanceof Expr.Literal a && a.value instanceof Double d1 &&
                             right instanceof Expr.Literal b && b.value instanceof Double d2) {
                         return new Expr.Literal(d1 >= d2);
                     }
                }
                case LESS -> {
                    if (left instanceof Expr.Literal a && a.value instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.value instanceof Double d2) {
                        return new Expr.Literal(d1 < d2);
                    }
                }
                case LESS_EQUAL -> {
                    if (left instanceof Expr.Literal a && a.value instanceof Double d1 &&
                            right instanceof Expr.Literal b && b.value instanceof Double d2) {
                        return new Expr.Literal(d1 <= d2);
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
            if (expr.expression instanceof Expr.Literal literalExpr) {
                return literalExpr;
            } else if (expr.expression instanceof Expr.Grouping groupingExpr) {
                // Recursively unwrap nested groupings
                return visitGroupingExpr(groupingExpr);
            } else {
                return new Expr.Grouping(expr.expression.accept(this));
            }
        }

        @Override
        public Expr visitLiteralExpr(Expr.Literal expr) {
            return expr;
        }

        @Override
        public Expr visitLogicalExpr(Expr.Logical expr) {
            switch (expr.operator.type) {
                case OR -> {
                    if (expr.left instanceof Expr.Literal l1 && l1.value instanceof Boolean b1 &&
                        expr.right instanceof Expr.Literal l2 && l2.value instanceof Boolean b2) {
                        return new Expr.Literal(b1 || b2);
                    }

                    if (expr.left instanceof Expr.Literal l1 && (l1.value == null || (l1.value instanceof Boolean b1 && !b1))) {
                        return expr.right;
                    }
                }
                case AND -> {
                    if (expr.left instanceof Expr.Literal l1 && l1.value instanceof Boolean b1 &&
                        expr.right instanceof Expr.Literal l2 && l2.value instanceof Boolean b2) {
                        return new Expr.Literal(b1 && b2);
                    }

                    if (expr.left instanceof Expr.Literal l1 && (l1.value == null || l1.value instanceof Boolean b1 && !b1)) {
                        return expr.left;
                    }
                }
            }

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
            var varDef = resolver.varDef(expr);

            if (varDef.isEmpty()) {
                runtimeError(new RuntimeError(expr.name, "Undefined variable '" + expr.name.lexeme + "'."));
                return expr;
            } else {
                if (varExprReplacements.containsKey(varDef.get().token())) {
                    var newExpr = varExprReplacements.get(varDef.get().token());

                    if (newExpr instanceof Expr.Literal) {
                        // If a variable access was replaced by a literal,
                        // then the variable is read one less time.
                        resolver.decrementReads(varDef.get());
                    }
                }
                return varExprReplacements.getOrDefault(varDef.get().token(), expr);
            }
        }

        @Override
        public Stmt visitBlockStmt(Stmt.Block stmt) {
            return new Stmt.Block(
                stmt.statements
                    .stream()
                    .map(it -> it.accept(this))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            );
        }

        @Override
        public Stmt visitClassStmt(Stmt.Class stmt) {
            var varDef = resolver.varDef(stmt.name);

            Expr superClass = null;
            if (stmt.superclass != null) {
                superClass = stmt.superclass.accept(this);

                if (!(superClass instanceof Expr.Variable)) {
                    // For compatibility with Lox test suite, throw a runtime error.
                    runtimeError(new RuntimeError(stmt.superclass.name, "Superclass must be a class."));
                    return null;
                }
            }

            if (varDef != null && !varDef.isRead() &&
                // If superClass is not null, it can cause a side effect of a runtime error
                // because we don't know until runtime if the variable contains a class.
                superClass == null) {
                return null;
            }

            return new Stmt.Class(
                stmt.name,
                stmt.superclass == null ? null : (Expr.Variable) superClass,
                stmt.methods
                    .stream()
                    .map(it -> (Stmt.Function)it.accept(this))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            );
        }

        @Override
        public Stmt visitExpressionStmt(Stmt.Expression stmt) {
            var expr = stmt.expression.accept(this);
            return expr == null ? null : new Stmt.Expression(expr);
        }

        @Override
        public Stmt visitFunctionStmt(Stmt.Function stmt) {
            var varDef = resolver.varDef(stmt.name);
            if (varDef != null && !varDef.isRead()) {
                return null;
            }

            if (stmt instanceof Compiler.NativeFunction nativeFunction) {
                return nativeFunction;
            }

            return new Stmt.Function(
                stmt.name,
                stmt.params,
                stmt.body
                    .stream()
                    .map(it -> it.accept(this))
                    .filter(Objects::nonNull)
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
                        return null;
                } else if (l.value instanceof Boolean b) {
                    if (b) return stmt.thenBranch.accept(this);
                    else if (stmt.elseBranch != null) return stmt.elseBranch.accept(this);
                    else return null;
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
            var varDef = resolver.varDef(stmt.name);

            if (varDef != null && !varDef.isRead()) {
                // The variable is never read but if it has an initializer,
                // there could be side effects.
                if (stmt.initializer != null) {
                    if (stmt.initializer.accept(new SideEffectCounter()) == 0) {
                        return null;
                    } else {
                        // potential side effects so keep the initializer
                        return new Stmt.Expression(stmt.initializer);
                    }
                } else {
                    return null;
                }
            }

            if (stmt.initializer != null) {
                var expr = stmt.initializer.accept(this);
                if (expr instanceof Expr.Literal && varDef.isFinal()) {
                    varExprReplacements.put(varDef.token(), expr);
                    return null;
                }
                return new Stmt.Var(stmt.name, expr);
            }

            return new Stmt.Var(stmt.name, null);
        }

        @Override
        public Stmt visitWhileStmt(Stmt.While stmt) {
            return new Stmt.While(
                stmt.condition.accept(this),
                stmt.body.accept(this)
            );
        }
    }
}
