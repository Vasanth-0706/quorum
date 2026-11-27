package com.quorum.timetableservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A student enrolled in a section for a semester. */
@Entity
@Table(name = "enrolment", uniqueConstraints = @UniqueConstraint(name = "uk_enrolment",
        columnNames = {"semester", "student_id", "section_code"}))
@Getter
@NoArgsConstructor
public class Enrolment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String semester;

    @Column(name = "student_id", nullable = false, length = 10)
    private String studentId;

    @Column(name = "section_code", nullable = false, length = 40)
    private String sectionCode;

    public Enrolment(String semester, String studentId, String sectionCode) {
        this.semester = semester;
        this.studentId = studentId;
        this.sectionCode = sectionCode;
    }
}
