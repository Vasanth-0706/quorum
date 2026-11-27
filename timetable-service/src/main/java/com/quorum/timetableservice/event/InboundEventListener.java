package com.quorum.timetableservice.event;

import com.quorum.timetableservice.config.Topics;
import com.quorum.timetableservice.service.InboundEventHandler;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * One listener, one thread, for both topics: every change to shortlists, sessions and
 * timetables is applied one at a time, so a recomputation always sees the others' committed
 * results. Messages that fail to deserialize never reach this method; they go to the DLT.
 */
@Component
public class InboundEventListener {

    private final InboundEventHandler handler;

    public InboundEventListener(InboundEventHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = {Topics.IMPORT_EVENTS, Topics.DRIVE_EVENTS}, concurrency = "1")
    public void onMessage(ConsumerRecord<String, JsonNode> record) {
        handler.handle(record.topic(), record.value());
    }
}
