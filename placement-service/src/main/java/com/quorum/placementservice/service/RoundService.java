package com.quorum.placementservice.service;

import com.quorum.placementservice.config.Topics;
import com.quorum.placementservice.dto.CreateRoundRequest;
import com.quorum.placementservice.dto.RoundResponse;
import com.quorum.placementservice.dto.SessionResponse;
import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.entity.Round;
import com.quorum.placementservice.event.DriveEventFactory;
import com.quorum.placementservice.event.EventTypes;
import com.quorum.placementservice.repository.DriveRepository;
import com.quorum.placementservice.repository.RoundRepository;
import com.quorum.placementservice.repository.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RoundService {

    private final DriveRepository driveRepository;
    private final RoundRepository roundRepository;
    private final SessionRepository sessionRepository;
    private final OutboxService outbox;
    private final DriveEventFactory events;

    public RoundService(DriveRepository driveRepository, RoundRepository roundRepository,
                        SessionRepository sessionRepository, OutboxService outbox, DriveEventFactory events) {
        this.driveRepository = driveRepository;
        this.roundRepository = roundRepository;
        this.sessionRepository = sessionRepository;
        this.outbox = outbox;
        this.events = events;
    }

    /** Sequence numbers are unique within a drive (409 otherwise). */
    @Transactional
    public RoundResponse create(Long driveId, CreateRoundRequest request) {
        Drive drive = driveRepository.findById(driveId)
                .orElseThrow(() -> new NotFoundException("Drive " + driveId + " not found"));
        if (roundRepository.existsByDriveIdAndSequence(driveId, request.sequence())) {
            throw new ConflictException("Drive " + driveId + " already has a round with sequence " + request.sequence());
        }
        Round round = new Round();
        round.setDrive(drive);
        round.setName(request.name().trim());
        round.setSequence(request.sequence());
        Round saved = roundRepository.save(round);

        outbox.append(Topics.DRIVE_EVENTS, String.valueOf(driveId), EventTypes.ROUND_CREATED,
                events.roundCreated(saved));
        return RoundResponse.from(saved, List.of());
    }

    @Transactional(readOnly = true)
    public List<RoundResponse> list(Long driveId) {
        if (!driveRepository.existsById(driveId)) {
            throw new NotFoundException("Drive " + driveId + " not found");
        }
        return listWithSessions(driveId);
    }

    /** Rounds by sequence, each with its sessions by date and start time. */
    @Transactional(readOnly = true)
    public List<RoundResponse> listWithSessions(Long driveId) {
        List<Round> rounds = roundRepository.findByDriveIdOrderBySequenceAscIdAsc(driveId);
        if (rounds.isEmpty()) {
            return List.of();
        }
        Map<Long, List<SessionResponse>> byRound = sessionRepository
                .findByRoundIdInOrderByDateAscStartTimeAscIdAsc(rounds.stream().map(Round::getId).toList())
                .stream()
                .collect(Collectors.groupingBy(s -> s.getRound().getId(),
                        Collectors.mapping(SessionResponse::from, Collectors.toList())));
        return rounds.stream()
                .map(r -> RoundResponse.from(r, byRound.getOrDefault(r.getId(), List.of())))
                .toList();
    }

    Round require(Long roundId) {
        return roundRepository.findById(roundId)
                .orElseThrow(() -> new NotFoundException("Round " + roundId + " not found"));
    }
}
