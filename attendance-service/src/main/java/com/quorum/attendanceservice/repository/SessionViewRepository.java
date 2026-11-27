package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.SessionView;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SessionViewRepository extends JpaRepository<SessionView, Long> {
}
