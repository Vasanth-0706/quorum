package com.quorum.attendanceservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/** One clash of the stored result for roundId + date: a student's class slot that overlaps the round. */
@Entity
@Table(name = "clash_entry", indexes = {
        @Index(name = "idx_clash_entry_round_date", columnList = "round_id, clash_date"),
        @Index(name = "idx_clash_entry_student_date", columnList = "student_id, clash_date")})
@Getter
@NoArgsConstructor
public class ClashEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    @Column(name = "clash_date", nullable = false)
    private LocalDate date;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    @Column(name = "section_code", nullable = false, length = 32)
    private String sectionCode;

    @Column(name = "course_name", length = 200)
    private String courseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "slot_start", nullable = false)
    private LocalTime slotStart;

    @Column(name = "slot_end", nullable = false)
    private LocalTime slotEnd;

    @Column(length = 60)
    private String room;

    public ClashEntry(Long roundId, LocalDate date, String studentId, String sectionCode, String courseName,
                      DayOfWeek dayOfWeek, LocalTime slotStart, LocalTime slotEnd, String room) {
        this.roundId = roundId;
        this.date = date;
        this.studentId = studentId;
        this.sectionCode = sectionCode;
        this.courseName = courseName;
        this.dayOfWeek = dayOfWeek;
        this.slotStart = slotStart;
        this.slotEnd = slotEnd;
        this.room = room;
    }
}
