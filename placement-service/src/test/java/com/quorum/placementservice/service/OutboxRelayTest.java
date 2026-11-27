package com.quorum.placementservice.service;

import com.quorum.placementservice.entity.OutboxEvent;
import com.quorum.placementservice.repository.OutboxEventRepository;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-11-27T10:00:00Z");

    private OutboxEventRepository repository;
    private KafkaTemplate<String, String> kafka;
    private OutboxRelay relay;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        repository = mock(OutboxEventRepository.class);
        kafka = mock(KafkaTemplate.class);
        relay = new OutboxRelay(repository, kafka, Clock.fixed(NOW, ZoneOffset.UTC), 100, Duration.ofSeconds(10));
    }

    private static OutboxEvent row(long id, String key) {
        OutboxEvent e = new OutboxEvent("drive-events", key, "SESSION_SCHEDULED", "{\"n\":" + id + "}",
                NOW.minusSeconds(5));
        e.setId(id);
        return e;
    }

    private void pending(OutboxEvent... rows) {
        when(repository.findByPublishedAtIsNullOrderByIdAsc(any(Limit.class))).thenReturn(List.of(rows));
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<SendResult<String, String>> ok() {
        return CompletableFuture.completedFuture(mock(SendResult.class));
    }

    @Test
    void successMarksEveryRowPublishedInIdOrder() {
        OutboxEvent a = row(1, "5");
        OutboxEvent b = row(2, "5");
        pending(a, b);
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(ok());

        assertThat(relay.publishBatch()).isEqualTo(2);

        var order = inOrder(kafka);
        order.verify(kafka).send("drive-events", "5", "{\"n\":1}");
        order.verify(kafka).send("drive-events", "5", "{\"n\":2}");
        assertThat(a.getPublishedAt()).isEqualTo(NOW);
        assertThat(b.getPublishedAt()).isEqualTo(NOW);
        assertThat(a.getAttempts()).isEqualTo(1);
        assertThat(a.getLastError()).isNull();
        verify(repository, times(2)).save(any(OutboxEvent.class));
    }

    @Test
    void failureKeepsRowUnpublishedAndStopsTheBatch() {
        OutboxEvent a = row(1, "5");
        OutboxEvent b = row(2, "5");
        OutboxEvent c = row(3, "6");
        pending(a, b, c);
        when(kafka.send("drive-events", "5", "{\"n\":1}")).thenReturn(ok());
        when(kafka.send("drive-events", "5", "{\"n\":2}"))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("broker down")));

        assertThat(relay.publishBatch()).isEqualTo(1);

        assertThat(a.getPublishedAt()).isEqualTo(NOW);
        assertThat(b.getPublishedAt()).isNull();
        assertThat(b.getAttempts()).isEqualTo(1);
        assertThat(b.getLastError()).contains("broker down");
        assertThat(c.getAttempts()).isZero();
        verify(kafka, never()).send(eq("drive-events"), eq("6"), anyString());
        verify(repository).save(b);
        verify(repository, never()).save(c);
    }

    @Test
    void synchronousSendErrorIsRecordedTheSameWay() {
        OutboxEvent a = row(1, "5");
        pending(a);
        when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new KafkaException("metadata timeout"));

        assertThat(relay.publishBatch()).isZero();

        assertThat(a.getPublishedAt()).isNull();
        assertThat(a.getAttempts()).isEqualTo(1);
        assertThat(a.getLastError()).contains("metadata timeout");
    }

    @Test
    void retryAfterFailureCountsAttemptsAndClearsError() {
        OutboxEvent a = row(1, "5");
        pending(a);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("down")))
                .thenReturn(ok());

        relay.publishBatch();
        relay.publishBatch();

        assertThat(a.getAttempts()).isEqualTo(2);
        assertThat(a.getPublishedAt()).isEqualTo(NOW);
        assertThat(a.getLastError()).isNull();
    }

    @Test
    void longErrorsAreTruncated() {
        OutboxEvent a = row(1, "5");
        pending(a);
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new KafkaException("x".repeat(5000))));

        relay.publishBatch();

        assertThat(a.getLastError()).hasSize(1000);
    }

    @Test
    void emptyOutboxSendsNothing() {
        pending();
        assertThat(relay.publishBatch()).isZero();
        verify(kafka, never()).send(anyString(), anyString(), anyString());
    }
}
