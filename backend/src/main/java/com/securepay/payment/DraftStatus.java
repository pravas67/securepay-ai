package com.securepay.payment;

public enum DraftStatus {
    /** Waiting for the user to approve or reject. */
    PENDING_APPROVAL,
    /** Approved and claimed; the provider call is in progress. */
    EXECUTING,
    EXECUTED,
    REJECTED,
    EXPIRED,
    FAILED
}
