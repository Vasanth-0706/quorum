package com.quorum.attendanceservice.listener;

import com.quorum.attendanceservice.event.DriveScheduledEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class DriveEventListener {

    @KafkaListener(topics = "drive-events")
    public void onDriveScheduled(DriveScheduledEvent event) {
        System.out.println("Received: " + event);
    }
}