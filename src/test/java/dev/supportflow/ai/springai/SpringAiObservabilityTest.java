package dev.supportflow.ai.springai;

import dev.supportflow.assistant.runtime.model.AgentLoopState;
import dev.supportflow.assistant.runtime.planning.AgentDecisionInput;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.common.observability.TraceContext;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.observation.ChatModelMeterObservationHandler;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SpringAiObservabilityTest {
    @Test void sdkUsageAndExistingCallCountersAreAvailableWithoutDuplicatingAttempts(CapturedOutput output) {
        var provider = new SpringAiTestProvider();
        var observations = ObservationRegistry.create();
        observations.observationConfig().observationHandler(new ChatModelMeterObservationHandler(provider.meters));
        var model = SpringAiConfiguration.model(provider.transport, provider.properties, observations);
        var planner = new SpringAiAgentDecisionPlanner(model, provider.transport, provider.properties, provider.json);
        var input = new AgentDecisionInput("查物流 SF10001", "SF10001", AgentLoopState.empty(), 16384, 512);
        provider.decision("get_order", "{\"orderNo\":\"SF10001\"}", "observed");
        UUID run = UUID.randomUUID();
        String trace;
        try (var request = TraceContext.beginRequest(); var agent = TraceContext.agentRun(run)) {
            trace = TraceContext.traceId();
            assertThat(planner.decide(input).usage().total()).isEqualTo(325);
        }
        assertThat(provider.requests).hasSize(1);
        assertThat(provider.meters.get("supportflow.ai.calls").tags("operation", "AGENT_DECISION", "outcome", "SUCCESS")
                .counter().count()).isEqualTo(1);
        assertThat(provider.meters.getMeters()).anySatisfy(meter -> assertThat(meter.getId().getName()).contains("token"));
        assertThat(output.getAll()).contains("traceId=" + trace, "agentRunId=" + run).doesNotContain(provider.properties.apiKey());
        provider.reply(503, "provider body contains " + provider.properties.apiKey());
        assertThatThrownBy(() -> planner.decide(input)).isInstanceOf(ModelUnavailableException.class).hasNoCause();
        assertThat(output.getAll()).doesNotContain(provider.properties.apiKey(), "provider body contains");
    }
}
