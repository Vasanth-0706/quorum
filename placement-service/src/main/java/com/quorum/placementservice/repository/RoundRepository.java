package com.quorum.placementservice.repository;

import com.quorum.placementservice.entity.Round;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoundRepository extends JpaRepository<Round, Long> {

    List<Round> findByDriveIdOrderBySequenceAscIdAsc(Long driveId);

    boolean existsByDriveIdAndSequence(Long driveId, int sequence);
}
