package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.Device;

import java.time.Instant;

/** A device without its key. */
public record DeviceResponse(String deviceId, String room, boolean active, String createdBy, Instant createdAt,
                             Instant lastSeenAt) {

    public static DeviceResponse from(Device d) {
        return new DeviceResponse(d.getDeviceId(), d.getRoom(), d.isActive(), d.getCreatedBy(), d.getCreatedAt(),
                d.getLastSeenAt());
    }
}
