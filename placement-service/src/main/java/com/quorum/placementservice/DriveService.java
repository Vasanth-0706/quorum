package com.quorum.placementservice;


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
