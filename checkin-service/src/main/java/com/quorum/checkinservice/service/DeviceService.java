package com.quorum.checkinservice.service;

import com.quorum.checkinservice.entity.Device;
import com.quorum.checkinservice.repository.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registers readers and checks their X-Device-Key.
 *
 * <p>A key is {@code <deviceId>.<secret>}: the deviceId finds the row by primary key, so only ONE
 * BCrypt check is needed (BCrypt cannot be looked up by hash). The secret is 32 url-safe base64
 * characters (192 random bits); only its BCrypt hash is stored. A BCrypt check costs ~50-100 ms, too
 * much for every tap within the 300 ms budget, so a successful check is remembered in memory as
 * the SHA-256 of the secret together with the hash it matched. A new key or a restart clears it.
 */
@Service
public class DeviceService {

    public record Registered(Device device, String apiKey) {
    }

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);
    private static final int SECRET_BYTES = 24;

    private final DeviceRepository devices;
    private final PasswordEncoder encoder;
    private final SecureRandom random;
    private final Clock clock;
    /** deviceId → (apiKeyHash it was verified against, SHA-256 of the secret). */
    private final Map<String, Verified> verified = new ConcurrentHashMap<>();

    private record Verified(String apiKeyHash, byte[] secretSha256) {
    }

    public DeviceService(DeviceRepository devices, PasswordEncoder encoder, SecureRandom random, Clock clock) {
        this.devices = devices;
        this.encoder = encoder;
        this.random = random;
        this.clock = clock;
    }

    @Transactional
    public Registered register(String deviceId, String room, String actor) {
        if (devices.existsById(deviceId)) {
            throw ApiException.conflict("DEVICE_EXISTS", "Device " + deviceId + " is already registered");
        }
        String secret = newSecret();
        Device device = devices.save(new Device(deviceId, room.trim(), encoder.encode(secret), actor, clock.instant()));
        log.info("Device {} registered in room {} by {}", deviceId, device.getRoom(), actor);
        return new Registered(device, deviceId + "." + secret);
    }

    /** A new key for a device whose key was lost or leaked. The old key stops working at once. */
    @Transactional
    public Registered rotateKey(String deviceId, String actor) {
        Device device = find(deviceId);
        String secret = newSecret();
        device.setApiKeyHash(encoder.encode(secret));
        verified.remove(deviceId);
        log.info("Key of device {} replaced by {}", deviceId, actor);
        return new Registered(device, deviceId + "." + secret);
    }

    @Transactional
    public Device update(String deviceId, Boolean active, String room, String actor) {
        Device device = find(deviceId);
        if (active != null) {
            device.setActive(active);
        }
        if (room != null && !room.isBlank()) {
            device.setRoom(room.trim());
        }
        log.info("Device {} updated by {}: active={}, room={}", deviceId, actor, device.isActive(), device.getRoom());
        return device;
    }

    @Transactional(readOnly = true)
    public List<Device> list() {
        return devices.findAllByOrderByDeviceIdAsc();
    }

    /**
     * The device the key belongs to. 401 INVALID_DEVICE_KEY when the key is missing, malformed or
     * wrong; 403 DEVICE_INACTIVE when it is right but the device is switched off.
     */
    public Device authenticate(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw invalidKey("Missing X-Device-Key header");
        }
        int dot = apiKey.indexOf('.');
        if (dot <= 0 || dot == apiKey.length() - 1 || apiKey.length() > 200) {
            throw invalidKey("Malformed X-Device-Key");
        }
        String deviceId = apiKey.substring(0, dot);
        String secret = apiKey.substring(dot + 1);
        Device device = devices.findById(deviceId).orElseThrow(() -> invalidKey("Unknown or wrong X-Device-Key"));
        if (!matches(device, secret)) {
            throw invalidKey("Unknown or wrong X-Device-Key");
        }
        if (!device.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_INACTIVE", "Device " + deviceId + " is not active");
        }
        return device;
    }

    private boolean matches(Device device, String secret) {
        byte[] sha = sha256(secret);
        Verified v = verified.get(device.getDeviceId());
        if (v != null && v.apiKeyHash().equals(device.getApiKeyHash()) && MessageDigest.isEqual(v.secretSha256(), sha)) {
            return true;
        }
        if (!encoder.matches(secret, device.getApiKeyHash())) {
            return false;
        }
        verified.put(device.getDeviceId(), new Verified(device.getApiKeyHash(), sha));
        return true;
    }

    private Device find(String deviceId) {
        return devices.findById(deviceId)
                .orElseThrow(() -> ApiException.notFound("DEVICE_NOT_FOUND", "Device " + deviceId + " is not registered"));
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static ApiException invalidKey(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_DEVICE_KEY", message);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
