package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.Enrolment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;

public interface EnrolmentRepository extends JpaRepository<Enrolment, Long> {

    List<Enrolment> findBySemesterAndStudentIdIn(String semester, Collection<String> studentIds);

    @Query("select e.sectionCode from Enrolment e where e.semester = :semester and e.studentId = :studentId")
    List<String> findSectionCodes(String semester, String studentId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Enrolment e where e.semester = :semester")
    int deleteBySemester(String semester);
}
