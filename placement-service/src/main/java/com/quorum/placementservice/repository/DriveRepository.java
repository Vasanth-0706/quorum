package com.quorum.placementservice.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.quorum.placementservice.entity.Drive;

public interface DriveRepository extends JpaRepository<Drive, Long> {
}
