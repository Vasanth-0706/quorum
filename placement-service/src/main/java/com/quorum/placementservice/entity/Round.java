package com.quorum.placementservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One stage of a drive, e.g. "Online test", "Technical", "HR". */
@Entity
@Table(name = "drive_round",
        uniqueConstraints = @UniqueConstraint(name = "uk_round_drive_sequence", columnNames = {"drive_id", "sequence"}))
@Getter
@Setter
@NoArgsConstructor
public class Round {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "drive_id", nullable = false)
    private Drive drive;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(nullable = false)
    private int sequence;
}
