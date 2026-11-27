package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * An ATTENDANCE_UPLOADED for a session this service has not heard of yet (drive-events and
 * import-events are separate topics, so the upload can overtake the session). It is applied, and
 * deleted, as soon as the session arrives.
 */
@Entity
@Table(name = "pending_file_upload", indexes = @Index(name = "idx_pending_session", columnList = "session_id"))
@Getter
@NoArgsConstructor
public class PendingFileUpload {

    @Id
    @Column(name = "event_id", length = 64)
    private String eventId;

    @Column(name = "upload_id", nullable = false, length = 64)
    private String uploadId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** Comma-separated, already validated 10-digit IDs. */
    @Column(name = "student_ids", nullable = false, length = 1_000_000)
    private String studentIds;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    public PendingFileUpload(String eventId, String uploadId, Long sessionId, String studentIds, Instant receivedAt) {
        this.eventId = eventId;
        this.uploadId = uploadId;
        this.sessionId = sessionId;
        this.studentIds = studentIds;
        this.receivedAt = receivedAt;
    }
}
