package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.Mark;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MarkRepository extends JpaRepository<Mark, Long> {

    Optional<Mark> findFirstBySessionIdAndStudentIdOrderByVersionDesc(Long sessionId, String studentId);

    boolean existsBySessionIdAndStudentIdAndSource(Long sessionId, String studentId, Mark.Source source);

    boolean existsByMarkId(String markId);

    /** Every version of every student, latest version first within a student. */
    List<Mark> findBySessionIdOrderByStudentIdAscVersionDesc(Long sessionId);

    long countBySessionId(Long sessionId);
}
