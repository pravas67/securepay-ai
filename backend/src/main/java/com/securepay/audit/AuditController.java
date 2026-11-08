package com.securepay.audit;

import com.securepay.security.SecurityUser;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/api/audit")
    List<AuditEntry> recent(@AuthenticationPrincipal SecurityUser user) {
        return auditService.recentFor(user.id());
    }
}
