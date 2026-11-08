package com.securepay.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securepay.support.PostgresTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest extends PostgresTestBase {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(username, password)));
    }

    @Test
    void loginIssuesATokenThatWorksForTheApi() throws Exception {
        String body = login("bob", "password").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("token").asText();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"));
    }

    @Test
    void unknownUserAndWrongPasswordLookTheSame() throws Exception {
        login("bob", "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Wrong username or password."));
        login("nobody-" + System.nanoTime(), "wrong").andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("Wrong username or password."));
    }

    @Test
    void repeatedFailuresLockTheUsernameEvenForTheRightPassword() throws Exception {
        // A username used only by this test, so the lockout doesn't affect other tests.
        jdbc.update("insert into app_user (username, password_hash, role) values ('locktest', crypt('password', gen_salt('bf', 4)), 'USER') on conflict do nothing");
        for (int i = 0; i < AuthController.MAX_FAILED_LOGINS; i++) {
            login("locktest", "wrong").andExpect(status().isUnauthorized());
        }
        login("locktest", "password").andExpect(status().isTooManyRequests());
    }
}
