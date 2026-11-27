package com.quorum.placementservice.controller;

import com.quorum.placementservice.dto.CreateDriveRequest;
import com.quorum.placementservice.dto.CreateRoundRequest;
import com.quorum.placementservice.dto.DriveDetailResponse;
import com.quorum.placementservice.dto.DriveResponse;
import com.quorum.placementservice.dto.RoundResponse;
import com.quorum.placementservice.service.DriveService;
import com.quorum.placementservice.service.RoundService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/drives")
public class DriveController {

    private final DriveService driveService;
    private final RoundService roundService;

    public DriveController(DriveService driveService, RoundService roundService) {
        this.driveService = driveService;
        this.roundService = roundService;
    }

    @PostMapping
    public ResponseEntity<DriveResponse> createDrive(@Valid @RequestBody CreateDriveRequest request) {
        DriveResponse created = driveService.create(request);
        return ResponseEntity.created(URI.create("/drives/" + created.id())).body(created);
    }

    /** Newest first. */
    @GetMapping
    public List<DriveResponse> listDrives() {
        return driveService.findAll();
    }

    @GetMapping("/{id}")
    public DriveDetailResponse getDrive(@PathVariable Long id) {
        return driveService.findDetail(id);
    }

    @PostMapping("/{driveId}/rounds")
    public ResponseEntity<RoundResponse> createRound(@PathVariable Long driveId,
                                                     @Valid @RequestBody CreateRoundRequest request) {
        RoundResponse created = roundService.create(driveId, request);
        return ResponseEntity.created(URI.create("/rounds/" + created.id() + "/sessions")).body(created);
    }

    @GetMapping("/{driveId}/rounds")
    public List<RoundResponse> listRounds(@PathVariable Long driveId) {
        return roundService.list(driveId);
    }
}
