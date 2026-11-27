package com.quorum.checkinservice.dto;

import com.quorum.checkinservice.entity.UnknownTap;

import java.time.Instant;

public record UnknownTapResponse(String cardUid, Instant firstSeenAt, Instant lastSeenAt, int tapCount,
                                 String lastDeviceId, String lastRoom) {

    public static UnknownTapResponse from(UnknownTap t) {
        return new UnknownTapResponse(t.getCardUid(), t.getFirstSeenAt(), t.getLastSeenAt(), t.getTapCount(),
                t.getLastDeviceId(), t.getLastRoom());
    }
}
