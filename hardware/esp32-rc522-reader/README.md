# ESP32 + RC522 RFID reader

A reader fixed in one room. It reads a student's card and posts the UID to checkin-service
(`POST /checkins/tap` with `X-Device-Key`). The server decides which session is running in the
room and answers; the reader only shows the result.

| Answer | Meaning | Reader |
|---|---|---|
| 200 `MARKED` / `ALREADY_MARKED` | present (now or earlier) | green blink + one short beep |
| 404 `UNKNOWN_CARD` | card not enrolled; listed in `GET /cards/unknown-taps` | red blink + two beeps |
| 409 `NO_ACTIVE_SESSION` / `SESSION_CANCELLED` | nothing to check in to in this room now | red blink + two beeps |
| 401 / 403 / 400 / 5xx | wrong or inactive key, bad request, server error | red blink + one long beep |
| no answer | network error, retried 3 times | long red light |

The same card is ignored for 3 s after a read. Every step is printed to Serial at 115200 baud.

## Parts

- ESP32 DevKit (ESP32-WROOM-32, 30 or 38 pins)
- MFRC522 (RC522) 13.56 MHz module + MIFARE cards or tags (4-byte or 7-byte UID)
- Optional: green LED, red LED (each with a 220-330 Ω resistor to GND), **active** 3.3 V buzzer
- Jumper wires, USB data cable

## Wiring

| RC522 pin | ESP32 pin | |
|---|---|---|
| SDA / SS | GPIO 5 | |
| SCK | GPIO 18 | |
| MOSI | GPIO 23 | |
| MISO | GPIO 19 | |
| IRQ | not connected | |
| GND | GND | |
| RST | GPIO 22 | |
| 3.3V | 3V3 | **never 5V**: the RC522 is a 3.3 V part and 5 V damages it |

| Optional | ESP32 pin |
|---|---|
| Green LED (+ resistor to GND) | GPIO 2 (also the on-board blue LED of most DevKits) |
| Red LED (+ resistor to GND) | GPIO 4 |
| Active buzzer + (− to GND) | GPIO 21 |

Without LEDs or buzzer the reader still works; watch the on-board LED and the Serial monitor.
A **passive** buzzer only clicks with `digitalWrite`; use an active one, or replace `beep()` with `tone()`.

## Arduino IDE setup

1. Install Arduino IDE 2.x.
2. Board support: *File → Preferences → Additional boards manager URLs*, add
   `https://espressif.github.io/arduino-esp32/package_esp32_index.json`.
   Then *Tools → Board → Boards Manager*, search **esp32**, install **esp32 by Espressif Systems**.
3. Library: *Tools → Manage Libraries*, search **MFRC522**, install **MFRC522 by GithubCommunity**
   (the `miguelbalboa/rfid` library). `WiFi`, `HTTPClient` and `SPI` come with the ESP32 board package.
4. Open `esp32-rc522-reader/esp32-rc522-reader.ino` (the folder name must match the sketch name).
5. *Tools → Board → esp32 → ESP32 Dev Module*; *Tools → Port* = the USB port
   (`/dev/ttyUSB0` / `/dev/ttyACM0` on Linux, `COMx` on Windows, `/dev/cu.usbserial-*` on macOS).
   No port? Install the USB-serial driver of your board (CP210x or CH340). On Linux add yourself to
   the `dialout` group (`sudo usermod -aG dialout $USER`, then log in again).
6. Create `secrets.h` (below), click **Upload**. If the upload hangs on `Connecting....`, hold the
   board's **BOOT** button until it starts writing.
7. *Tools → Serial Monitor*, **115200 baud**.

## secrets.h

`secrets.h` is git-ignored; never commit it. Copy the template and edit it:

```bash
cp secrets.example.h secrets.h
```

```c
#define WIFI_SSID     "my-hotspot"                 // 2.4 GHz only
#define WIFI_PASSWORD "…"
#define SERVER_URL    "http://192.168.43.17:8084"   // laptop LAN IP, no trailing slash
#define DEVICE_KEY    "R-HALL-A.…"                  // apiKey from POST /devices
```

### Finding the laptop's LAN IP for SERVER_URL

The ESP32 and the laptop must be on the **same network**. A phone hotspot works well: connect both
the laptop and the ESP32 to it (turn on the hotspot's 2.4 GHz / "maximise compatibility" option).
`localhost` does not work: on the ESP32, localhost is the ESP32.

| OS | Command | Look for |
|---|---|---|
| Linux | `ip -4 addr show` or `hostname -I` | the `inet` of `wlan0` / `wlp…`, e.g. `192.168.43.17` |
| macOS | `ipconfig getifaddr en0` | |
| Windows | `ipconfig` | *Wireless LAN adapter Wi-Fi → IPv4 Address* |

Check from the phone or another device on the same Wi-Fi: open `http://<IP>:8084/devices` in a
browser; it must answer with JSON. If it does not, open port 8084 in the laptop's firewall, e.g.
`sudo ufw allow 8084/tcp` (Linux) or allow Java in *Windows Defender Firewall*. Hotspot IPs can
change when it restarts; update `SERVER_URL` and upload again.

## Register the device and enrol a card

checkin-service must run on port 8084. Device endpoints are DIRECTOR only (TEMPORARY headers until
real login exists, see the checkin-service README).

```bash
# 1. Register the reader for its room. The key is shown ONCE: copy it into secrets.h as DEVICE_KEY.
curl -s -X POST http://localhost:8084/devices \
  -H 'X-User-Id: dir-1' -H 'X-User-Role: DIRECTOR' -H 'Content-Type: application/json' \
  -d '{"deviceId":"R-HALL-A","room":"Hall A"}'
# → {"deviceId":"R-HALL-A","room":"Hall A","active":true,"apiKey":"R-HALL-A.…","note":"…"}

# 2. Tap a new card on the reader. It answers red + two beeps (404 UNKNOWN_CARD) and the UID is stored.
curl -s http://localhost:8084/cards/unknown-taps
# → [{"cardUid":"A1B2C3D4","firstSeenAt":"…","tapCount":1,"lastDeviceId":"R-HALL-A","lastRoom":"Hall A"}]

# 3. Enrol that UID to a student (made-up 10-digit ID). It disappears from the unknown-tap list.
curl -s -X POST http://localhost:8084/cards \
  -H 'X-User-Id: coord-1' -H 'X-User-Role: COORDINATOR' -H 'Content-Type: application/json' \
  -d '{"studentId":"9900000001","cardUid":"A1B2C3D4"}'
```

Many cards at once: `POST /cards/import` with a CSV of `studentId,cardUid` lines
(`curl -F file=@cards.csv …`, same headers). Lost the key? `POST /devices/R-HALL-A/key` (DIRECTOR)
issues a new one. Switch a reader off: `PATCH /devices/R-HALL-A` with `{"active":false}`.

The room must match the session's `room` in placement-service (case and surrounding spaces are ignored).

## Test without hardware

curl does exactly what the reader does:

```bash
KEY='R-HALL-A.…'   # the apiKey from step 1
curl -s -w '\nHTTP %{http_code} in %{time_total}s\n' -X POST http://localhost:8084/checkins/tap \
  -H "X-Device-Key: $KEY" -H 'Content-Type: application/json' -d '{"cardUid":"A1B2C3D4"}'
```

Expect 404 `UNKNOWN_CARD` before enrolment, 409 `NO_ACTIVE_SESSION` when no session runs in the
room now, 200 `MARKED` during a session, and 200 `ALREADY_MARKED` on the second tap.

## Serial output (demo)

```
=== Quorum RFID reader (ESP32 + RC522) ===
[rc522] firmware version 0x92
[wifi] connecting to "my-hotspot" ...
....
[wifi] connected, IP 192.168.43.88, signal -48 dBm
[ready] server http://192.168.43.17:8084/checkins/tap
[ready] hold a card to the reader
[card] UID A1B2C3D4 (4 bytes)
[http] POST http://192.168.43.17:8084/checkins/tap {"cardUid":"A1B2C3D4"}
[http] 200 in 41 ms: {"result":"MARKED","cardUid":"A1B2C3D4","studentId":"9900000001","sessionId":41,…}
[tap] marked present
[card] A1B2C3D4 again within 3 s, ignored
```

## Troubleshooting

| Symptom | Fix |
|---|---|
| `firmware version 0x00` or `0xFF` | RC522 not answering: check SS=5, SCK=18, MOSI=23, MISO=19, RST=22, 3V3 and GND; short wires |
| Wi-Fi never connects | wrong SSID/password, or a 5 GHz-only network |
| `network error -1 (connection refused)` | wrong IP/port in `SERVER_URL`, service not running, or firewall |
| `401` | `DEVICE_KEY` wrong or from an earlier registration / rotated key |
| `403` | device switched off (`PATCH /devices/{id}` `{"active":true}`) |
| `409` although a session runs | the device room differs from the session room, or `quorum.time-zone` does not match placement-service's times |
| upload fails with `secrets.h is missing` | `cp secrets.example.h secrets.h` |
