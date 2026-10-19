package com.lsengine.engine.nativecodec;

/**
 * Ошибка нативного кодека контейнера .c.
 */
public class CodecException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
