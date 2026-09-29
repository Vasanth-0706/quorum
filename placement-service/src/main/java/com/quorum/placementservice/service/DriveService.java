package com.quorum.placementservice.service;


import com.quorum.placementservice.entity.Drive;
import com.quorum.placementservice.event.DriveScheduledEvent;
import com.quorum.placementservice.repository.DriveRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class DriveService {

    private final DriveRepository driveRepository;
    private final KafkaTemplate<String, DriveScheduledEvent> kafkaTemplate;

    private static final String TOPIC = "drive-events";


    public DriveService(DriveRepository driveRepository, KafkaTemplate<String, DriveScheduledEvent> kafkaTemplate) {

        this.driveRepository = driveRepository;
        this.kafkaTemplate = kafkaTemplate;

    }

    public Drive create(Drive drive) {

        Drive saved =  driveRepository.save(drive);
        DriveScheduledEvent event = new DriveScheduledEvent(  // 2. write the letter
                saved.getId(),
                saved.getCompanyName(),
                saved.getDriveDate(),
                saved.getStartTime(),
                saved.getEndTime(),
                saved.getVenue());
        kafkaTemplate.send(TOPIC, String.valueOf(saved.getId()), event);
        return saved;

    }
}
