/*
 * Quorum RFID reader: ESP32 DevKit + MFRC522 (RC522) over SPI.
 *
 * Reads a card UID (4 or 7 bytes) as upper-case hex and sends it to checkin-service:
 *   POST SERVER_URL/checkins/tap   X-Device-Key: DEVICE_KEY   {"cardUid":"04A31B2C"}
 *
 *   200 (MARKED / ALREADY_MARKED)          -> green blink + one short beep
 *   404 (UNKNOWN_CARD) / 409 (no session)  -> red blink + two beeps
 *   other HTTP status (401, 403, 400, 5xx) -> red blink + one long beep
 *   network error                          -> retried 3 times, then a long red light
 *
 * The same card is ignored for 3 s after a read. Every step is printed to Serial at 115200 baud.
 * Wiring and setup: README.md next to this file. Settings: secrets.h (copy secrets.example.h).
 */

#include <SPI.h>
#include <MFRC522.h>
#include <WiFi.h>
#include <HTTPClient.h>

#if __has_include("secrets.h")
#include "secrets.h"
#else
#error "secrets.h is missing: copy secrets.example.h to secrets.h and fill in Wi-Fi, SERVER_URL and DEVICE_KEY"
#endif

// ---- pins (see README wiring table) ----
static const uint8_t PIN_SS = 5;     // RC522 SDA/SS
static const uint8_t PIN_SCK = 18;
static const uint8_t PIN_MISO = 19;
static const uint8_t PIN_MOSI = 23;
static const uint8_t PIN_RST = 22;
static const uint8_t PIN_LED_GREEN = 2;  // also the on-board LED of most DevKits
static const uint8_t PIN_LED_RED = 4;
static const uint8_t PIN_BUZZER = 21;    // active buzzer (HIGH = sound)

// ---- behaviour ----
static const unsigned long SAME_CARD_IGNORE_MS = 3000;
static const int NETWORK_RETRIES = 3;           // after the first attempt
static const unsigned long RETRY_DELAY_MS = 400;
static const uint16_t HTTP_CONNECT_TIMEOUT_MS = 2000;
static const uint16_t HTTP_TIMEOUT_MS = 3000;
static const unsigned long WIFI_CONNECT_TIMEOUT_MS = 15000;

MFRC522 rfid(PIN_SS, PIN_RST);

String lastUid = "";
unsigned long lastReadAt = 0;

// ---------- LEDs and buzzer ----------

void beep(unsigned int ms) {
  digitalWrite(PIN_BUZZER, HIGH);
  delay(ms);
  digitalWrite(PIN_BUZZER, LOW);
}

void signalOk() {
  digitalWrite(PIN_LED_GREEN, HIGH);
  beep(80);
  delay(420);
  digitalWrite(PIN_LED_GREEN, LOW);
}

void signalRejected() {
  digitalWrite(PIN_LED_RED, HIGH);
  beep(80);
  delay(80);
  beep(80);
  delay(400);
  digitalWrite(PIN_LED_RED, LOW);
}

void signalError() {
  digitalWrite(PIN_LED_RED, HIGH);
  beep(600);
  delay(400);
  digitalWrite(PIN_LED_RED, LOW);
}

void signalNetworkDown() {
  digitalWrite(PIN_LED_RED, HIGH);
  delay(1500);
  digitalWrite(PIN_LED_RED, LOW);
}

// ---------- Wi-Fi ----------

bool connectWifi() {
  if (WiFi.status() == WL_CONNECTED) {
    return true;
  }
  Serial.printf("[wifi] connecting to \"%s\" ...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  unsigned long started = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - started < WIFI_CONNECT_TIMEOUT_MS) {
    delay(250);
    Serial.print('.');
  }
  Serial.println();
  if (WiFi.status() != WL_CONNECTED) {
    Serial.printf("[wifi] NOT connected (status %d). Check SSID/password; the ESP32 needs 2.4 GHz.\n", WiFi.status());
    return false;
  }
  Serial.printf("[wifi] connected, IP %s, signal %d dBm\n", WiFi.localIP().toString().c_str(), WiFi.RSSI());
  return true;
}

// ---------- card ----------

String uidHex(const MFRC522::Uid &uid) {
  String hex = "";
  char part[3];
  for (byte i = 0; i < uid.size; i++) {
    snprintf(part, sizeof(part), "%02X", uid.uidByte[i]);
    hex += part;
  }
  return hex;
}

// ---------- server ----------

String tapUrl() {
  String base = SERVER_URL;
  while (base.endsWith("/")) {
    base.remove(base.length() - 1);
  }
  return base + "/checkins/tap";
}

/** One POST. Returns the HTTP status, or a negative HTTPClient error code on a network error. */
int postTap(const String &uid, String &responseBody) {
  HTTPClient http;
  http.setConnectTimeout(HTTP_CONNECT_TIMEOUT_MS);
  http.setTimeout(HTTP_TIMEOUT_MS);
  if (!http.begin(tapUrl())) {
    responseBody = "bad SERVER_URL";
    return HTTPC_ERROR_CONNECTION_REFUSED;
  }
  http.addHeader("Content-Type", "application/json");
  http.addHeader("X-Device-Key", DEVICE_KEY);
  String body = "{\"cardUid\":\"" + uid + "\"}";
  unsigned long started = millis();
  int status = http.POST(body);
  unsigned long took = millis() - started;
  if (status > 0) {
    responseBody = http.getString();
    Serial.printf("[http] %d in %lu ms: %s\n", status, took, responseBody.c_str());
  } else {
    responseBody = HTTPClient::errorToString(status);
    Serial.printf("[http] network error %d (%s) after %lu ms\n", status, responseBody.c_str(), took);
  }
  http.end();
  return status;
}

void sendTap(const String &uid) {
  String response;
  int status = -1;
  for (int attempt = 0; attempt <= NETWORK_RETRIES; attempt++) {
    if (attempt > 0) {
      Serial.printf("[http] retry %d of %d\n", attempt, NETWORK_RETRIES);
      delay(RETRY_DELAY_MS);
    }
    if (!connectWifi()) {
      status = -1;
      continue;
    }
    Serial.printf("[http] POST %s {\"cardUid\":\"%s\"}\n", tapUrl().c_str(), uid.c_str());
    status = postTap(uid, response);
    if (status > 0) {
      break;  // the server answered; only network errors are retried
    }
  }

  if (status == 200) {
    Serial.println(response.indexOf("ALREADY_MARKED") >= 0 ? "[tap] already marked" : "[tap] marked present");
    signalOk();
  } else if (status == 404 || status == 409) {
    Serial.println(status == 404 ? "[tap] unknown card: enrol it from GET /cards/unknown-taps"
                                 : "[tap] rejected: no running session in this room (or it is cancelled)");
    signalRejected();
  } else if (status > 0) {
    Serial.printf("[tap] server refused with %d (401/403 = check DEVICE_KEY or device inactive)\n", status);
    signalError();
  } else {
    Serial.println("[tap] FAILED: server not reachable after retries. Check SERVER_URL, laptop IP and firewall.");
    signalNetworkDown();
  }
}

// ---------- Arduino ----------

void setup() {
  Serial.begin(115200);
  delay(200);
  Serial.println();
  Serial.println("=== Quorum RFID reader (ESP32 + RC522) ===");

  pinMode(PIN_LED_GREEN, OUTPUT);
  pinMode(PIN_LED_RED, OUTPUT);
  pinMode(PIN_BUZZER, OUTPUT);
  digitalWrite(PIN_LED_GREEN, LOW);
  digitalWrite(PIN_LED_RED, LOW);
  digitalWrite(PIN_BUZZER, LOW);

  SPI.begin(PIN_SCK, PIN_MISO, PIN_MOSI, PIN_SS);
  rfid.PCD_Init();
  delay(50);
  byte version = rfid.PCD_ReadRegister(MFRC522::VersionReg);
  Serial.printf("[rc522] firmware version 0x%02X%s\n", version,
                (version == 0x00 || version == 0xFF) ? "  <-- no answer: check wiring and 3.3 V" : "");

  connectWifi();
  Serial.printf("[ready] server %s\n", tapUrl().c_str());
  Serial.println("[ready] hold a card to the reader");
  signalOk();
}

void loop() {
  if (!rfid.PICC_IsNewCardPresent() || !rfid.PICC_ReadCardSerial()) {
    delay(50);
    return;
  }

  String uid = uidHex(rfid.uid);
  rfid.PICC_HaltA();
  rfid.PCD_StopCrypto1();

  unsigned long now = millis();
  if (uid == lastUid && now - lastReadAt < SAME_CARD_IGNORE_MS) {
    Serial.printf("[card] %s again within 3 s, ignored\n", uid.c_str());
    return;
  }
  lastUid = uid;
  lastReadAt = now;

  Serial.printf("[card] UID %s (%d bytes)\n", uid.c_str(), rfid.uid.size);
  sendTap(uid);
}
