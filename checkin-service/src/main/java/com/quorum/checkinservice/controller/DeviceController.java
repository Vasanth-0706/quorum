package com.quorum.checkinservice.controller;

import com.quorum.checkinservice.config.TemporaryAuthInterceptor;
import com.quorum.checkinservice.dto.DeviceKeyResponse;
import com.quorum.checkinservice.dto.DeviceRequest;
import com.quorum.checkinservice.dto.DeviceResponse;
import com.quorum.checkinservice.dto.DeviceUpdateRequest;
import com.quorum.checkinservice.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Reader registration. Every write is DIRECTOR only. */
@RestController
public class DeviceController {

    private final DeviceService devices;

    public DeviceController(DeviceService devices) {
        this.devices = devices;
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceKeyResponse register(@RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                      @RequestHeader(TemporaryAuthInterceptor.USER_ROLE_HEADER) String role,
                                      @Valid @RequestBody DeviceRequest request) {
        TemporaryAuthInterceptor.requireDirector(role);
        DeviceService.Registered r = devices.register(request.deviceId(), request.room(), userId);
        return DeviceKeyResponse.from(r.device(), r.apiKey());
    }

    /** Replaces a lost or leaked key; the old one stops working. */
    @PostMapping("/devices/{deviceId}/key")
    public DeviceKeyResponse rotateKey(@RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                       @RequestHeader(TemporaryAuthInterceptor.USER_ROLE_HEADER) String role,
                                       @PathVariable String deviceId) {
        TemporaryAuthInterceptor.requireDirector(role);
        DeviceService.Registered r = devices.rotateKey(deviceId, userId);
        return DeviceKeyResponse.from(r.device(), r.apiKey());
    }

    @PatchMapping("/devices/{deviceId}")
    public DeviceResponse update(@RequestHeader(TemporaryAuthInterceptor.USER_ID_HEADER) String userId,
                                 @RequestHeader(TemporaryAuthInterceptor.USER_ROLE_HEADER) String role,
                                 @PathVariable String deviceId, @Valid @RequestBody DeviceUpdateRequest request) {
        TemporaryAuthInterceptor.requireDirector(role);
        return DeviceResponse.from(devices.update(deviceId, request.active(), request.room(), userId));
    }

    @GetMapping("/devices")
    public List<DeviceResponse> list() {
        return devices.list().stream().map(DeviceResponse::from).toList();
    }
}
