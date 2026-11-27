package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.ShortlistEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ShortlistEntryRepository extends JpaRepository<ShortlistEntry, Long> {

    @Query("select e.studentId from ShortlistEntry e where e.roundId = :roundId order by e.studentId")
    List<String> findStudentIdsByRoundId(Long roundId);

    /** Runs immediately (bulk delete), so re-inserting the same students in the same transaction is safe. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from ShortlistEntry e where e.roundId = :roundId")
    int deleteByRoundId(Long roundId);
}
