package com.securepay.wallet;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    List<WalletTransaction> findTop20ByUserIdOrderByCreatedAtDescIdDesc(Long userId);

    /** Total sent (as a positive number) by this user since {@code since}. */
    @Query("""
            select coalesce(-sum(t.amount), 0) from WalletTransaction t
            where t.userId = :userId and t.amount < 0 and t.createdAt > :since
            """)
    BigDecimal sumDebitsSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);
}
