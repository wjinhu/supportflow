package dev.supportflow.testsupport;

import dev.supportflow.chat.infrastructure.deepseek.DeepSeekConfiguration;
import dev.supportflow.assistant.execution.AfterSaleExecutionScheduling;
import dev.supportflow.assistant.execution.AfterSaleExecutionWorker;
import dev.supportflow.common.traffic.TrafficConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

class ApplicationResourceLifecycleTest {
    @Test void closingContextTerminatesStartedWorkersSchedulerAndHttpClient() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("lifecycle-test", Map.of(
                    "supportflow.after-sale.execution.enabled", "true",
                    "supportflow.after-sale.execution.poll-delay-ms", "60000")));
            context.registerBean(AfterSaleExecutionWorker.class, () -> mock(AfterSaleExecutionWorker.class));
            context.register(AfterSaleExecutionScheduling.class);
            context.registerBean("applicationTaskExecutor", ThreadPoolTaskExecutor.class, () -> {
                var executor = new ThreadPoolTaskExecutor();
                executor.setThreadNamePrefix("supportflow-lifecycle-worker-");
                executor.setCorePoolSize(1);
                return executor;
            });
            context.registerBean("taskScheduler", ThreadPoolTaskScheduler.class, () -> {
                var scheduler = new ThreadPoolTaskScheduler();
                scheduler.setThreadNamePrefix("supportflow-lifecycle-scheduler-");
                return scheduler;
            });
            context.registerBean("backgroundWorker", ExecutorService.class, () -> Executors.newSingleThreadExecutor());
            context.registerBean("httpClient", HttpClient.class, HttpClient::newHttpClient);
            context.refresh();

            var worker = context.getBean(ThreadPoolTaskExecutor.class);
            var scheduler = context.getBean(ThreadPoolTaskScheduler.class);
            var background = context.getBean(ExecutorService.class);
            var http = context.getBean(HttpClient.class);
            assertThat(worker.submit(() -> 1).get(2, TimeUnit.SECONDS)).isEqualTo(1);
            scheduler.schedule(() -> { }, Instant.now()).get(2, TimeUnit.SECONDS);
            assertThat(scheduler.getScheduledThreadPoolExecutor().getQueue()).as("real polling bean registered a scheduled task").isNotEmpty();
            assertThat(background.submit(() -> 2).get(2, TimeUnit.SECONDS)).isEqualTo(2);
            assertThat(http.isTerminated()).isFalse();

            ApplicationResourceAssertions.closeAndVerify(context);
            assertThat(worker.getThreadPoolExecutor().isTerminated()).isTrue();
            assertThat(scheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
            assertThat(background.isTerminated()).isTrue();
            assertThat(http.isTerminated()).isTrue();
        }
    }

    @Test void existingDeepSeekHttpBeanClosesWithoutAnyRemoteRequest() {
        var client = new AtomicReference<HttpClient>();
        new ApplicationContextRunner()
                .withUserConfiguration(DeepSeekConfiguration.class, TrafficConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new).withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("supportflow.ai.deepseek.enabled=true", "supportflow.ai.deepseek.api-key=fake-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    client.set(context.getBean("deepSeekHttpClient", HttpClient.class));
                    assertThat(client.get().isTerminated()).isFalse();
                });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(client.get().isTerminated()).isTrue());
    }
}
