package com.quorum.checkinservice.event;

import com.quorum.checkinservice.config.Topics;
import com.quorum.checkinservice.service.InboundEventHandler;
import com.quorum.checkinservice.service.WriteLock;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * One listener, one thread, for drive-events and import-events. Changes are applied one at a time,
 * under the same {@link WriteLock} as taps and manual marks. Messages that fail to deserialize never
 * reach this method; they go to the DLT.
 */
@Component
public class InboundEventListener {

    private final InboundEventHandler handler;
    private final WriteLock lock;

    public InboundEventListener(InboundEventHandler handler, WriteLock lock) {
        this.handler = handler;
        this.lock = lock;
    }

    @KafkaListener(topics = {Topics.DRIVE_EVENTS, Topics.IMPORT_EVENTS}, concurrency = "1")
    public void onMessage(ConsumerRecord<String, JsonNode> record) {
        lock.call(() -> handler.handle(record.topic(), record.value()));
    }
}
