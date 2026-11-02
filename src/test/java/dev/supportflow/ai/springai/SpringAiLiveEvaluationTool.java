package dev.supportflow.ai.springai;

import dev.supportflow.assistant.runtime.model.*;
import dev.supportflow.assistant.runtime.planning.*;
import dev.supportflow.assistant.runtime.store.AgentRunStatus;
import dev.supportflow.chat.application.ModelFailureKind;
import dev.supportflow.chat.application.ModelUnavailableException;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekCallExecutor;
import dev.supportflow.chat.infrastructure.deepseek.DeepSeekProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.http.HttpClient;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Explicit paid acceptance CLI on the test classpath; CI never invokes remote requests. */
public final class SpringAiLiveEvaluationTool {
    private static final String DATASET = "spring-ai-durable-synthetic-v1";
    private SpringAiLiveEvaluationTool() { }

    public static void main(String[] args) {
        int exit = 2;
        try {
            if (args.length == 0) {
                System.out.println("SPRING_AI_EVALUATION NOT RUN --allow-remote required");
                return;
            }
            var settings = Settings.parse(args, System.getenv());
            exit = execute(settings, System.getenv());
        } catch (Exception failure) {
            System.err.println("SPRING_AI_EVALUATION_FAILED (details suppressed)");
        }
        if (exit != 0) System.exit(exit);
    }

    private static int execute(Settings settings, Map<String, String> environment) throws Exception {
        var json = new ObjectMapper();
        var scenarios = SpringAiEvaluationSandbox.scenarios();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(scenarios)));
        var results = new ArrayList<Result>();
        UUID id = UUID.randomUUID();
        // Validate the environment credential before starting Docker or creating a provider client.
        var properties = new DeepSeekProperties(true, environment.get("DEEPSEEK_API_KEY"), settings.model(),
                Duration.ofSeconds(3), Duration.ofSeconds(8), 1);
        var meters = new SimpleMeterRegistry();
        try (var sandbox = new SpringAiEvaluationSandbox();
             var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var transport = new SpringAiTransport(new DeepSeekCallExecutor(http, properties, meters));
            var sdk = SpringAiConfiguration.model(transport, properties, ObservationRegistry.NOOP);
            var delegate = new SpringAiAgentDecisionPlanner(sdk, transport, properties, json);
            var bounded = new MeteredPlanner(delegate, settings);
            for (var scenario : scenarios) {
                if (!bounded.canStart()) {
                    results.add(new Result(scenario.id(), "SKIPPED", "LIMIT", false, false, 0, List.of()));
                    continue;
                }
                long started = System.nanoTime();
                bounded.decisions.clear();
                bounded.argumentsCorrect = true;
                var outcome = sandbox.execute(scenario, bounded, settings.inputRate(), settings.outputRate());
                boolean success = outcome.status() == AgentRunStatus.SUCCEEDED && outcome.tools().equals(scenario.expectedTools());
                results.add(new Result(scenario.id(), success ? "PASS" : "FAIL", outcome.failure(), success,
                        success && bounded.argumentsCorrect, elapsed(started), List.copyOf(bounded.decisions)));
            }
            int passed = (int) results.stream().filter(result -> result.outcome().equals("PASS")).count();
            int skipped = (int) results.stream().filter(result -> result.outcome().equals("SKIPPED")).count();
            int attempted = results.size() - skipped;
            var report = new Report(1, "SPRING_AI", settings.model(), DATASET, hash, Instant.now(), "ACTUALLY_RUN",
                    attempted, passed, attempted - passed, skipped, bounded.calls, settings.maxCalls(),
                    bounded.returned == 0 ? 0 : (double) bounded.correctSelections / bounded.returned,
                    bounded.returned == 0 ? 0 : (double) bounded.correctArguments / bounded.returned,
                    attempted == 0 ? 0 : (double) passed / attempted, bounded.budget.reportedTokens(), bounded.budget.chargedTokens(),
                    bounded.budget.chargedCostUsd(), settings.cost(), settings.tokens(), settings.deadlineSeconds(), 8,
                    percentile(bounded.latencies, 0.50), percentile(bounded.latencies, 0.95), List.copyOf(results));
            Path directory = Path.of("target", "real-model-evaluation");
            Files.createDirectories(directory);
            Path destination = directory.resolve("spring-ai-durable-" + id + ".json");
            Files.writeString(destination, json.writerWithDefaultPrettyPrinter().writeValueAsString(report), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            System.out.println("SPRING_AI_EVALUATION attempted=" + attempted + " passed=" + passed + " failed=" + (attempted - passed)
                    + " skipped=" + skipped + " calls=" + bounded.calls + " report=" + destination.getFileName());
            return attempted - passed > 0 ? 1 : skipped > 0 ? 3 : 0;
        } finally { meters.close(); }
    }

    static final class MeteredPlanner implements AgentDecisionPlanner {
        private final AgentDecisionPlanner delegate;
        private final Settings settings;
        private final java.util.function.LongSupplier nanoTime;
        private final long started;
        private AgentBudget budget;
        private int calls;
        private int returned;
        private int correctArguments;
        private int correctSelections;
        private boolean argumentsCorrect = true;
        private final List<Long> latencies = new ArrayList<>();
        private final List<AgentAction> decisions = new ArrayList<>();

        MeteredPlanner(AgentDecisionPlanner delegate, Settings settings) {
            this(delegate, settings, System::nanoTime);
        }
        MeteredPlanner(AgentDecisionPlanner delegate, Settings settings, java.util.function.LongSupplier nanoTime) {
            this.delegate = delegate;
            this.settings = settings;
            this.nanoTime = nanoTime;
            started = nanoTime.getAsLong();
            budget = new AgentBudget(delegate.profile(), settings.tokens(), 0, 0, settings.cost(), BigDecimal.ZERO,
                    8192, 256, settings.inputRate(), settings.outputRate());
        }
        private boolean canStart() { return calls < settings.maxCalls() && budget.canReserve() && remaining() > 0; }
        private long remaining() { return Duration.ofSeconds(settings.deadlineSeconds()).toNanos() - (nanoTime.getAsLong() - started); }
        @Override public AgentModelProfile profile() { return delegate.profile(); }
        @Override public AgentDecisionResult decide(AgentDecisionInput input) {
            if (!canStart()) throw new ModelUnavailableException(ModelFailureKind.CANCELLED);
            long left = remaining();
            if (left <= 0) throw new ModelUnavailableException(ModelFailureKind.CANCELLED);
            budget = budget.reserve();
            calls++;
            long callStarted = nanoTime.getAsLong();
            try {
                var result = delegate.decide(new AgentDecisionInput(input.message(), input.selectedOrderNo(), input.state(), 8192, 256,
                        Duration.ofNanos(Math.min(left, Math.min(input.callTimeout().toNanos(), Duration.ofSeconds(8).toNanos())))));
                budget = budget.settle(result.usage());
                returned++;
                if (result.decision().action() == new RuleBasedAgentDecisionPlanner().decide(input).decision().action()) correctSelections++;
                boolean correct = result.decision().action() == AgentAction.CLARIFY ? result.decision().orderNo() == null
                        : SpringAiEvaluationSandbox.ORDER.equals(result.decision().orderNo());
                if (correct) correctArguments++;
                argumentsCorrect &= correct;
                decisions.add(result.decision().action());
                return result;
            } finally { latencies.add(Math.max(0, nanoTime.getAsLong() - callStarted) / 1000000); }
        }
    }

    static Settings parse(String[] args, Map<String, String> environment) { return Settings.parse(args, environment); }
    record Settings(String model, int maxCalls, long tokens, BigDecimal cost, int deadlineSeconds, BigDecimal inputRate, BigDecimal outputRate) {
        static Settings parse(String[] args, Map<String, String> environment) {
            boolean allowed = false;
            int calls = 20, deadline = 180;
            long tokens = 200000;
            BigDecimal cost = new BigDecimal("0.05");
            Set<String> seen = new HashSet<>();
            for (String argument : args) {
                String name = argument.split("=", 2)[0];
                if (!seen.add(name)) throw new IllegalArgumentException("Duplicate evaluation setting");
                if (argument.equals("--allow-remote")) allowed = true;
                else if (argument.startsWith("--max-calls=")) calls = Integer.parseInt(argument.substring(12));
                else if (argument.startsWith("--max-tokens=")) tokens = Long.parseLong(argument.substring(13));
                else if (argument.startsWith("--max-cost-usd=")) cost = decimal(argument.substring(15));
                else if (argument.startsWith("--deadline-seconds=")) deadline = Integer.parseInt(argument.substring(19));
                else throw new IllegalArgumentException("Unknown evaluation setting");
            }
            if (!allowed || calls < 1 || calls > 24 || tokens < 1 || tokens > 250000 || deadline < 1 || deadline > 180
                    || cost.signum() <= 0 || cost.compareTo(new BigDecimal("0.10")) > 0) throw new IllegalArgumentException("Invalid evaluation limits or consent");
            String model = environment.getOrDefault("DEEPSEEK_MODEL", "deepseek-flash");
            new AgentModelProfile(model, true);
            BigDecimal input = decimal(environment.get("SUPPORTFLOW_EVAL_INPUT_USD_PER_MILLION"));
            BigDecimal output = decimal(environment.get("SUPPORTFLOW_EVAL_OUTPUT_USD_PER_MILLION"));
            if (input.signum() <= 0 || output.signum() <= 0 || input.compareTo(new BigDecimal("1000")) > 0
                    || output.compareTo(new BigDecimal("1000")) > 0) throw new IllegalArgumentException("Bounded price estimates required");
            return new Settings(model, calls, tokens, cost, deadline, input, output);
        }
        private static BigDecimal decimal(String text) {
            if (text == null || !text.matches("[0-9]{1,6}(\\.[0-9]{1,12})?")) throw new IllegalArgumentException("Invalid estimate");
            return new BigDecimal(text);
        }
    }
    private static long elapsed(long start) { return Math.max(0, System.nanoTime() - start) / 1000000; }
    private static long percentile(List<Long> values, double quantile) {
        if (values.isEmpty()) return 0;
        var sorted = values.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1));
    }
    record Result(String caseId, String outcome, String failureReason, boolean taskSuccess, boolean argumentAccuracy,
                  long latencyMs, List<AgentAction> decisions) { }
    record Report(int schemaVersion, String planner, String model, String datasetVersion, String datasetSha256, Instant generatedAt,
                  String runStatus, int attempted, int passed, int failed, int skipped, int attemptedCalls, int maxCalls,
                  double toolSelectionAccuracy, double argumentAccuracy, double taskSuccess, long tokenUsage, long chargedTokens,
                  BigDecimal estimatedCostUsd, BigDecimal costBudgetUsd, long tokenBudget, int totalDeadlineSeconds, int requestTimeoutSeconds,
                  long p50LatencyMs, long p95LatencyMs, List<Result> results) { }
}
