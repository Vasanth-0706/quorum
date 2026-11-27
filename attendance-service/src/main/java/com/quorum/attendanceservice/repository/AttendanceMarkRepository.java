package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.AttendanceMark;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AttendanceMarkRepository extends JpaRepository<AttendanceMark, Long> {

    boolean existsByMarkId(String markId);

    Optional<AttendanceMark> findFirstBySessionIdAndStudentIdOrderByVersionDesc(Long sessionId, String studentId);

    /** The latest mark (highest version) of every student in the session. */
    @Query("""
            select m from AttendanceMark m where m.sessionId = :sessionId and m.version =
              (select max(m2.version) from AttendanceMark m2
               where m2.sessionId = m.sessionId and m2.studentId = m.studentId)
            order by m.studentId""")
    List<AttendanceMark> findLatestBySession(Long sessionId);

    /** The latest mark of one student in each of the given sessions. */
    @Query("""
            select m from AttendanceMark m where m.studentId = :studentId and m.sessionId in :sessionIds
              and m.version = (select max(m2.version) from AttendanceMark m2
                               where m2.sessionId = m.sessionId and m2.studentId = m.studentId)""")
    List<AttendanceMark> findLatestByStudent(String studentId, Collection<Long> sessionIds);

    List<AttendanceMark> findBySessionIdOrderByStudentIdAscVersionAsc(Long sessionId);
}
