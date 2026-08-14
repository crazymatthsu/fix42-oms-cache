package com.fix42.oms.fix;

public class FixParseException extends RuntimeException {
    public FixParseException(String message) {
        super(message);
    }

    public FixParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
