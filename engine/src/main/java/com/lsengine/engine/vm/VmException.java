package com.lsengine.engine.vm;

/**
 * Ошибка времени выполнения языка LS.
 */
public class VmException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public VmException(String message) {
        super(message);
    }

    public VmException(String message, Throwable cause) {
        super(message, cause);
    }
}
