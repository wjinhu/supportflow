package dev.supportflow.ai.springai;

import dev.supportflow.assistant.runtime.model.*;
import dev.supportflow.assistant.runtime.planning.*;
import dev.supportflow.chat.application.ModelUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class SpringAiLiveEvaluationToolTest {
    private static final Map<String, String> ENV = Map.of("SUPPORTFLOW_EVAL_INPUT_USD_PER_MILLION", "0.30",
            "SUPPORTFLOW_EVAL_OUTPUT_USD_PER_MILLION", "1.20");
    private final AgentDecisionInput input = new AgentDecisionInput("查物流", "SF90000001", AgentLoopState.empty(), 8192, 256);

    @Test void defaultEntryDoesNotStartDockerOrCallRemote(CapturedOutput output) {
        SpringAiLiveEvaluationTool.main(new String[0]);
        assertThat(output.getAll()).contains("NOT RUN");
    }

    @Test void capsConsentAndEnvironmentOnlyPricesAreValidated() {
        for (String[] args : List.of(new String[]{"--max-calls=1"}, new String[]{"--allow-remote", "--max-calls=25"},
                new String[]{"--allow-remote", "--max-tokens=250001"}, new String[]{"--allow-remote", "--max-cost-usd=0.11"},
                new String[]{"--allow-remote", "--deadline-seconds=181"}, new String[]{"--allow-remote", "--api-key=private-test"},
                new String[]{"--allow-remote", "--allow-remote"}))
            assertThatThrownBy(() -> SpringAiLiveEvaluationTool.parse(args, ENV)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SpringAiLiveEvaluationTool.parse(new String[]{"--allow-remote"}, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void globalCallTokenAndCostLimitsAreCheckedBeforeRequestAndNeverResetBetweenTasks() {
        for (String limit : List.of("--max-calls=1", "--max-tokens=8448", "--max-cost-usd=0.0027648")) {
            var delegate = mock(AgentDecisionPlanner.class);
            when(delegate.profile()).thenReturn(new AgentModelProfile("deepseek-flash", true));
            when(delegate.decide(any())).thenThrow(new ModelUnavailableException());
            var planner = new SpringAiLiveEvaluationTool.MeteredPlanner(delegate,
                    SpringAiLiveEvaluationTool.parse(new String[]{"--allow-remote", limit}, ENV));
            assertThatThrownBy(() -> planner.decide(input)).isInstanceOf(ModelUnavailableException.class);
            assertThatThrownBy(() -> planner.decide(input)).isInstanceOf(ModelUnavailableException.class);
            verify(delegate, times(1)).decide(any());
        }
    }

    @Test void totalDeadlineBoundsTheNextCallAndRequestTimeout() {
        var delegate = mock(AgentDecisionPlanner.class);
        when(delegate.profile()).thenReturn(new AgentModelProfile("deepseek-flash", true));
        when(delegate.decide(any())).thenAnswer(call -> {
            AgentDecisionInput request = call.getArgument(0);
            assertThat(request.callTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(8));
            return new AgentDecisionResult(new AgentDecision(AgentAction.ORDER_LOOKUP, "SF90000001", "test"), new AgentTokenUsage(100, 10));
        });
        var clock = new AtomicLong();
        var planner = new SpringAiLiveEvaluationTool.MeteredPlanner(delegate,
                SpringAiLiveEvaluationTool.parse(new String[]{"--allow-remote", "--deadline-seconds=1"}, ENV), clock::get);
        planner.decide(input);
        clock.set(Duration.ofSeconds(2).toNanos());
        assertThatThrownBy(() -> planner.decide(input)).isInstanceOf(ModelUnavailableException.class);
        verify(delegate, times(1)).decide(any());
    }
}
