package com.securepay.payment.provider;

/** The provider couldn't be reached or returned a server error: the outcome is unknown, retry with the same key. */
public class ProviderUnavailableException extends RuntimeException {

    public ProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
