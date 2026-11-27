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

import java.time.Instant;

/** A buffered part of an upload, kept until every part has arrived, then deleted. */
@Entity
@Table(name = "timetable_part",
        uniqueConstraints = @UniqueConstraint(name = "uk_part_upload_part", columnNames = {"upload_id", "part"}))
@Getter
@NoArgsConstructor
public class TimetablePart {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "upload_id", nullable = false, length = 64)
    private String uploadId;

    @Column(nullable = false)
    private int part;

    /** JSON array exactly as received (empty except in part 1). */
    @Column(name = "slots_json", nullable = false, length = 16_000_000)
    private String slotsJson;

    @Column(name = "enrolments_json", nullable = false, length = 16_000_000)
    private String enrolmentsJson;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public TimetablePart(String uploadId, int part, String slotsJson, String enrolmentsJson, Instant receivedAt) {
        this.uploadId = uploadId;
        this.part = part;
        this.slotsJson = slotsJson;
        this.enrolmentsJson = enrolmentsJson;
        this.receivedAt = receivedAt;
    }
}
