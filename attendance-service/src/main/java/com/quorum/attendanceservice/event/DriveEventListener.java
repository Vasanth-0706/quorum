package com.quorum.attendanceservice.event;

import com.quorum.attendanceservice.config.Topics;
import com.quorum.attendanceservice.service.DriveEventHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Reads drive-events as plain JSON. Messages that fail to deserialize arrive here
 * already diverted by the ErrorHandlingDeserializer and go to drive-events.DLT.
 */
@Component
public class DriveEventListener {

    private final DriveEventHandler handler;

    public DriveEventListener(DriveEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = Topics.DRIVE_EVENTS)
    public void onMessage(JsonNode event) {
        handler.handle(event);
    }
}
