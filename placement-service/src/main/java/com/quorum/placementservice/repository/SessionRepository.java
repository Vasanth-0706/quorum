package com.quorum.placementservice.repository;

import com.quorum.placementservice.entity.Session;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SessionRepository extends JpaRepository<Session, Long> {

    List<Session> findByRoundIdOrderByDateAscStartTimeAscIdAsc(Long roundId);

    List<Session> findByRoundIdInOrderByDateAscStartTimeAscIdAsc(Collection<Long> roundIds);
}
