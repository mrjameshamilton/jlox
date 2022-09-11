package com.craftinginterpreters.lox;

import com.craftinginterpreters.lox.CompilerResolver.VarDef;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.WeakHashMap;

public class VariableAllocator implements Stmt.Visitor<Void>, Expr.Visitor<Void> {

    private static final boolean DEBUG = System.getProperty("lox.variableallocator.debug") != null;

    private final CompilerResolver resolver;
    private final Stack<Stmt.Function> functionStack = new Stack<>();
    private final Stack<Map<VarDef, Boolean>> scopes = new Stack<>();
    private final Map<Stmt.Function, Map<VarDef, Slot>> slots = new HashMap<>();

    public VariableAllocator(CompilerResolver resolver) {
        this.resolver = resolver;
    }

    /**
     * Returns the slot number for the specified variable in the specified
     * function.
     */
    public int slot(Stmt.Function function, VarDef varDef) {
        var slot = slots(function)
                .entrySet()
                .stream()
                .filter(it -> it.getKey().equals(varDef))
                .map(Map.Entry::getValue)
                .findFirst();

        return slot.orElseThrow().number;
    }

    public void resolve(Stmt.Function function) {
        resolveFunction(function);
    }

    private void resolve(List<Stmt> stmts) {
        stmts.forEach(this::resolve);
    }

    private void resolve(Stmt stmt) {
        stmt.accept(this);
    }

    private void resolve(Expr expr) {
        expr.accept(this);
    }

    private void resolveFunction(Stmt.Function function) {
        beginScope(function);
        for (Token param : function.params) declare(param);
        // Assign slots for variables captured by this function.
        resolver.captured(function)
                .stream()
                .filter(it -> !it.isGlobal())
                .forEach(
                    varDef -> slots(function).put(varDef, new Slot(function, nextSlotNumber(function), true))
                );
        resolve(function.body);
        endScope(function);
    }

    private void beginScope(Stmt.Function function) {
        functionStack.push(function);
        beginScope();
    }

    private void beginScope() {
        scopes.push(new HashMap<>());
    }

    private void endScope() {
        // At the end of each scope, the variable slot can be re-used.
        scopes.peek().keySet().forEach(it -> free(functionStack.peek(), it));
        scopes.pop();
    }

    private void free(Stmt.Function function, VarDef it) {
        var slot = slots(function).get(it);
        if (slot != null) slot.isUsed = false;
        if (DEBUG) System.out.println("freeing " + it.token().lexeme + " from slot " + slot + " in function " + function.name.lexeme);
    }

    private void endScope(Stmt.Function ignoredFunction) {
        endScope();
        functionStack.pop();
    }

    private void declare(Token name) {
        if (scopes.isEmpty()) return;

        var varDef = resolver.varDef(name);
        var currentFunction = functionStack.peek();
        boolean isAlreadyDeclared = slots(currentFunction).containsKey(varDef);
        boolean isGlobalScope = scopes.size() == 1;

        if (isAlreadyDeclared && isGlobalScope) {
             // Global scope allows redefinitions so no need to assign a new slot.
            return;
        }

        scopes.peek().put(varDef, false);
        int slot = nextSlotNumber(currentFunction);
        slots(currentFunction).put(varDef, new Slot(currentFunction, slot, true));

        if (DEBUG) System.out.println("assigning " + varDef + " to slot " + slot + " in " + currentFunction.name.lexeme);
    }

    @Override
    public Void visitAssignExpr(Expr.Assign expr) {
        resolve(expr.value);
        return null;
    }

    @Override
    public Void visitBinaryExpr(Expr.Binary expr) {
        resolve(expr.left);
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitCallExpr(Expr.Call expr) {
        resolve(expr.callee);
        expr.arguments.forEach(this::resolve);
        return null;
    }

    @Override
    public Void visitGetExpr(Expr.Get expr) {
        resolve(expr.object);
        return null;
    }

    @Override
    public Void visitGroupingExpr(Expr.Grouping expr) {
        resolve(expr.expression);
        return null;
    }

    @Override
    public Void visitLiteralExpr(Expr.Literal expr) {
        return null;
    }

    @Override
    public Void visitLogicalExpr(Expr.Logical expr) {
        resolve(expr.left);
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitSetExpr(Expr.Set expr) {
        resolve(expr.object);
        resolve(expr.value);
        return null;
    }

    @Override
    public Void visitSuperExpr(Expr.Super expr) {

        return null;
    }

    @Override
    public Void visitThisExpr(Expr.This expr) {

        return null;
    }

    @Override
    public Void visitUnaryExpr(Expr.Unary expr) {
        resolve(expr.right);
        return null;
    }

    @Override
    public Void visitVariableExpr(Expr.Variable expr) {

        return null;
    }

    @Override
    public Void visitBlockStmt(Stmt.Block stmt) {
        beginScope();
        resolve(stmt.statements);
        endScope();
        return null;
    }

    @Override
    public Void visitClassStmt(Stmt.Class stmt) {
        declare(stmt.name);
        if (stmt.superclass != null) resolve(stmt.superclass);
        beginScope();
        stmt.methods.forEach(this::resolveFunction);
        endScope();
        return null;
    }

    @Override
    public Void visitExpressionStmt(Stmt.Expression stmt) {
        resolve(stmt.expression);
        return null;
    }

    @Override
    public Void visitFunctionStmt(Stmt.Function stmt) {
        declare(stmt.name);
        resolveFunction(stmt);
        return null;
    }

    @Override
    public Void visitIfStmt(Stmt.If stmt) {
        resolve(stmt.condition);
        resolve(stmt.thenBranch);
        if (stmt.elseBranch != null) resolve(stmt.elseBranch);
        return null;
    }

    @Override
    public Void visitPrintStmt(Stmt.Print stmt) {
        resolve(stmt.expression);
        return null;
    }

    @Override
    public Void visitReturnStmt(Stmt.Return stmt) {
        if (stmt.value != null) resolve(stmt.value);
        return null;
    }

    @Override
    public Void visitVarStmt(Stmt.Var stmt) {
        declare(stmt.name);
        if (stmt.initializer != null) resolve(stmt.initializer);
        return null;
    }

    @Override
    public Void visitWhileStmt(Stmt.While stmt) {
        resolve(stmt.condition);
        resolve(stmt.body);
        return null;
    }

    private Map<VarDef, Slot> slots(Stmt.Function function) {
        return slots.computeIfAbsent(function, k -> new WeakHashMap<>());
    }

    private int nextSlotNumber(Stmt.Function function) {
        Map<VarDef, Slot> slots = slots(function);
        if (slots != null) {
            var firstFreeSlot = slots
                .entrySet()
                .stream()
                .filter(entry -> !entry.getValue().isUsed)
                .min(Comparator.comparingInt(it -> it.getValue().number));
            if (firstFreeSlot.isPresent()) {
                firstFreeSlot.get().getValue().isUsed = true;
                return firstFreeSlot.get().getValue().number;
            } else {
                Optional<Slot> maxSlot = slots.values()
                                              .stream()
                                              .max(Comparator.comparingInt(it -> it.number));
                return maxSlot.map(slot -> slot.number).orElse(0) + 1;
            }
        }
        return 0;
    }

    private static class Slot {

        public final int number;
        private final Stmt.Function function;
        private boolean isUsed;

        public Slot(Stmt.Function function, int number, boolean isUsed) {
            this.function = function;
            this.number = number;
            this.isUsed = isUsed;
        }

        public String toString() {
            return function.name.lexeme + "@" + number + (isUsed ? " (used)" : " (unused)");
        }
    }
}
