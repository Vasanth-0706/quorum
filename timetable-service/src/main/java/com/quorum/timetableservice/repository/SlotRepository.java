package com.quorum.timetableservice.repository;

import com.quorum.timetableservice.entity.Slot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.DayOfWeek;
import java.util.Collection;
import java.util.List;

public interface SlotRepository extends JpaRepository<Slot, Long> {

    List<Slot> findBySemesterAndSectionCodeIn(String semester, Collection<String> sectionCodes);

    List<Slot> findBySemesterAndSectionCodeInAndDayOfWeekOrderByStartTimeAscSectionCodeAsc(
            String semester, Collection<String> sectionCodes, DayOfWeek dayOfWeek);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Slot s where s.semester = :semester")
    int deleteBySemester(String semester);
}
