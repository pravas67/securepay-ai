package com.securepay.user;

import com.securepay.wallet.WalletService;
import java.math.BigDecimal;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deployed environments don't load demo data, so they start with no users. If BOOTSTRAP_ADMIN_USERNAME and
 * BOOTSTRAP_ADMIN_PASSWORD are set and that user doesn't exist yet, this creates it once (as ADMIN, with a demo
 * wallet). Remove the variables after the first start.
 */
@Component
public class UserBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserBootstrap.class);
    static final int MIN_PASSWORD_LENGTH = 12;

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final WalletService wallets;
    private final String username;
    private final String password;

    public UserBootstrap(AppUserRepository users, PasswordEncoder passwordEncoder, WalletService wallets,
                         @Value("${securepay.bootstrap.admin-username:}") String username,
                         @Value("${securepay.bootstrap.admin-password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.wallets = wallets;
        // Usernames are stored lowercase (recipient lookups are case-insensitive).
        this.username = username.strip().toLowerCase(Locale.ROOT);
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (username.isBlank() || password.isBlank() || users.findByUsername(username).isPresent()) {
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("BOOTSTRAP_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        AppUser admin = users.save(new AppUser(username, passwordEncoder.encode(password), "ADMIN"));
        wallets.openDemoWallet(admin.getId(), new BigDecimal("10000.00"), "INR");
        log.info("Created bootstrap administrator '{}'", username);
    }
}
