package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.TimetablePart;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface TimetablePartRepository extends JpaRepository<TimetablePart, Long> {

    boolean existsByUploadIdAndPart(String uploadId, int part);

    long countByUploadId(String uploadId);

    List<TimetablePart> findByUploadIdOrderByPartAsc(String uploadId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from TimetablePart p where p.uploadId = :uploadId")
    int deleteByUploadId(String uploadId);
}
