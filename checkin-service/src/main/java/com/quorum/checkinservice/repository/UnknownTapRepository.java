package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.UnknownTap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UnknownTapRepository extends JpaRepository<UnknownTap, String> {

    List<UnknownTap> findAllByOrderByLastSeenAtDesc();
}
