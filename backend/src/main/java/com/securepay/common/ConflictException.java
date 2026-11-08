package com.securepay.common;

/** The resource exists but is in a state that doesn't allow the request (already rejected, expired, ...). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
