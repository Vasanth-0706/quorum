package com.quorum.timetableservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** One timetable upload from import-service, assembled from totalParts parts. */
@Entity
@Table(name = "timetable_upload")
@Getter
@Setter
@NoArgsConstructor
public class TimetableUpload {

    public enum Status { BUFFERING, APPLIED }

    @Id
    @Column(name = "upload_id", length = 64)
    private String uploadId;

    @Column(nullable = false, length = 40)
    private String semester;

    @Column(name = "total_parts", nullable = false)
    private int totalParts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    /** As sent by import-service (local time, no offset). */
    @Column(name = "uploaded_at", length = 40)
    private String uploadedAt;

    @Column(name = "first_part_at", nullable = false)
    private Instant firstPartAt;

    @Column(name = "applied_at")
    private Instant appliedAt;

    public TimetableUpload(String uploadId, String semester, int totalParts, String uploadedAt, Instant now) {
        this.uploadId = uploadId;
        this.semester = semester;
        this.totalParts = totalParts;
        this.uploadedAt = uploadedAt;
        this.status = Status.BUFFERING;
        this.firstPartAt = now;
    }
}
