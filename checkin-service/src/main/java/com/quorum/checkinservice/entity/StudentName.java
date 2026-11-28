package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The latest known name of a student, from any shortlist or attendance upload that carried a
 * non-empty name. The newest name wins; a missing or blank name never clears one.
 */
@Entity
@Table(name = "student_name")
@Getter
@Setter
@NoArgsConstructor
public class StudentName {

    @Id
    @Column(name = "student_id", length = 10)
    private String studentId;

    @Column(nullable = false, length = RosterEntry.NAME_MAX_LENGTH)
    private String name;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public StudentName(String studentId, String name, Instant updatedAt) {
        this.studentId = studentId;
        this.name = name;
        this.updatedAt = updatedAt;
    }
}
