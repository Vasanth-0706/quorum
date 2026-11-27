package com.quorum.timetableservice.service;

import com.quorum.timetableservice.entity.OutboxEvent;
import com.quorum.timetableservice.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends unpublished outbox rows to Kafka in id order. Each send is awaited;
 * the batch stops at the first failure so no later event overtakes it.
 * Delivery is at-least-once: a row can be sent again if marking it fails,
 * which consumers absorb by de-duplicating on eventId.
 * Assumes a single running instance of timetable-service.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxEventRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;

    public OutboxRelay(OutboxEventRepository repository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       Clock clock,
                       @Value("${quorum.outbox.batch-size:100}") int batchSize,
                       @Value("${quorum.outbox.send-timeout:10s}") Duration sendTimeout) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
    }

    @Scheduled(fixedDelayString = "${quorum.outbox.relay-interval:500ms}")
    public void relay() {
        publishBatch();
    }

    /** @return how many rows were published in this pass */
    public int publishBatch() {
        List<OutboxEvent> batch = repository.findByPublishedAtIsNullOrderByIdAsc(Limit.of(batchSize));
        int published = 0;
        for (OutboxEvent event : batch) {
            String error = send(event);
            if (error != null) {
                event.markFailed(error);
                repository.save(event);
                log.warn("Outbox row {} ({}) not sent, attempt {}: {}. Stopping this batch.",
                        event.getId(), event.getEventType(), event.getAttempts(), error);
                break;
            }
            event.markPublished(clock.instant());
            repository.save(event);
            published++;
        }
        return published;
    }

    /** @return null on success, otherwise a description of the failure */
    private String send(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getAggregateKey(), event.getPayload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Interrupted while waiting for Kafka";
        } catch (TimeoutException e) {
            return "No answer from Kafka within " + sendTimeout.toSeconds() + " s";
        } catch (ExecutionException e) {
            return describe(e.getCause() != null ? e.getCause() : e);
        } catch (RuntimeException e) {
            return describe(e);
        }
    }

    private static String describe(Throwable t) {
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
