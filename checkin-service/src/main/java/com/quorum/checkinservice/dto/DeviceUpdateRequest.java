package com.quorum.checkinservice.dto;

import jakarta.validation.constraints.Size;

/** PATCH /devices/{deviceId}: either field may be left out. */
public record DeviceUpdateRequest(Boolean active, @Size(max = 60) String room) {
}
