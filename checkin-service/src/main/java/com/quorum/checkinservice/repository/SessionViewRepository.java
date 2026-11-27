package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.SessionView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface SessionViewRepository extends JpaRepository<SessionView, Long> {

    /** Sessions in a room on a date, room compared without case or surrounding spaces. */
    @Query("""
            SELECT s FROM SessionView s
            WHERE s.date = :date AND LOWER(TRIM(s.room)) = LOWER(TRIM(:room))
            ORDER BY s.startTime, s.sessionId""")
    List<SessionView> findInRoomOnDate(@Param("room") String room, @Param("date") LocalDate date);

    List<SessionView> findByDateOrderByStartTimeAscSessionIdAsc(LocalDate date);

    List<SessionView> findAllByOrderByDateAscStartTimeAscSessionIdAsc();
}
