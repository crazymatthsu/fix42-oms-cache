package com.fix42.oms.fix;

/** Thrown when a FIX message string cannot be parsed or fails strict validation. */
public class FixParseException extends RuntimeException {

    public FixParseException(String message) {
        super(message);
    }

    public FixParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
