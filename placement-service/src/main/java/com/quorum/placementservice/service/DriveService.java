package com.quorum.placementservice.service;


import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.repository.DriveRepository;
import org.springframework.stereotype.Service;

@Service
public class DriveService {

    private final DriveRepository driveRepository;

    public DriveService(DriveRepository driveRepository) {
        this.driveRepository = driveRepository;
    }

    public Drive create(Drive drive) {

        return driveRepository.save(drive);

    }
}
