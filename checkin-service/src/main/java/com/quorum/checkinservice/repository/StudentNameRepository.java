package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.StudentName;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentNameRepository extends JpaRepository<StudentName, String> {
}
