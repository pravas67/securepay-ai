package com.securepay.audit;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final int MAX_DETAILS_LENGTH = 1000;

    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes in its own transaction so the record survives even if the caller's transaction rolls back
     * (a failed action must still leave a trace).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long userId, String action, String details, AuditOutcome outcome) {
        repository.save(new AuditEntry(userId, action, truncate(details), outcome));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> recentFor(Long userId) {
        return repository.findTop50ByUserIdOrderByCreatedAtDesc(userId);
    }

    private static String truncate(String details) {
        if (details == null || details.length() <= MAX_DETAILS_LENGTH) {
            return details;
        }
        return details.substring(0, MAX_DETAILS_LENGTH) + "…";
    }
}
