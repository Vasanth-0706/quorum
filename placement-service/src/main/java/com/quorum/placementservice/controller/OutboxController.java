package com.quorum.placementservice.controller;

import com.quorum.placementservice.dto.OutboxStatusResponse;
import com.quorum.placementservice.repository.OutboxEventRepository;
import org.springframework.data.domain.Limit;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only view of the outbox, for checking that the relay keeps up. */
@RestController
public class OutboxController {

    private final OutboxEventRepository repository;

    public OutboxController(OutboxEventRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/outbox")
    @Transactional(readOnly = true)
    public OutboxStatusResponse status(@RequestParam(defaultValue = "20") int limit) {
        int size = Math.max(1, Math.min(limit, 200));
        return new OutboxStatusResponse(repository.countByPublishedAtIsNull(),
                repository.findAllByOrderByIdDesc(Limit.of(size)).stream()
                        .map(OutboxStatusResponse.Row::from)
                        .toList());
    }
}
