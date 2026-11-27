package com.quorum.timetableservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;

/** The last CLASHES_COMPUTED queued in the outbox for a roundId + date, kept to skip unchanged results. */
@Entity
@Table(name = "clash_result",
        uniqueConstraints = @UniqueConstraint(name = "uk_clash_round_date", columnNames = {"round_id", "clash_date"}))
@Getter
@Setter
@NoArgsConstructor
public class ClashResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "clash_date", nullable = false)
    private LocalDate date;

    /** SHA-256 of semester, window and the sorted clash list. */
    @Column(name = "result_hash", nullable = false, length = 64)
    private String resultHash;

    @Column(name = "clash_count", nullable = false)
    private int clashCount;

    @Column(name = "last_event_id", nullable = false, length = 64)
    private String lastEventId;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;
}
