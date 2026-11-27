package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One attendance mark of a student in a session. Rows are never updated: every new mark is a new
 * row with the next {@code version} for that session + student; the highest version is the latest.
 * Each mark is published once as ATTENDANCE_MARKED with its markId.
 */
@Entity
@Table(name = "mark", uniqueConstraints = {
        @UniqueConstraint(name = "uk_mark_mark_id", columnNames = "mark_id"),
        @UniqueConstraint(name = "uk_mark_session_student_version",
                columnNames = {"session_id", "student_id", "version"})})
@Getter
@NoArgsConstructor
public class Mark {

    public enum Source { RFID, MANUAL, FILE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** UUID string. */
    @Column(name = "mark_id", nullable = false, length = 36)
    private String markId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private boolean present;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Source source;

    /** deviceId for RFID, X-User-Id for MANUAL, {@code import-service} for FILE. */
    @Column(name = "marked_by", nullable = false, length = 64)
    private String markedBy;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Mark(String markId, Long sessionId, Long roundId, String studentId, int version, boolean present,
                Source source, String markedBy, Instant markedAt, Instant createdAt) {
        this.markId = markId;
        this.sessionId = sessionId;
        this.roundId = roundId;
        this.studentId = studentId;
        this.version = version;
        this.present = present;
        this.source = source;
        this.markedBy = markedBy;
        this.markedAt = markedAt;
        this.createdAt = createdAt;
    }
}
