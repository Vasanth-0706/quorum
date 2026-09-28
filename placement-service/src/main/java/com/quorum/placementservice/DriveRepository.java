package com.quorum.placementservice;

import org.springframework.data.jpa.repository.JpaRepository;
import com.quorum.placementservice.Drive;

public interface DriveRepository extends JpaRepository<Drive, Long> {
}
