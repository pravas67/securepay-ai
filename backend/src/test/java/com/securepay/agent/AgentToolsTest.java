package com.securepay.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.securepay.audit.AuditOutcome;
import com.securepay.audit.AuditService;
import com.securepay.cloud.CloudReadService;
import com.securepay.common.NotFoundException;
import com.securepay.payment.PaymentDraftService;
import com.securepay.payment.PaymentService;
import com.securepay.payment.TransactionAnalyzer;
import com.securepay.wallet.WalletService;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

class AgentToolsTest {

    private final PaymentService paymentService = mock(PaymentService.class);
    private final WalletService walletService = mock(WalletService.class);
    private final PaymentDraftService draftService = mock(PaymentDraftService.class);
    private final TransactionAnalyzer analyzer = mock(TransactionAnalyzer.class);
    private final CloudReadService cloud = mock(CloudReadService.class);
    private final AuditService audit = mock(AuditService.class);
    private final AgentTools tools = new AgentTools(paymentService, walletService, draftService, analyzer, cloud, audit);

    private static ToolContext contextFor(Long userId, String role) {
        return new ToolContext(Map.of(AgentTools.USER_ID, userId, AgentTools.ROLE, role));
    }

    @Test
    void historyIsScopedToTheUserInTheToolContext() {
        when(paymentService.history(42L, 30)).thenReturn(List.of());

        tools.getPaymentHistory(30, contextFor(42L, "USER"));

        verify(paymentService).history(42L, 30);
        verify(audit).record(eq(42L), eq("TOOL_getPaymentHistory"), any(), eq(AuditOutcome.SUCCESS));
    }

    @Test
    void someoneElsesPaymentLooksExactlyLikeAMissingOne() {
        when(paymentService.status(42L, "PAY-2001")).thenThrow(new NotFoundException("not found"));

        AgentTools.PaymentLookup result = tools.getPaymentStatus("PAY-2001", contextFor(42L, "USER"));

        assertThat(result.found()).isFalse();
        assertThat(result.payment()).isNull();
        verify(audit).record(eq(42L), eq("TOOL_getPaymentStatus"), any(), eq(AuditOutcome.DENIED));
    }

    @Test
    void toolCallWithoutServerSuppliedIdentityFailsClosed() {
        assertThatThrownBy(() -> tools.getPaymentHistory(30, new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> tools.getWalletBalance(new ToolContext(Map.of(AgentTools.USER_ID, "42"))))
                .isInstanceOf(IllegalStateException.class);

        verify(paymentService, never()).history(anyLong(), anyInt());
        verify(walletService, never()).balance(anyLong());
    }

    @Test
    void preparedPaymentsAndTransfersBelongToTheSessionUser() {
        tools.preparePayment(new BigDecimal("500"), "INR", "Test Recipient", "test", contextFor(42L, "USER"));
        tools.prepareWalletTransfer(new BigDecimal("50"), "bob", null, contextFor(42L, "USER"));

        verify(draftService).prepare(42L, new BigDecimal("500"), "INR", "Test Recipient", "test");
        verify(draftService).prepareWalletTransfer(42L, new BigDecimal("50"), "bob", null);
    }

    @Test
    void cloudToolsRequireTheAdminRole() {
        Object result = tools.getAwsResourceStatus(contextFor(42L, "USER"));
        tools.getAwsMonthlyCost(contextFor(42L, "USER"));

        assertThat(result).isInstanceOf(AgentTools.Refusal.class);
        verifyNoInteractions(cloud);
        verify(audit).record(eq(42L), eq("TOOL_getAwsResourceStatus"), any(), eq(AuditOutcome.DENIED));
    }

    /**
     * The model's capabilities are exactly this list. Adding a tool should be a deliberate decision that
     * updates this test, and nothing on it may approve, execute, cancel, refund or change infrastructure.
     */
    @Test
    void theModelsToolSurfaceIsExactlyTheReviewedList() {
        Set<String> toolNames = Arrays.stream(AgentTools.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Tool.class))
                .map(m -> m.getName())
                .collect(Collectors.toSet());

        assertThat(toolNames).containsExactlyInAnyOrder(
                "getPaymentHistory", "getPaymentStatus", "analyzeTransactions", "getWalletBalance",
                "preparePayment", "prepareWalletTransfer", "getAwsResourceStatus", "getAwsMonthlyCost");
        assertThat(toolNames).noneMatch(name -> name.matches("(?i).*(approve|execute|cancel|refund|delete|stop|terminate).*"));
    }
}
