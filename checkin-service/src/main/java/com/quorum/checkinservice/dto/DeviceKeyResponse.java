package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.Device;

/** The plain API key, shown ONLY in this answer. Put it in the reader's secrets.h as DEVICE_KEY. */
public record DeviceKeyResponse(String deviceId, String room, boolean active, String apiKey, String note) {

    public static DeviceKeyResponse from(Device d, String apiKey) {
        return new DeviceKeyResponse(d.getDeviceId(), d.getRoom(), d.isActive(), apiKey,
                "Store this key now; it is not shown again. Send it as the X-Device-Key header.");
    }
}
