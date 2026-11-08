package com.securepay.wallet;

import com.securepay.security.SecurityUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @GetMapping("/api/wallet")
    WalletView balance(@AuthenticationPrincipal SecurityUser user) {
        return walletService.balance(user.id());
    }

    @GetMapping("/api/wallet/transactions")
    List<WalletTransactionView> transactions(@AuthenticationPrincipal SecurityUser user) {
        return walletService.recentTransactions(user.id());
    }
}
