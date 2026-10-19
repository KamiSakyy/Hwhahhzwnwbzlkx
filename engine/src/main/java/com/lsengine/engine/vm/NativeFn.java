package com.lsengine.engine.vm;

/**
 * Встроенная функция, реализованная на Java.
 */
public interface NativeFn {
    Value call(Vm vm, Value[] args);
}
