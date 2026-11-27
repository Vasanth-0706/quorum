package com.quorum.timetableservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** A semester with a stored timetable. Exactly one is active: the latest one applied. */
@Entity
@Table(name = "semester")
@Getter
@Setter
@NoArgsConstructor
public class Semester {

    @Id
    @Column(length = 40)
    private String code;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "upload_id", nullable = false, length = 64)
    private String uploadId;

    @Column(name = "slot_count", nullable = false)
    private int slotCount;

    @Column(name = "enrolment_count", nullable = false)
    private int enrolmentCount;

    @Column(name = "applied_at", nullable = false)
    private Instant appliedAt;
}
