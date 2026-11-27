package com.quorum.attendanceservice.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Every change that can move OD (a Kafka event or a correction) runs under this one lock,
 * taken OUTSIDE its transaction. Two recomputations of the same student then never read each
 * other's uncommitted state, e.g. a correction to absent racing a new clash list.
 * Like the outbox relay, it assumes a single running instance of attendance-service.
 */
@Component
public class WriteLock {

    private final ReentrantLock lock = new ReentrantLock(true);

    public <T> T call(Supplier<T> work) {
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }
}
