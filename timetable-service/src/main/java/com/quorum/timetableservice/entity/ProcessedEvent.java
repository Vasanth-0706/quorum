package com.quorum.timetableservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/** An eventId already applied. A redelivered event finds its row here and is skipped. */
@Entity
@Table(name = "processed_event")
@Getter
@NoArgsConstructor
public class ProcessedEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean fresh;

    public ProcessedEvent(String eventId, String eventType, Instant processedAt) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
        this.fresh = true;
    }

    @Override
    public String getId() {
        return eventId;
    }

    /** Always INSERT, so a concurrent duplicate fails on the primary key instead of merging. */
    @Override
    public boolean isNew() {
        return fresh;
    }
}
