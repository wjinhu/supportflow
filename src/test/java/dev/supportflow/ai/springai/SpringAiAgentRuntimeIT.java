package dev.supportflow.ai.springai;

import dev.supportflow.assistant.runtime.AgentLoopExecutor;
import dev.supportflow.assistant.runtime.AgentRetryProperties;
import dev.supportflow.assistant.runtime.AgentRuntimeProperties;
import dev.supportflow.assistant.runtime.store.*;
import dev.supportflow.assistant.tool.LogisticsTool;
import dev.supportflow.assistant.tool.OrderTool;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.conversation.ConversationMessageRequest;
import dev.supportflow.chat.conversation.JdbcConversationStore;
import dev.supportflow.common.security.model.CustomerIdentity;
import dev.supportflow.common.security.model.CustomerPrincipal;
import dev.supportflow.common.security.model.SecurityPermission;
import dev.supportflow.order.application.OrderQueryService;
import dev.supportflow.order.domain.model.OrderStatus;
import dev.supportflow.order.domain.model.SupportOrder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real MySQL checkpoints + real Spring AI client; no daily database and no paid calls. */
@Testcontainers
class SpringAiAgentRuntimeIT {
    @Container static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0.45")
            .withDatabaseName("spring_ai_runtime_test").withUsername("root").withPassword(UUID.randomUUID().toString())
            .withEnv("MYSQL_ROOT_HOST", "%").withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true").withUrlParam("sslMode", "DISABLED")
            .withUrlParam("allowPublicKeyRetrieval", "true");
    private final CustomerPrincipal buyer = new CustomerPrincipal("test-buyer", "unused", new CustomerIdentity(200), List.of(SecurityPermission.ORDER_READ));
    private SpringAiTestProvider provider;
    private JdbcAgentRunStore store;
    private JdbcConversationStore conversations;
    private JdbcClient jdbc;
    private DataSourceTransactionManager transactions;
    private OrderTool orders;
    private LogisticsTool logistics;
    private OrderQueryService authorization;
    private AgentLoopExecutor loop;

    @BeforeAll static void migrate() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        transactions = new DataSourceTransactionManager(source);
        jdbc = JdbcClient.create(source);
        store = new JdbcAgentRunStore(jdbc, new ObjectMapper(), transactions);
        conversations = new JdbcConversationStore(jdbc, new ObjectMapper(), transactions);
        provider = new SpringAiTestProvider();
        orders = mock(OrderTool.class);
        logistics = mock(LogisticsTool.class);
        authorization = mock(OrderQueryService.class);
        var order = new SupportOrder(123, 100, 999, 200, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("100.00"), "CNY", Instant.now(), Instant.now());
        when(authorization.getOwnedOrder(any(), anyLong(), anyString())).thenReturn(order);
        when(orders.lookup(any(), anyLong(), anyString())).thenReturn(order);
        when(logistics.lookup(any())).thenReturn("已揽收");
        loop = executor(new AgentRuntimeProperties(8, 90));
    }

    @Test void sdkDecisionsUseTheExistingValidationToolsCheckpointsAndBudget() {
        successReplies();
        var done = loop.advance(buyer, claim());
        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(5);
        assertThat(done.budget().chargedTokens()).isEqualTo(975);
        assertThat(done.budget().reportedTokens()).isEqualTo(975);
        assertThat(provider.requests).hasSize(3);
        assertThat(store.steps(done.key())).hasSize(5);
        verify(orders, times(1)).lookup(buyer.getIdentity(), 100, "SF10001");
        verify(logistics, times(1)).lookup(any());
    }

    @Test void oneDurableRetryMeansExactlyTwoProviderAttemptsForThatDecision() {
        provider.reply(503, "temporary provider failure");
        successReplies();
        var done = loop.advance(buyer, claim());
        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(provider.requests).hasSize(4);
        assertThat(done.budget().chargedTokens()).isEqualTo(16896 + 975);
        verify(orders, times(1)).lookup(any(), anyLong(), anyString());
        verify(logistics, times(1)).lookup(any());
    }

    @Test void restartAndResumePreserveOriginalDeadlineBudgetAndCompletedTool() {
        provider.decision("get_order", "{\"orderNo\":\"SF10001\"}", "order");
        provider.reply(401, "provider rejected");
        var claimed = claim();
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isInstanceOf(ModelUnavailableException.class);
        var paused = store.find(claimed.key());
        assertThat(paused.status()).isEqualTo(AgentRunStatus.PAUSED);
        long charge = paused.budget().chargedTokens();
        store = new JdbcAgentRunStore(jdbc, new ObjectMapper(), transactions);
        loop = executor(new AgentRuntimeProperties(8, 90));
        provider.decision("get_logistics", "{\"orderNo\":\"SF10001\"}", "logistics");
        provider.decision("finish", "{\"orderNo\":\"SF10001\"}", "finish");
        var done = loop.advance(buyer, store.claim(claimed.key(), true));
        assertThat(done.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(done.deadlineAt()).isEqualTo(paused.deadlineAt());
        assertThat(done.budget().chargedTokens()).isEqualTo(charge + 650);
        verify(orders, times(1)).lookup(any(), anyLong(), anyString());
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isInstanceOf(RuntimeException.class);
        assertThat(store.find(done.key()).generation()).isEqualTo(done.generation());
    }

    @Test void modelCannotSwitchOrdersOrForgeIdentityBeforeAnyToolExecution() {
        provider.decision("get_order", "{\"orderNo\":\"SF10002\"}", "switch");
        var claimed = claim();
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(claimed.key()).failureCode()).isEqualTo(AgentFailureCode.VALIDATION_REJECTED);
        verifyNoInteractions(orders, logistics);
        assertThat(provider.requests).hasSize(1);
    }

    @Test void duplicateCallIdCannotExecuteLogistics() {
        provider.decision("get_order", "{\"orderNo\":\"SF10001\"}", "duplicate");
        provider.decision("get_logistics", "{\"orderNo\":\"SF10001\"}", "duplicate");
        assertThatThrownBy(() -> loop.advance(buyer, claim())).isInstanceOf(ModelUnavailableException.class);
        verify(orders, times(1)).lookup(any(), anyLong(), anyString());
        verifyNoInteractions(logistics);
        assertThat(provider.requests).hasSize(2);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "guardrail-v1/{0}")
    @org.junit.jupiter.params.provider.MethodSource("runtimeAttacks")
    void runtimeRejectsReplayAndSwitchAfterCheckpoint(String id, String tool, String number, String callId) {
        provider.decision("get_order", "{\"orderNo\":\"SF10001\"}", "first");
        provider.decision(tool, "{\"orderNo\":\"" + number + "\"}", callId);
        var run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(ModelUnavailableException.class);
        assertThat(store.find(run.key()).failureCode()).isEqualTo(AgentFailureCode.VALIDATION_REJECTED);
        verify(orders, times(1)).lookup(any(), anyLong(), anyString());
        verifyNoInteractions(logistics);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> runtimeAttacks() {
        return java.util.stream.Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("duplicate-call-id", "get_logistics", "SF10001", "first"),
                org.junit.jupiter.params.provider.Arguments.of("repeated-completed-tool", "get_order", "SF10001", "second"),
                org.junit.jupiter.params.provider.Arguments.of("switch-order-after-observation", "get_logistics", "SF10002", "second"));
    }

    @Test void guardrailCrossShopSameOrderNumberCannotSupplyAnObservation() {
        var foreign = new SupportOrder(124, 101, 999, 200, "SF10001", OrderStatus.SHIPPED,
                new BigDecimal("99.00"), "CNY", Instant.now(), Instant.now());
        when(authorization.getOwnedOrder(any(), anyLong(), anyString())).thenReturn(foreign);
        provider.decision("get_order", "{\"orderNo\":\"SF10001\"}", "first");
        var run = claim();
        assertThatThrownBy(() -> loop.advance(buyer, run)).isInstanceOf(dev.supportflow.order.application.exception.OrderNotFoundException.class);
        assertThat(store.find(run.key()).loopState().observations()).isEmpty();
        verifyNoInteractions(orders, logistics);
    }

    @Test void exhaustedBudgetAndDeadlinePreventProviderCalls() {
        loop = executor(new AgentRuntimeProperties(8, 90, 1, new BigDecimal("0.05"), 16384, 512,
                new BigDecimal("0.30"), new BigDecimal("1.20"), "deepseek-flash"));
        var claimed = claim();
        assertThatThrownBy(() -> loop.advance(buyer, claimed)).isInstanceOf(RuntimeException.class);
        loop = executor(new AgentRuntimeProperties(8, 90));
        var expired = claim();
        jdbc.sql("UPDATE support_agent_run SET deadline_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(3)) WHERE public_id=:id")
                .param("id", expired.id().toString()).update();
        assertThatThrownBy(() -> loop.advance(buyer, store.find(expired.key()))).isInstanceOf(RuntimeException.class);
        assertThat(provider.requests).isEmpty();
        verifyNoInteractions(orders, logistics);
    }

    private AgentLoopExecutor executor(AgentRuntimeProperties properties) {
        return new AgentLoopExecutor(store, provider.planner, orders, logistics, authorization, properties,
                new AgentRetryProperties(1, Duration.ofMillis(1)), provider.meters);
    }

    private AgentRun claim() {
        var conversation = conversations.create(200, 100);
        var request = new ConversationMessageRequest(UUID.randomUUID(), "查物流 SF10001", "SF10001");
        conversations.reserve(200, 100, conversation.id(), request);
        var input = new AgentRunInput(new AgentRunKey(conversation.id(), request.clientMessageId(), 200, 100), request, request.message(), request.orderNo());
        loop.create(input);
        return store.claim(input.key(), false);
    }

    private void successReplies() {
        provider.decision("get_order", "{\"orderNo\":\"SF10001\"}", "order");
        provider.decision("get_logistics", "{\"orderNo\":\"SF10001\"}", "logistics");
        provider.decision("finish", "{\"orderNo\":\"SF10001\"}", "finish");
    }
}
