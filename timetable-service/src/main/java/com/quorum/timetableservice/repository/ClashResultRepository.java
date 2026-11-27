package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.ClashResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ClashResultRepository extends JpaRepository<ClashResult, Long> {

    Optional<ClashResult> findByRoundIdAndDate(Long roundId, LocalDate date);

    @Query("select c.date from ClashResult c where c.roundId = :roundId")
    List<LocalDate> findDatesByRoundId(Long roundId);

    @Query("select distinct c.roundId from ClashResult c")
    List<Long> findRoundIds();
}
