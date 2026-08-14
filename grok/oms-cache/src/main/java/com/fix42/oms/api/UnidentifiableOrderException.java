package com.fix42.oms.api;

public class UnidentifiableOrderException extends RuntimeException {
    public UnidentifiableOrderException(String message) {
        super(message);
    }
}
