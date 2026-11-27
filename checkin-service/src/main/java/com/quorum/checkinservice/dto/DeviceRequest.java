package com.quorum.checkinservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** POST /devices. deviceId: letters, digits, '-' and '_' (it is the part of the key before the '.'). */
public record DeviceRequest(
        @NotNull @Pattern(regexp = "[A-Za-z0-9_-]{1,40}", message = "must be 1-40 letters, digits, '-' or '_'")
        String deviceId,
        @NotBlank @Size(max = 60) String room) {
}
