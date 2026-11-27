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
 * An RFID reader fixed in one room. Only the BCrypt hash of its API key is stored; the plain key
 * ({@code <deviceId>.<secret>}) is shown once, when the device is registered.
 */
@Entity
@Table(name = "device")
@Getter
@Setter
@NoArgsConstructor
public class Device {

    @Id
    @Column(name = "device_id", length = 40)
    private String deviceId;

    @Column(nullable = false, length = 60)
    private String room;

    /** BCrypt hash of the secret part of the key. */
    @Column(name = "api_key_hash", nullable = false, length = 100)
    private String apiKeyHash;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    public Device(String deviceId, String room, String apiKeyHash, String createdBy, Instant createdAt) {
        this.deviceId = deviceId;
        this.room = room;
        this.apiKeyHash = apiKeyHash;
        this.active = true;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }
}
