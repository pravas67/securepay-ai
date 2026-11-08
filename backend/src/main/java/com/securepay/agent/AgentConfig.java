package com.securepay.agent;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentConfig {

    /**
     * Per-user conversation history, kept in memory and capped. Restarting the server forgets it, which is
     * acceptable for a chat that never holds the authoritative copy of anything.
     */
    @Bean
    ChatMemory chatMemory(AgentProperties properties) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(properties.memoryMessages())
                .build();
    }
}
