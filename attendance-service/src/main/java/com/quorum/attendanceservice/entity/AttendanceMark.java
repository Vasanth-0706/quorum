package com.quorum.attendanceservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One mark of a student in a session. Rows are never updated: every new mark or correction is
 * a new row with the next {@code version} for that session + student, and the highest version
 * is the latest mark. {@code markId} is unique, so a redelivered mark is stored once.
 */
@Entity
@Table(name = "attendance_mark",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_mark_mark_id", columnNames = "mark_id"),
                @UniqueConstraint(name = "uk_mark_session_student_version",
                        columnNames = {"session_id", "student_id", "version"})},
        indexes = @Index(name = "idx_mark_student", columnList = "student_id"))
@Getter
@NoArgsConstructor
public class AttendanceMark {

    public enum Source { RFID, MANUAL, FILE, CORRECTION }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mark_id", nullable = false, length = 100)
    private String markId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    /** 1, 2, 3 ... per session + student, in the order the marks arrived. */
    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private boolean present;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Source source;

    @Column(name = "marked_by", nullable = false, length = 64)
    private String markedBy;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    /** The Kafka eventId that carried this mark; null for a correction made here. */
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public AttendanceMark(String markId, Long sessionId, String studentId, int version, boolean present,
                          Source source, String markedBy, Instant markedAt, String eventId, Instant receivedAt) {
        this.markId = markId;
        this.sessionId = sessionId;
        this.studentId = studentId;
        this.version = version;
        this.present = present;
        this.source = source;
        this.markedBy = markedBy;
        this.markedAt = markedAt;
        this.eventId = eventId;
        this.receivedAt = receivedAt;
    }
}
