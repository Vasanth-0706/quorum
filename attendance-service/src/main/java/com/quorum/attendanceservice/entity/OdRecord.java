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
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The current OD state of one class slot of one student on one date. Unique on
 * (studentId, date, sectionCode, slotStart). Every change bumps {@code version} and
 * writes an od_record_history row, so nothing is lost when it is revoked or re-granted.
 */
@Entity
@Table(name = "od_record",
        uniqueConstraints = @UniqueConstraint(name = "uk_od_student_date_section_slot",
                columnNames = {"student_id", "od_date", "section_code", "slot_start"}),
        indexes = {
                @Index(name = "idx_od_date", columnList = "od_date, status"),
                @Index(name = "idx_od_session", columnList = "session_id")})
@Getter
@Setter
@NoArgsConstructor
public class OdRecord {

    public enum Status { GRANTED, REVOKED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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

    /** The attended session the grant is based on (kept on a revoke). */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(nullable = false, length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status;

    @Column(nullable = false)
    private int version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
