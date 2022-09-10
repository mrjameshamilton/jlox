package com.craftinginterpreters.lox;

import lox.LoxNative;

import static proguard.classfile.util.ClassUtil.internalClassName;

public interface LoxConstants {

    String LOX_CALLABLE = internalClassName(lox.LoxCallable.class.getName());
    String LOX_FUNCTION = internalClassName(lox.LoxFunction.class.getName());
    String LOX_METHOD = internalClassName(lox.LoxMethod.class.getName());
    String LOX_CLASS = internalClassName(lox.LoxClass.class.getName());
    String LOX_INSTANCE = internalClassName(lox.LoxInstance.class.getName());
    String LOX_INVOKER = internalClassName(lox.LoxInvoker.class.getName());
    String LOX_NATIVE = internalClassName(LoxNative.class.getName());
    String LOX_EXCEPTION = internalClassName(lox.LoxException.class.getName());
    String LOX_CAPTURED = internalClassName(lox.LoxCaptured.class.getName());
    String LOX_MAIN_CLASS = "Main";
}
