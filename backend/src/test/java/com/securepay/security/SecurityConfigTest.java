package com.securepay.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.securepay.user.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeController.class)
@Import({SecurityConfig.class, JwtConfig.class, TokenService.class, DbUserDetailsService.class})
class SecurityConfigTest {

    @Autowired MockMvc mvc;
    @Autowired TokenService tokens;

    @MockitoBean AppUserRepository users;

    private String bearer(SecurityUser user) {
        return "Bearer " + tokens.issue(user).token();
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void validTokenIdentifiesTheUser() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", bearer(new SecurityUser(7L, "alice", null, "USER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void tamperedTokenIsRejected() throws Exception {
        String token = bearer(new SecurityUser(7L, "alice", null, "USER"));
        // Flip one character of the signature.
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "B" : "A") + token.charAt(token.length() - 1);

        mvc.perform(get("/api/me").header("Authorization", tampered)).andExpect(status().isUnauthorized());
    }

    @Test
    void cloudEndpointsRequireAdmin() throws Exception {
        mvc.perform(get("/api/cloud/instances").header("Authorization", bearer(new SecurityUser(7L, "bob", null, "USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownPathsAreDenied() throws Exception {
        mvc.perform(get("/admin").header("Authorization", bearer(new SecurityUser(7L, "alice", null, "ADMIN"))))
                .andExpect(status().isForbidden());
    }
}
