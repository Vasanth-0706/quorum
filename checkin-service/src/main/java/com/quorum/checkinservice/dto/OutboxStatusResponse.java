package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.OutboxEvent;

import java.time.Instant;
import java.util.List;

/** GET /outbox: how many rows still wait for Kafka, and the latest rows newest first. */
public record OutboxStatusResponse(long pending, List<Row> latest) {

    public record Row(Long id, String topic, String aggregateKey, String eventType, Instant createdAt,
                      Instant publishedAt, int attempts, String lastError) {

        public static Row from(OutboxEvent e) {
            return new Row(e.getId(), e.getTopic(), e.getAggregateKey(), e.getEventType(), e.getCreatedAt(),
                    e.getPublishedAt(), e.getAttempts(), e.getLastError());
        }
    }
}
