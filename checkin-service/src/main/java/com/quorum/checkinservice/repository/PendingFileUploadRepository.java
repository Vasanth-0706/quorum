package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.PendingFileUpload;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PendingFileUploadRepository extends JpaRepository<PendingFileUpload, String> {

    List<PendingFileUpload> findBySessionIdOrderByReceivedAtAsc(Long sessionId);
}
