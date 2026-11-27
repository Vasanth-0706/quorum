package com.quorum.attendanceservice.event;

import com.quorum.attendanceservice.service.InboundEventHandler;
import com.quorum.attendanceservice.service.WriteLock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * One listener, one thread, for every consumed topic (the list is the {@code inboundTopics}
 * bean: drive-events, clash-events, checkin-events, and import-events while the TEMPORARY file
 * bridge is on). Changes are applied one at a time, under the same {@link WriteLock} as
 * corrections. Messages that fail to deserialize never reach this method; they go to the DLT.
 */
@Component
public class InboundEventListener {

    private final InboundEventHandler handler;
    private final WriteLock lock;

    public InboundEventListener(InboundEventHandler handler, WriteLock lock) {
        this.handler = handler;
        this.lock = lock;
    }

    @KafkaListener(topics = "#{@inboundTopics}", concurrency = "1")
    public void onMessage(ConsumerRecord<String, JsonNode> record) {
        lock.call(() -> handler.handle(record.topic(), record.value()));
    }
}
