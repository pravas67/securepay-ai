package com.securepay.agent;

import com.securepay.security.SecurityUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/api/agent/chat")
    AgentService.AgentReply chat(@AuthenticationPrincipal SecurityUser user, @Valid @RequestBody ChatRequest request) {
        return agentService.chat(user, request.message());
    }

    /** Starts a new conversation (forgets the previous messages). */
    @DeleteMapping("/api/agent/conversation")
    ResponseEntity<Void> reset(@AuthenticationPrincipal SecurityUser user) {
        agentService.resetConversation(user);
        return ResponseEntity.noContent().build();
    }

    public record ChatRequest(@NotBlank @Size(max = 2000) String message) {
    }
}
