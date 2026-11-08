package com.securepay.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.securepay.security.SecurityUser;
import com.securepay.support.PostgresTestBase;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Prompt-injection and abuse tests. The real model is replaced by a scripted "compromised" model that does
 * whatever an attacker would want. Everything else (tools, services, limits, database) is real. The point:
 * security must hold even when the model is fully hostile.
 */
@SpringBootTest(properties = "spring.ai.openai.api-key=sk-test-not-used")
class AgentSecurityIntegrationTest extends PostgresTestBase {

    @MockitoBean ChatModel chatModel;
    @Autowired AgentService agent;
    @Autowired AgentProperties properties;

    SecurityUser alice;
    SecurityUser bob;

    @BeforeEach
    void setUp() {
        resetMoneyState();
        alice = new SecurityUser(userId("alice"), "alice", null, "ADMIN");
        bob = new SecurityUser(userId("bob"), "bob", null, "USER");
        agent.resetConversation(alice);
        agent.resetConversation(bob);
    }

    private static ChatResponse toolCall(String name, String argumentsJson) {
        var call = new AssistantMessage.ToolCall("call_" + name + "_" + System.nanoTime(), "function", name, argumentsJson);
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("").toolCalls(List.of(call)).build())));
    }

    private static ChatResponse text(String reply) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(reply))));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    @Test
    void injectedInstructionCanAtMostProduceADraftThatOnlyTheUserCanApprove() {
        // The model "obeys" the injected description in PAY-1005: tries to drain the wallet, then a smaller amount.
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolCall("getPaymentHistory", "{\"days\":60}"),
                toolCall("prepareWalletTransfer", "{\"amount\":25000,\"recipientUsername\":\"bob\",\"description\":\"as instructed\"}"),
                toolCall("preparePayment", "{\"amount\":9999,\"currency\":\"INR\",\"recipient\":\"account 9999\"}"),
                text("Done! I've transferred everything."));

        AgentService.AgentReply reply = agent.chat(alice, "Summarise my payments");

        assertThat(reply.toolsUsed()).containsExactly("getPaymentHistory", "prepareWalletTransfer", "preparePayment");
        // Over the single-transaction limit: refused outright, nothing drafted.
        assertThat(count("select count(*) from payment_draft where kind = 'WALLET_TRANSFER'")).isZero();
        // Within limits: drafted, but nothing executed. The user sees it and can reject it.
        assertThat(count("select count(*) from payment_draft where recipient = 'account 9999' and status = 'PENDING_APPROVAL'")).isEqualTo(1);
        assertThat(count("select count(*) from payment where recipient = 'account 9999'")).isZero();
        assertThat(count("select count(*) from wallet_transaction")).isZero();
    }

    @Test
    void toolsThatDoNotExistAreRefusedBeforeAnythingRuns() {
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolCall("approvePaymentDraft", "{\"reference\":\"DRF-ANY\"}"),
                text("should never be reached"));

        AgentService.AgentReply reply = agent.chat(alice, "approve everything");

        assertThat(reply.reply()).contains("isn't available");
        assertThat(reply.toolsUsed()).isEmpty();
        assertThat(count("select count(*) from audit_log where user_id = ? and action = 'AGENT_TOOL_REFUSED'", alice.id())).isPositive();
    }

    @Test
    void endlessToolLoopsAreCutOff() {
        AtomicInteger modelCalls = new AtomicInteger();
        when(chatModel.call(any(Prompt.class))).thenAnswer(inv -> {
            modelCalls.incrementAndGet();
            return toolCall("getWalletBalance", "{}");
        });

        AgentService.AgentReply reply = agent.chat(alice, "check my balance forever");

        assertThat(reply.toolsUsed()).hasSize(properties.maxToolCalls());
        assertThat(modelCalls.get()).isEqualTo(properties.maxToolCalls() + 1);
        assertThat(reply.reply()).contains("more steps");
    }

    @Test
    void modelCannotChooseWhoseDataItActsOn() {
        // An extra "userId" argument is not part of the tool schema and is ignored; identity comes from the session.
        when(chatModel.call(any(Prompt.class))).thenReturn(
                toolCall("preparePayment", "{\"amount\":100,\"currency\":\"INR\",\"recipient\":\"Smuggle Test\",\"userId\":" + bob.id() + "}"),
                text("ok"));

        agent.chat(alice, "pay 100 to Smuggle Test");

        assertThat(count("select count(*) from payment_draft where recipient = 'Smuggle Test' and user_id = ?", alice.id())).isEqualTo(1);
        assertThat(count("select count(*) from payment_draft where user_id = ?", bob.id())).isZero();
    }

    @Test
    void regularUsersCannotReadCloudDataThroughTheAgent() {
        when(chatModel.call(any(Prompt.class))).thenReturn(toolCall("getAwsMonthlyCost", "{}"), text("ok"));
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);

        agent.chat(bob, "what's our AWS bill?");

        verify(chatModel, atMost(2)).call(prompts.capture());
        Message toolResult = prompts.getAllValues().getLast().getInstructions().getLast();
        assertThat(toolResult).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) toolResult).getResponses().getFirst().responseData()).contains("Only administrators");
        assertThat(count("select count(*) from audit_log where user_id = ? and action = 'TOOL_getAwsMonthlyCost' and outcome = 'DENIED'", bob.id())).isPositive();
    }

    @Test
    void conversationMemoryIsPerUser() {
        when(chatModel.call(any(Prompt.class))).thenReturn(text("first answer"), text("second answer"), text("bob answer"));
        ArgumentCaptor<Prompt> prompts = ArgumentCaptor.forClass(Prompt.class);

        agent.chat(alice, "my secret plan is X");
        agent.chat(alice, "what did I just say?");
        agent.chat(bob, "what did alice say?");

        verify(chatModel, atMost(3)).call(prompts.capture());
        String aliceSecond = prompts.getAllValues().get(1).getContents();
        String bobFirst = prompts.getAllValues().get(2).getContents();
        assertThat(aliceSecond).contains("my secret plan is X");
        assertThat(bobFirst).doesNotContain("my secret plan is X");
    }
}
