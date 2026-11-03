package dev.supportflow.chat.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "supportflow.human-support.idle-scan.enabled", havingValue = "true", matchIfMissing = true)
public class HumanSupportIdleScheduling {
    private static final Logger LOG = LoggerFactory.getLogger(HumanSupportIdleScheduling.class);
    private final HumanSupportService service;
    public HumanSupportIdleScheduling(HumanSupportService service) { this.service = service; }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void expire() {
        try { service.expireInactive(); }
        catch (RuntimeException error) { LOG.warn("Human support idle scan failed: {}", error.getClass().getSimpleName()); }
    }
}
