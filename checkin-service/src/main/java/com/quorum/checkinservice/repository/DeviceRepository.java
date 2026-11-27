package com.quorum.checkinservice.repository;

import com.quorum.checkinservice.entity.Device;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface DeviceRepository extends JpaRepository<Device, String> {

    List<Device> findAllByOrderByDeviceIdAsc();

    @Modifying
    @Query("UPDATE Device d SET d.lastSeenAt = :at WHERE d.deviceId = :id")
    int touch(@Param("id") String deviceId, @Param("at") Instant at);
}
