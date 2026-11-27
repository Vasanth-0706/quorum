package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.SessionView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface SessionViewRepository extends JpaRepository<SessionView, Long> {

    List<SessionView> findByRoundIdAndDate(Long roundId, LocalDate date);

    @Query("select distinct s.date from SessionView s where s.roundId = :roundId order by s.date")
    List<LocalDate> findDatesByRoundId(Long roundId);

    @Query("select distinct s.roundId from SessionView s")
    List<Long> findRoundIds();
}
