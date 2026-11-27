package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.SessionView;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface SessionViewRepository extends JpaRepository<SessionView, Long> {

    /** Cancelled sessions included: they still set the start of the waiting window and keep their marks. */
    List<SessionView> findByRoundIdAndDate(Long roundId, LocalDate date);
}
