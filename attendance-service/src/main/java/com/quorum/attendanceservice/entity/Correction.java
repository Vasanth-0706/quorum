package com.quorum.attendanceservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * REQ-F12: who changed a student's mark in a session, when, from what, to what and why.
 * Never updated. The new value is also stored as the latest attendance_mark (source CORRECTION).
 */
@Entity
@Table(name = "correction", indexes = @Index(name = "idx_correction_session", columnList = "session_id"))
@Getter
@NoArgsConstructor
public class Correction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    /** The latest mark before this correction; null when the student had no mark at all. */
    @Column(name = "old_present")
    private Boolean oldPresent;

    @Column(name = "new_present", nullable = false)
    private boolean newPresent;

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "actor_id", nullable = false, length = 64)
    private String actorId;

    @Column(name = "actor_role", nullable = false, length = 16)
    private String actorRole;

    @Column(name = "corrected_at", nullable = false)
    private Instant correctedAt;

    /** The attendance_mark row this correction created. */
    @Column(name = "mark_id", nullable = false, length = 100)
    private String markId;

    @Column(name = "mark_version", nullable = false)
    private int markVersion;

    public Correction(Long sessionId, String studentId, Boolean oldPresent, boolean newPresent, String reason,
                      String actorId, String actorRole, Instant correctedAt, String markId, int markVersion) {
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.oldPresent = oldPresent;
        this.newPresent = newPresent;
        this.reason = reason;
        this.actorId = actorId;
        this.actorRole = actorRole;
        this.correctedAt = correctedAt;
        this.markId = markId;
        this.markVersion = markVersion;
    }
}
