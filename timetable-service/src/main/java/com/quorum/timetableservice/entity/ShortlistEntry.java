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

/** One shortlisted student of a round. The whole set is replaced on every upload. */
@Entity
@Table(name = "shortlist_entry",
        uniqueConstraints = @UniqueConstraint(name = "uk_shortlist_round_student", columnNames = {"round_id", "student_id"}))
@Getter
@NoArgsConstructor
public class ShortlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    @Column(name = "upload_id", nullable = false, length = 64)
    private String uploadId;

    public ShortlistEntry(Long roundId, String studentId, String uploadId) {
        this.roundId = roundId;
        this.studentId = studentId;
        this.uploadId = uploadId;
    }
}
