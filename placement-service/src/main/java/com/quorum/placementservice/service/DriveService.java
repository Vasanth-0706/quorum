package com.quorum.placementservice.service;

import com.quorum.placementservice.config.Topics;
import com.quorum.placementservice.dto.CreateDriveRequest;
import com.quorum.placementservice.dto.DriveDetailResponse;
import com.quorum.placementservice.dto.DriveResponse;
import com.quorum.placementservice.dto.RoundResponse;
import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.event.DriveEventFactory;
import com.quorum.placementservice.event.EventTypes;
import com.quorum.placementservice.repository.DriveRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DriveService {

    private final DriveRepository driveRepository;
    private final RoundService roundService;
    private final OutboxService outbox;
    private final DriveEventFactory events;

    public DriveService(DriveRepository driveRepository, RoundService roundService, OutboxService outbox,
                        DriveEventFactory events) {
        this.driveRepository = driveRepository;
        this.roundService = roundService;
        this.outbox = outbox;
        this.events = events;
    }

    /** Saves the drive and its DRIVE_SCHEDULED outbox row in one transaction. */
    @Transactional
    public DriveResponse create(CreateDriveRequest request) {
        Drive drive = new Drive();
        drive.setCompanyName(request.companyName().trim());
        drive.setDriveDate(request.driveDate());
        drive.setStartTime(request.startTime().withNano(0));
        drive.setEndTime(request.endTime().withNano(0));
        drive.setVenue(request.venue().trim());
        Drive saved = driveRepository.save(drive);

        outbox.append(Topics.DRIVE_EVENTS, String.valueOf(saved.getId()), EventTypes.DRIVE_SCHEDULED,
                events.driveScheduled(saved));
        return DriveResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<DriveResponse> findAll() {
        return driveRepository.findAll(Sort.by(Sort.Direction.DESC, "id")).stream()
                .map(DriveResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public DriveDetailResponse findDetail(Long id) {
        Drive drive = require(id);
        List<RoundResponse> rounds = roundService.listWithSessions(drive.getId());
        return DriveDetailResponse.from(drive, rounds);
    }

    Drive require(Long id) {
        return driveRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Drive " + id + " not found"));
    }
}
