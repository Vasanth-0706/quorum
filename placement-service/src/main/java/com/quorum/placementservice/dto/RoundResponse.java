package com.quorum.placementservice.dto;

import com.quorum.placementservice.entity.Round;

import java.util.List;

public record RoundResponse(
        Long id,
        Long driveId,
        String name,
        int sequence,
        List<SessionResponse> sessions) {

    public static RoundResponse from(Round r, List<SessionResponse> sessions) {
        return new RoundResponse(r.getId(), r.getDrive().getId(), r.getName(), r.getSequence(), sessions);
    }
}
