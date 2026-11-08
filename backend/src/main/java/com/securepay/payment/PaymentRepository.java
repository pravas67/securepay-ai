package com.securepay.payment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every finder takes a userId: there is no way to load a payment without saying whose it is. */
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findTop100ByUserIdAndCreatedAtAfterOrderByCreatedAtDesc(Long userId, OffsetDateTime after);

    Optional<Payment> findByReferenceAndUserId(String reference, Long userId);

    /** Used only by the provider webhook, which acts on behalf of the system, not a user. */
    Optional<Payment> findByProviderReference(String providerReference);

    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p
            where p.userId = :userId and p.createdAt > :since and p.status <> 'FAILED'
            """)
    BigDecimal sumSpentSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);
}
