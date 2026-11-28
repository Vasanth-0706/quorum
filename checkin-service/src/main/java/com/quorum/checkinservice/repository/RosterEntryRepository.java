package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.RosterEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RosterEntryRepository extends JpaRepository<RosterEntry, Long> {

    List<RosterEntry> findByRoundIdOrderByStudentIdAsc(Long roundId);

    /** Runs immediately (bulk delete), so re-inserting the same students in the same transaction is safe. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from RosterEntry e where e.roundId = :roundId")
    int deleteByRoundId(@Param("roundId") Long roundId);
}
