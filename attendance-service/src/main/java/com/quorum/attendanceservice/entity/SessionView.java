package com.quorum.attendanceservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Local copy of placement-service sessions, built from drive-events. Upserted by sessionId. */
@Entity
@Table(name = "session_view")
@Getter
@Setter
@NoArgsConstructor
public class SessionView {

    @Id
    @Column(name = "session_id")
    private Long sessionId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "drive_id", nullable = false)
    private Long driveId;

    @Column(name = "session_date", nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(length = 60)
    private String room;

    /** SCHEDULED or CANCELLED. */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "last_event_id", nullable = false, length = 64)
    private String lastEventId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
