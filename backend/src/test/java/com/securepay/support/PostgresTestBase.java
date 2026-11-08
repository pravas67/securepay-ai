package com.securepay.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One real PostgreSQL container shared by every integration test class (started once per JVM).
 * Locks, triggers and conditional updates are exactly what we need to test, so no in-memory database.
 */
public abstract class PostgresTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected JdbcTemplate jdbc;

    protected Long userId(String username) {
        return jdbc.queryForObject("select id from app_user where username = ?", Long.class, username);
    }

    /**
     * Puts money-related tables back to the seeded state. The ledger is append-only for the application
     * (row triggers block UPDATE/DELETE); TRUNCATE is a table-level admin operation that the production
     * database role must not be granted (see docs/security.md).
     */
    protected void resetMoneyState() {
        jdbc.update("delete from payment_draft");
        jdbc.update("delete from payment where provider_reference is not null");
        jdbc.update("delete from processed_webhook_event");
        // Seeded payments are moved out of the 24h window so limits start clean.
        jdbc.update("update payment set created_at = now() - interval '30 days' where created_at > now() - interval '30 days'");
        jdbc.execute("truncate wallet_transaction");
        jdbc.update("update wallet set balance = 25000.00 where user_id = (select id from app_user where username = 'alice')");
        jdbc.update("update wallet set balance = 4200.00 where user_id = (select id from app_user where username = 'bob')");
    }
}
