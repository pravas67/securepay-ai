package com.securepay.common;

/** Thrown when a resource doesn't exist *or* belongs to another user — callers can't tell which. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
