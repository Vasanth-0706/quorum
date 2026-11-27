package com.quorum.attendanceservice.entity;

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
import java.time.LocalDate;
import java.time.LocalTime;

/** An immutable snapshot of an od_record after one grant or revoke, with the event that announced it. */
@Entity
@Table(name = "od_record_history", uniqueConstraints = @UniqueConstraint(name = "uk_od_history_record_version",
        columnNames = {"od_record_id", "version"}))
@Getter
@NoArgsConstructor
public class OdRecordHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "od_record_id", nullable = false)
    private Long odRecordId;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OdRecord.Status status;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    @Column(name = "od_date", nullable = false)
    private LocalDate date;

    @Column(name = "section_code", nullable = false, length = 32)
    private String sectionCode;

    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;

    @Column(name = "slot_end", nullable = false)
    private LocalTime slotEnd;

    @Column(name = "course_name", length = 200)
    private String courseName;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(nullable = false, length = 300)
    private String reason;

    /** The OD_GRANTED / OD_REVOKED eventId queued in the outbox. */
    @Column(name = "event_id", nullable = false, length = 64)
    private String eventId;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    public OdRecordHistory(OdRecord r, String eventId, Instant recordedAt) {
        this.odRecordId = r.getId();
        this.version = r.getVersion();
        this.status = r.getStatus();
        this.studentId = r.getStudentId();
        this.date = r.getDate();
        this.sectionCode = r.getSectionCode();
        this.slotStart = r.getSlotStart();
        this.slotEnd = r.getSlotEnd();
        this.courseName = r.getCourseName();
        this.sessionId = r.getSessionId();
        this.roundId = r.getRoundId();
        this.reason = r.getReason();
        this.eventId = eventId;
        this.recordedAt = recordedAt;
    }
}
