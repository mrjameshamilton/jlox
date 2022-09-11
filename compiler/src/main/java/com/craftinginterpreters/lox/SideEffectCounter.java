package com.craftinginterpreters.lox;

/**
 * Counts how many instructions that have side effects.
 * <p>
 * Side effects include function calls, assign and set expressions.
 */
public class SideEffectCounter extends FunctionCallCounter {

    @Override
    public Integer visitSetExpr(Expr.Set expr) {
        return super.visitSetExpr(expr) + 1;
    }

    @Override
    public Integer visitAssignExpr(Expr.Assign expr) {
        return super.visitAssignExpr(expr) + 1;
    }
}
