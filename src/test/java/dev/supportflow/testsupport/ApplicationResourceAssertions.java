package dev.supportflow.testsupport;

import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.resource.ClientResources;
import io.netty.util.concurrent.EventExecutorGroup;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/** Checks only resources owned by this context, without creating lazy beans or scanning JVM threads. */
public final class ApplicationResourceAssertions {
    private ApplicationResourceAssertions() { }

    public static void closeAndVerify(ConfigurableApplicationContext context) {
        closeAndVerify(context, context::close);
    }

    static void closeAndVerify(ConfigurableApplicationContext context, Runnable close) {
        var resources = Resources.capture(context);
        try {
            await().alias("all application JDBC connections returned before shutdown")
                    .atMost(Duration.ofSeconds(5)).untilAsserted(resources::assertConnectionsReturned);
        } finally {
            close.run();
            assertThat(context.isActive()).as("application context closed").isFalse();
            await().alias("context-owned resources terminated after close")
                    .atMost(Duration.ofSeconds(5)).untilAsserted(resources::assertClosed);
        }
    }

    private record Resources(List<HikariDataSource> pools, Set<ExecutorService> executors,
                             List<SimpleAsyncTaskExecutor> simpleExecutors, List<HttpClient> clients,
                             List<LettuceConnectionFactory> redis, List<EventExecutorGroup> redisExecutors) {
        static Resources capture(ConfigurableApplicationContext context) {
            var resources = new Resources(new ArrayList<>(), new LinkedHashSet<>(), new ArrayList<>(),
                    new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            var beans = context.getBeanFactory();
            for (String name : beans.getSingletonNames()) {
                Object bean = beans.getSingleton(name);
                if (bean instanceof HikariDataSource pool) resources.pools.add(pool);
                if (bean instanceof ThreadPoolTaskExecutor executor) resources.executors.add(executor.getThreadPoolExecutor());
                if (bean instanceof ThreadPoolTaskScheduler scheduler) resources.executors.add(scheduler.getScheduledThreadPoolExecutor());
                if (bean instanceof ExecutorService executor) resources.executors.add(executor);
                if (bean instanceof SimpleAsyncTaskExecutor executor) resources.simpleExecutors.add(executor);
                if (bean instanceof HttpClient client) resources.clients.add(client);
                if (bean instanceof LettuceConnectionFactory factory) resources.redis.add(factory);
                if (bean instanceof ClientResources client) resources.redisExecutors.add(client.eventExecutorGroup());
            }
            return resources;
        }

        void assertConnectionsReturned() {
            for (var pool : pools) {
                var state = pool.getHikariPoolMXBean();
                if (state != null) assertThat(state.getActiveConnections()).as("%s borrowed connections", pool.getPoolName()).isZero();
            }
        }

        void assertClosed() {
            for (var pool : pools) {
                assertThat(pool.isClosed()).as("%s closed", pool.getPoolName()).isTrue();
                var state = pool.getHikariPoolMXBean();
                if (state != null) assertThat(state.getTotalConnections()).as("%s retained connections", pool.getPoolName()).isZero();
            }
            for (var executor : executors) assertThat(executor.isTerminated()).as("owned executor terminated").isTrue();
            for (var executor : simpleExecutors) assertThat(executor.isActive()).as("owned async executor closed").isFalse();
            for (var client : clients) assertThat(client.isTerminated()).as("owned HTTP client terminated").isTrue();
            for (var factory : redis) {
                assertThat(factory.isRunning()).as("Redis connection factory stopped").isFalse();
                assertThatThrownBy(factory::getConnection).as("closed Redis factory cannot create another connection")
                        .isInstanceOf(IllegalStateException.class);
            }
            for (var executor : redisExecutors) assertThat(executor.isTerminated()).as("owned Redis event executor terminated").isTrue();
        }
    }
}
