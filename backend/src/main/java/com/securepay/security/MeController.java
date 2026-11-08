package com.securepay.security;

import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping("/api/me")
    Map<String, Object> me(@AuthenticationPrincipal SecurityUser user) {
        return Map.of("id", user.id(), "username", user.username(), "role", user.role());
    }
}
