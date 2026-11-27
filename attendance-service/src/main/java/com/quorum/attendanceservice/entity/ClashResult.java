package com.quorum.attendanceservice.entity;

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
import java.time.LocalTime;

/**
 * The latest CLASHES_COMPUTED header for a roundId + date. Its clash list is in clash_entry and
 * is replaced, never merged, by every new result. No row means no clashes.
 */
@Entity
@Table(name = "clash_result", uniqueConstraints = @UniqueConstraint(name = "uk_clash_result_round_date",
        columnNames = {"round_id", "clash_date"}))
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

    /** Null in an empty replacement (the round has no session on that date any more). */
    @Column(name = "window_start")
    private LocalTime windowStart;

    @Column(name = "window_end")
    private LocalTime windowEnd;

    @Column(length = 32)
    private String semester;

    @Column(name = "clash_count", nullable = false)
    private int clashCount;

    @Column(name = "last_event_id", nullable = false, length = 64)
    private String lastEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
