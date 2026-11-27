package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.TimetableUpload;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimetableUploadRepository extends JpaRepository<TimetableUpload, String> {
}
