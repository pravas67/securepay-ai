package com.securepay.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.securepay.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "securepay.bootstrap.admin-username=Ops-Admin",
        "securepay.bootstrap.admin-password=a-long-enough-password"})
class UserBootstrapIntegrationTest extends PostgresTestBase {

    @Autowired UserBootstrap bootstrap;

    @Test
    void createsTheAdministratorOnceWithADemoWallet() throws Exception {
        bootstrap.run(null); // second run (the first happened at startup) must be a no-op

        assertThat(jdbc.queryForObject("select count(*) from app_user where username = 'ops-admin' and role = 'ADMIN'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select w.balance from wallet w join app_user u on u.id = w.user_id where u.username = 'ops-admin'
                """, java.math.BigDecimal.class)).isEqualByComparingTo("10000.00");
    }
}
