package com.quorum.checkinservice.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Every write that stores a mark (a tap, a manual mark, a file upload, a session change) runs under
 * this one lock, taken OUTSIDE its transaction. Two taps of the same card a few milliseconds apart
 * then never both see "no mark yet", so the second one answers ALREADY_MARKED. Holding the lock is
 * a few milliseconds. Like the outbox relay, it assumes a single running instance of checkin-service.
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
