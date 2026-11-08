package com.securepay.payment;

import com.securepay.security.SecurityUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    List<PaymentView> history(@AuthenticationPrincipal SecurityUser user,
                              @RequestParam(defaultValue = "30") int days) {
        return paymentService.history(user.id(), days);
    }

    @GetMapping("/{reference}")
    PaymentView status(@AuthenticationPrincipal SecurityUser user, @PathVariable String reference) {
        return paymentService.status(user.id(), reference);
    }
}
