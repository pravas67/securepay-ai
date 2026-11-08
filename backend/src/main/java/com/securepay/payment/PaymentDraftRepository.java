package com.securepay.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentDraftRepository extends JpaRepository<PaymentDraft, Long> {

    Optional<PaymentDraft> findByReferenceAndUserId(String reference, Long userId);

    long countByUserIdAndStatusAndExpiresAtAfter(Long userId, DraftStatus status, OffsetDateTime now);

    List<PaymentDraft> findTop20ByUserIdAndStatusAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, DraftStatus status, OffsetDateTime now);

    /**
     * Atomically moves a draft from one status to another. Returns 0 if the draft was not in {@code from}
     * (for example, another request already claimed it), so only one caller can ever win.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update PaymentDraft d set d.status = :to, d.decidedAt = :now
            where d.reference = :reference and d.userId = :userId and d.status = :from
            """)
    int transition(@Param("reference") String reference, @Param("userId") Long userId,
                   @Param("from") DraftStatus from, @Param("to") DraftStatus to, @Param("now") OffsetDateTime now);

    /** Drafts claimed for execution before {@code before} that never finished (e.g. the server died mid-way). */
    List<PaymentDraft> findTop20ByStatusAndDecidedAtBefore(DraftStatus status, OffsetDateTime before);

    @Query("""
            select coalesce(sum(d.amount), 0) from PaymentDraft d
            where d.userId = :userId and d.status = com.securepay.payment.DraftStatus.EXECUTING
            """)
    BigDecimal sumExecuting(@Param("userId") Long userId);
}
