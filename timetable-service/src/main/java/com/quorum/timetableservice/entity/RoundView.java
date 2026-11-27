package com.quorum.timetableservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Local copy of placement rounds from ROUND_CREATED. */
@Entity
@Table(name = "round_view")
@Getter
@Setter
@NoArgsConstructor
public class RoundView {

    @Id
    @Column(name = "round_id")
    private Long roundId;

    @Column(name = "drive_id", nullable = false)
    private Long driveId;

    @Column(length = 60)
    private String name;

    private Integer sequence;
}
