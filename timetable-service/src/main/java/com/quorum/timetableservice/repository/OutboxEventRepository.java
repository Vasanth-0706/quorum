package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.OutboxEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Unpublished rows, oldest first, so per-key order is kept. */
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Limit limit);

    long countByPublishedAtIsNull();

    List<OutboxEvent> findAllByOrderByIdDesc(Limit limit);
}
