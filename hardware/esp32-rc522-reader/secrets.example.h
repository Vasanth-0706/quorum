// Copy this file to secrets.h (git-ignored) and fill in your values.
// secrets.h must sit next to esp32-rc522-reader.ino.
#pragma once

// 2.4 GHz Wi-Fi only (the ESP32 has no 5 GHz). A phone hotspot works.
#define WIFI_SSID     "your-wifi-or-hotspot-name"
#define WIFI_PASSWORD "change-me"

// checkin-service on your laptop: http://<laptop LAN IP>:8084, no trailing slash.
// Not localhost: on the ESP32, localhost is the ESP32 itself.
#define SERVER_URL    "http://192.168.1.50:8084"

// The apiKey returned ONCE by POST /devices, e.g. "R-HALL-A.xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx".
#define DEVICE_KEY    "R-HALL-A.paste-the-key-from-POST-devices"
