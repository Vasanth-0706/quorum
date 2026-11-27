package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * An RFID card enrolled to a student. cardUid is upper-case hex without separators (4, 7 or 10
 * bytes). One student may hold several cards (e.g. a replacement); a card belongs to one student.
 */
@Entity
@Table(name = "card", indexes = @Index(name = "idx_card_student", columnList = "student_id"))
@Getter
@NoArgsConstructor
public class Card implements Persistable<String> {

    @Id
    @Column(name = "card_uid", length = 20)
    private String cardUid;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    @Column(name = "enrolled_by", nullable = false, length = 64)
    private String enrolledBy;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Transient
    private boolean fresh;

    public Card(String cardUid, String studentId, String enrolledBy, Instant enrolledAt) {
        this.cardUid = cardUid;
        this.studentId = studentId;
        this.enrolledBy = enrolledBy;
        this.enrolledAt = enrolledAt;
        this.fresh = true;
    }

    @Override
    public String getId() {
        return cardUid;
    }

    /** Always INSERT, so enrolling the same UID twice at once fails on the primary key. */
    @Override
    public boolean isNew() {
        return fresh;
    }
}
