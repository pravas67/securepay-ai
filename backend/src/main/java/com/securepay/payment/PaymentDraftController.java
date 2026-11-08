package com.securepay.payment;

import com.securepay.security.SecurityUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The human side of the approval flow. The AI agent has no access to these endpoints. */
@RestController
@RequestMapping("/api/payment-drafts")
public class PaymentDraftController {

    private final PaymentDraftService draftService;

    public PaymentDraftController(PaymentDraftService draftService) {
        this.draftService = draftService;
    }

    @GetMapping
    List<DraftView> pending(@AuthenticationPrincipal SecurityUser user) {
        return draftService.pending(user.id());
    }

    /** Drafts a card payment by hand (e.g. with curl) without going through the AI. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    DraftView prepare(@AuthenticationPrincipal SecurityUser user, @Valid @RequestBody PrepareRequest request) {
        return draftService.prepare(user.id(), request.amount(), request.currency(), request.recipient(), request.description());
    }

    /** Drafts a demo-wallet transfer to another user, by username. */
    @PostMapping("/wallet-transfers")
    @ResponseStatus(HttpStatus.CREATED)
    DraftView prepareTransfer(@AuthenticationPrincipal SecurityUser user, @Valid @RequestBody TransferRequest request) {
        return draftService.prepareWalletTransfer(user.id(), request.amount(), request.recipient(), request.description());
    }

    @PostMapping("/{reference}/approve")
    ApprovalResult approve(@AuthenticationPrincipal SecurityUser user, @PathVariable String reference) {
        return draftService.approve(user.id(), reference);
    }

    @PostMapping("/{reference}/reject")
    DraftView reject(@AuthenticationPrincipal SecurityUser user, @PathVariable String reference) {
        return draftService.reject(user.id(), reference);
    }

    public record PrepareRequest(@NotNull BigDecimal amount, @NotBlank String currency,
                                 @NotBlank String recipient, String description) {
    }

    public record TransferRequest(@NotNull BigDecimal amount, @NotBlank String recipient, String description) {
    }
}
