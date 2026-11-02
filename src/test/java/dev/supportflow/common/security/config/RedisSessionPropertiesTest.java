package dev.supportflow.common.security.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedisSessionPropertiesTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"a", "ab", ":session", "supportflow session", "supportflow/session", "supportflow\nsession"})
    void rejectsAnAmbiguousOrUnboundedEnvironmentNamespace(String namespace) {
        assertThatThrownBy(() -> new RedisSessionProperties(true, namespace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("namespace");
    }

    @Test
    void rejectsOverlongNamespacesWithoutLeakingTheConfiguredValue() {
        String namespace = "s".repeat(129);
        assertThatThrownBy(() -> new RedisSessionProperties(true, namespace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining(namespace);
    }

    @ParameterizedTest
    @ValueSource(strings = {"supportflow:local:session", "supportflow:lab:session", "supportflow:test-123:session"})
    void preservesExplicitEnvironmentNamesWithoutNormalizingThemIntoAnotherNamespace(String namespace) {
        assertThat(new RedisSessionProperties(true, namespace).namespace()).isEqualTo(namespace);
    }
}
