package com.quorum.timetableservice.entity;

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
import java.time.LocalTime;

/** A weekly class slot of a section. */
@Entity
@Table(name = "timetable_slot", indexes = @Index(name = "idx_slot_semester_section", columnList = "semester, section_code"))
@Getter
@NoArgsConstructor
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String semester;

    @Column(name = "section_code", nullable = false, length = 40)
    private String sectionCode;

    @Column(name = "course_name", length = 120)
    private String courseName;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 10)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(length = 60)
    private String room;

    public Slot(String semester, String sectionCode, String courseName, DayOfWeek dayOfWeek,
                LocalTime startTime, LocalTime endTime, String room) {
        this.semester = semester;
        this.sectionCode = sectionCode;
        this.courseName = courseName;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.room = room;
    }
}
