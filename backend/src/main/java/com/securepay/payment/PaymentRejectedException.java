package com.securepay.payment;

/** The request is well-formed but breaks a payment rule (limit, currency, ...). Safe to show the user. */
public class PaymentRejectedException extends RuntimeException {

    public PaymentRejectedException(String message) {
        super(message);
    }
}
