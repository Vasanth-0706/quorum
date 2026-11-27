package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.Correction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CorrectionRepository extends JpaRepository<Correction, Long> {

    List<Correction> findBySessionIdOrderByIdDesc(Long sessionId);
}
