package com.securepay.payment;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically finishes approvals that were interrupted mid-execution. */
@Component
@ConditionalOnProperty(name = "securepay.payments.recovery-enabled", havingValue = "true", matchIfMissing = true)
public class PaymentRecoveryJob {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryJob.class);

    /** Long enough that we never race a request that is still legitimately in progress. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(2);

    private final PaymentDraftService draftService;

    public PaymentRecoveryJob(PaymentDraftService draftService) {
        this.draftService = draftService;
    }

    @Scheduled(fixedDelayString = "${securepay.payments.recovery-interval:PT1M}")
    void run() {
        int recovered = draftService.recoverStuck(STUCK_AFTER);
        if (recovered > 0) {
            log.info("Recovered {} interrupted payment approvals", recovered);
        }
    }
}
