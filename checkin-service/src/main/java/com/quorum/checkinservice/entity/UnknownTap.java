package com.quorum.checkinservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A card UID that was tapped but is not enrolled, so a coordinator can enrol it. One row per UID;
 * enrolling the card deletes it.
 */
@Entity
@Table(name = "unknown_tap")
@Getter
@NoArgsConstructor
public class UnknownTap {

    @Id
    @Column(name = "card_uid", length = 20)
    private String cardUid;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "tap_count", nullable = false)
    private int tapCount;

    @Column(name = "last_device_id", nullable = false, length = 40)
    private String lastDeviceId;

    @Column(name = "last_room", nullable = false, length = 60)
    private String lastRoom;

    public UnknownTap(String cardUid, Instant at, String deviceId, String room) {
        this.cardUid = cardUid;
        this.firstSeenAt = at;
        this.lastSeenAt = at;
        this.tapCount = 1;
        this.lastDeviceId = deviceId;
        this.lastRoom = room;
    }

    public void seenAgain(Instant at, String deviceId, String room) {
        this.tapCount++;
        if (at.isAfter(lastSeenAt)) {
            this.lastSeenAt = at;
        }
        this.lastDeviceId = deviceId;
        this.lastRoom = room;
    }
}
