package dev.supportflow.testsupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestContextManager;

import static org.assertj.core.api.Assertions.assertThat;

/** Close application resources in a user callback, before Testcontainers' AfterAllCallback stops class containers. */
public abstract class SpringContainerTest {
    @AfterAll
    protected static void closeApplicationBeforeContainers(TestInfo test, @Autowired ConfigurableApplicationContext context) {
        // Resolve the same cache key through Spring's public test API, without loading a second context.
        var testContext = new TestContextManager(test.getTestClass().orElseThrow()).getTestContext();
        assertThat(testContext.hasApplicationContext()).isTrue();
        assertThat(testContext.getApplicationContext()).isSameAs(context);
        ApplicationResourceAssertions.closeAndVerify(context,
                () -> testContext.markApplicationContextDirty(DirtiesContext.HierarchyMode.EXHAUSTIVE));
        assertThat(testContext.hasApplicationContext()).as("closed context evicted from Spring cache").isFalse();
    }
}
