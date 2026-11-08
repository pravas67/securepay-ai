package com.securepay.payment;

import com.securepay.common.NotFoundException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PaymentService {

    public static final int MAX_HISTORY_DAYS = 90;

    private final PaymentRepository payments;

    public PaymentService(PaymentRepository payments) {
        this.payments = payments;
    }

    /** Payments for this user in the last {@code days} days. Out-of-range values are clamped, not trusted. */
    public List<PaymentView> history(Long userId, int days) {
        int safeDays = Math.clamp(days, 1, MAX_HISTORY_DAYS);
        return payments.findTop100ByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(userId, OffsetDateTime.now().minusDays(safeDays))
                .stream()
                .map(PaymentView::from)
                .toList();
    }

    public PaymentView status(Long userId, String reference) {
        return payments.findByReferenceAndUserId(reference, userId)
                .map(PaymentView::from)
                .orElseThrow(() -> new NotFoundException("Payment " + reference + " not found"));
    }
}
