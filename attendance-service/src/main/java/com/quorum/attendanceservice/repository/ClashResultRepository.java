package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.ClashResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface ClashResultRepository extends JpaRepository<ClashResult, Long> {

    Optional<ClashResult> findByRoundIdAndDate(Long roundId, LocalDate date);
}
