package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.ClashEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;

public interface ClashEntryRepository extends JpaRepository<ClashEntry, Long> {

    List<ClashEntry> findByRoundIdAndDate(Long roundId, LocalDate date);

    List<ClashEntry> findByStudentIdAndDate(String studentId, LocalDate date);

    @Query("select distinct c.studentId from ClashEntry c where c.roundId = :roundId and c.date = :date")
    List<String> findStudentIds(Long roundId, LocalDate date);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ClashEntry c where c.roundId = :roundId and c.date = :date")
    int deleteByRoundAndDate(Long roundId, LocalDate date);
}
