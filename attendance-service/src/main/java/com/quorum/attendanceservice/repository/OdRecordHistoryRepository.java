package com.quorum.attendanceservice.repository;

import com.quorum.attendanceservice.entity.OdRecordHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OdRecordHistoryRepository extends JpaRepository<OdRecordHistory, Long> {

    List<OdRecordHistory> findByOdRecordIdOrderByVersionAsc(Long odRecordId);
}
