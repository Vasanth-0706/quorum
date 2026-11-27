package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.Semester;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface SemesterRepository extends JpaRepository<Semester, String> {

    Optional<Semester> findFirstByActiveTrue();

    List<Semester> findAllByOrderByActiveDescAppliedAtDesc();

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Semester s set s.active = false where s.active = true")
    int deactivateAll();
}
