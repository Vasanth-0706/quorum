package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An event waiting to be sent to Kafka. Written in the same transaction as the
 * entity change it describes; {@code OutboxRelay} publishes it afterwards.
 */
@Entity
@Table(name = "outbox_event", indexes = @Index(name = "idx_outbox_unpublished", columnList = "published_at, id"))
@Getter
@Setter
@NoArgsConstructor
public class OutboxEvent {

    static final int MAX_ERROR_LENGTH = 1000;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Kafka message key, the sessionId for checkin-events. */
    @Column(name = "aggregate_key", nullable = false, length = 64)
    private String aggregateKey;

    @Column(nullable = false, length = 200)
    private String topic;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    /** The compact JSON message exactly as it goes on the wire. */
    @Column(nullable = false, length = 1_000_000)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = MAX_ERROR_LENGTH)
    private String lastError;

    public OutboxEvent(String topic, String aggregateKey, String eventType, String payload, Instant createdAt) {
        this.topic = topic;
        this.aggregateKey = aggregateKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
    }

    public void markPublished(Instant when) {
        this.attempts++;
        this.publishedAt = when;
        this.lastError = null;
    }

    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null || error.length() <= MAX_ERROR_LENGTH
                ? error
                : error.substring(0, MAX_ERROR_LENGTH);
    }
}
