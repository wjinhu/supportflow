package dev.supportflow.ai.springai;

import dev.supportflow.assistant.knowledge.KnowledgeAnswer;
import dev.supportflow.assistant.knowledge.KnowledgeMatch;
import dev.supportflow.assistant.knowledge.KnowledgeService;
import dev.supportflow.assistant.knowledge.PublicReferenceCatalog;
import dev.supportflow.chat.application.model.KnowledgeEvidence;
import dev.supportflow.chat.application.model.KnowledgeSource;
import dev.supportflow.chat.application.model.KnowledgeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.rag.Query;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class SpringAiKnowledgeAdapterTest {
    private static final String QUESTION = "安卓耳机如何连接";
    private final SpringAiTestProvider provider = new SpringAiTestProvider();
    private final KnowledgeService knowledge = mock(KnowledgeService.class);
    private final KnowledgeMatch publicMatch = new PublicReferenceCatalog().find(102, "ref-android-bluetooth-pair").orElseThrow();
    private final SpringAiKnowledgeAdapter adapter;

    SpringAiKnowledgeAdapterTest() {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("knowledge", knowledge);
        adapter = new SpringAiKnowledgeAdapter(provider.client, provider.transport, provider.properties,
                provider.json, beans.getBeanProvider(KnowledgeService.class));
        when(knowledge.searchEvidence(eq(102L), anyString())).thenReturn(answer(publicMatch));
    }

    @Test void actualChatClientSelectsOnlyCurrentPublicDocumentsAndRetainsMetadata() {
        provider.selection("{\"sourceIds\":[\"" + publicMatch.sourceId() + "\"]}");
        assertThat(adapter.select(102, QUESTION, List.of(publicMatch))).contains(List.of(publicMatch.sourceId()));
        assertThat(provider.requests).hasSize(1);
        assertThat(provider.requests.getFirst()).contains(publicMatch.version(), "contentHash", "CURRENT_VISIBLE");
        assertThat(provider.json.readTree(provider.requests.getFirst()).path("tools").isMissingNode()).isTrue();
    }

    @Test void modularRetrieverBindsShopOnServerAndReReadsCurrentEvidenceEveryTime() {
        var retriever = adapter.forShop(102);
        var first = retriever.retrieve(new Query(QUESTION));
        assertThat(first).hasSize(1);
        assertThat(first.getFirst().getMetadata()).containsEntry("shopId", 102L).containsEntry("version", publicMatch.version())
                .containsEntry("sourceId", publicMatch.sourceId()).containsEntry("title", publicMatch.title());
        assertThat(first.getFirst().getMetadata().get("contentHash").toString()).hasSize(64);
        when(knowledge.searchEvidence(eq(102L), anyString())).thenReturn(new KnowledgeAnswer("无资料", new KnowledgeEvidence(KnowledgeStatus.NO_MATCH, List.of())));
        assertThat(retriever.retrieve(new Query(QUESTION))).isEmpty();
        assertThat(provider.requests).isEmpty();
        verify(knowledge, times(2)).searchEvidence(102, QUESTION);
    }

    @Test void privateMixedForgedAndWrongShopEvidenceNeverReachTheModel() {
        var privateMatch = new KnowledgeMatch("kb-private", "v1", "商品说明", "PRIVATE-CONTENT", 1, List.of());
        var forged = new KnowledgeMatch(publicMatch.sourceId(), publicMatch.version(), publicMatch.title(), "PRIVATE-CONTENT", 1, List.of());
        for (var matches : List.of(List.of(privateMatch), List.of(publicMatch, privateMatch), List.of(forged))) {
            assertThat(adapter.select(102, QUESTION, matches)).isEmpty();
        }
        assertThat(adapter.select(999, QUESTION, List.of(publicMatch))).isEmpty();
        assertThat(adapter.select(102, "查订单 SF10001", List.of(publicMatch))).isEmpty();
        assertThat(provider.requests).isEmpty();
        verifyNoInteractions(knowledge);
    }

    @Test void staleVersionOrContentCannotBeInjectedFromAnOldMatch() {
        var changed = new KnowledgeMatch(publicMatch.sourceId(), "v99", publicMatch.title(), "changed", 1, List.of());
        when(knowledge.searchEvidence(eq(102L), anyString())).thenReturn(answer(changed));
        assertThat(adapter.select(102, QUESTION, List.of(publicMatch))).isEmpty();
        assertThat(provider.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"sourceIds\":[\"kb-private\"]}", "{\"sourceIds\":[123]}",
            "{\"sourceIds\":null}", "{\"sourceIds\":[],\"buyerId\":200}", "{\"sourceIds\":[],\"sourceIds\":[\"bad\"]}"})
    void invalidStructuredOutputFallsBackWithoutRepairRequests(String content) {
        provider.selection(content);
        assertThat(adapter.select(102, QUESTION, List.of(publicMatch))).isEmpty();
        assertThat(provider.requests).hasSize(1);
    }

    @Test void providerFailureKeepsExistingLocalEvidenceAndDoesNotRetry() {
        provider.reply(503, "secret echoed fake-spring-ai-test-key");
        assertThat(adapter.select(102, QUESTION, List.of(publicMatch))).isEmpty();
        assertThat(provider.requests).hasSize(1);
    }

    private static KnowledgeAnswer answer(KnowledgeMatch match) {
        return new KnowledgeAnswer(match.content(), new KnowledgeEvidence(KnowledgeStatus.ANSWERED,
                List.of(new KnowledgeSource(match.sourceId(), match.version(), match.title(), match.content()))));
    }
}
