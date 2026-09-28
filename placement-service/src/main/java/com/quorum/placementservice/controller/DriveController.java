package com.quorum.placementservice.controller;

import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.service.DriveService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/drives")
public class DriveController {
    private final DriveService driveService;

    public DriveController(DriveService driveService) {
        this.driveService = driveService;
    }


    @PostMapping
    public ResponseEntity<Drive> createDrive(@RequestBody Drive drive) {
        return ResponseEntity.status(HttpStatus.CREATED).body(driveService.create(drive));
    }


}
