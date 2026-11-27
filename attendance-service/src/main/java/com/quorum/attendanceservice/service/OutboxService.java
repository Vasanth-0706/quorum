package com.quorum.attendanceservice.service;

import com.quorum.attendanceservice.entity.OutboxEvent;
import com.quorum.attendanceservice.repository.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Queues an event in outbox_event. Must run inside the caller's transaction. */
@Service
public class OutboxService {

    private final OutboxEventRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OutboxService(OutboxEventRepository repository, ObjectMapper objectMapper, Clock clock) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public OutboxEvent append(String topic, String key, String eventType, Object event) {
        String payload = objectMapper.writeValueAsString(event);
        return repository.save(new OutboxEvent(topic, key, eventType, payload, clock.instant()));
    }
}
