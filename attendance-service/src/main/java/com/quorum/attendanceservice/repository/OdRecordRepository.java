package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.OdRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface OdRecordRepository extends JpaRepository<OdRecord, Long> {

    List<OdRecord> findByStudentIdAndDate(String studentId, LocalDate date);

    @Query("""
            select distinct o.studentId from OdRecord o
            where o.roundId = :roundId and o.date = :date and o.status = :status""")
    List<String> findStudentIds(Long roundId, LocalDate date, OdRecord.Status status);

    List<OdRecord> findByDateAndStatusInOrderByStudentIdAscSlotStartAsc(LocalDate date,
                                                                        Collection<OdRecord.Status> statuses);

    List<OdRecord> findByStudentIdAndDateBetweenAndStatusInOrderByDateAscSlotStartAsc(
            String studentId, LocalDate from, LocalDate to, Collection<OdRecord.Status> statuses);

    List<OdRecord> findBySessionIdAndStatusInOrderByStudentIdAscSlotStartAsc(Long sessionId,
                                                                             Collection<OdRecord.Status> statuses);
}
