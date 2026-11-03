package dev.supportflow.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** A deterministic external-resource stand-in proves callback order without another Docker container. */
@SpringJUnitConfig(SpringContainerShutdownOrderTest.Beans.class)
@ExtendWith(SpringContainerShutdownOrderTest.ExternalResource.class)
class SpringContainerShutdownOrderTest extends SpringContainerTest {
    private static boolean externalRunning;
    private static boolean beanClosed;
    private static ConfigurableApplicationContext application;

    @Test void externalResourceRemainsAvailableThroughoutApplicationLifetime(ConfigurableApplicationContext context) {
        application = context;
        assertThat(externalRunning).isTrue();
        assertThat(context.isActive()).isTrue();
    }

    @Configuration(proxyBeanMethods = false)
    static class Beans {
        @Bean AutoCloseable dependentResource() {
            return () -> {
                assertThat(externalRunning).as("external resource still alive during bean destruction").isTrue();
                beanClosed = true;
            };
        }
    }

    static class ExternalResource implements BeforeAllCallback, AfterAllCallback {
        @Override public void beforeAll(ExtensionContext context) { externalRunning = true; beanClosed = false; }
        @Override public void afterAll(ExtensionContext context) {
            try {
                assertThat(application.isActive()).as("context closed before external AfterAllCallback").isFalse();
                assertThat(beanClosed).isTrue();
            } finally { externalRunning = false; }
        }
    }
}
