package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One shortlisted student of a round, from SHORTLIST_UPLOADED on import-events. A new upload for a
 * round replaces that round's whole roster (the last upload applied wins, the same rule as
 * timetable-service's shortlist_entry).
 */
@Entity
@Table(name = "round_roster", uniqueConstraints = @UniqueConstraint(name = "uk_round_roster_round_student",
        columnNames = {"round_id", "student_id"}))
@Getter
@NoArgsConstructor
public class RosterEntry {

    public static final int NAME_MAX_LENGTH = 120;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    /** The name in the shortlist file, or null when it had none. */
    @Column(name = "student_name", length = NAME_MAX_LENGTH)
    private String studentName;

    @Column(name = "upload_id", nullable = false, length = 64)
    private String uploadId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RosterEntry(Long roundId, String studentId, String studentName, String uploadId, Instant updatedAt) {
        this.roundId = roundId;
        this.studentId = studentId;
        this.studentName = studentName;
        this.uploadId = uploadId;
        this.updatedAt = updatedAt;
    }
}
