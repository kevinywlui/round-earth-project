#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <esp_mac.h>
#include <esp_system.h>    // esp_reset_reason() for boot diagnostics
#include <esp_task_wdt.h>  // task watchdog (requires ESP32 Arduino core 3.x / IDF 5)
#include <stdarg.h>        // va_list/vsnprintf for the emitLogf() variadic debug logger

// --- Configuration ---
#define DEVICE_NAME  "Bike Speed"  // a per-device suffix from the MAC is appended at boot
#define FW_VERSION   "1.0"         // reported over the BLE Device Information Service (0x180A)
#define SENSOR_PIN   D0    // hall effect sensor OUT pin (XIAO ESP32-C6)
#define MIN_MS       60    // minimum ms between triggers (~16.6 rev/s; ~125 km/h on a 2.1 m wheel)
#define SELFTEST_WINDOW_MS 8000  // boot wiring self-test: wait up to this long for a magnet pass to
                                 // confirm the hall sensor's GND/V/OUT wiring (LED flashes 3x on
                                 // success). 0 skips the wait (the stuck-low fault check still runs).
#define WDT_TIMEOUT_S      5      // reboot if loop() stalls this long (e.g. a wedged BLE stack)
#define HEALTH_INTERVAL_MS 5000   // period of the serial health line
#define DEBUG_VERBOSE      1      // 1 (default) = log every revolution; 0 = only health +
                                 // lifecycle events. Verbose is a per-rev Serial.printf on the
                                 // watchdog-guarded loop() hot path (~16 rev/s ceiling); set it
                                 // to 0 in the field if the serial load ever becomes a concern.

// Bluetooth CSC (Cycling Speed and Cadence) standard UUIDs
#define CSC_SERVICE_UUID      "00001816-0000-1000-8000-00805f9b34fb"
#define CSC_MEASUREMENT_UUID  "00002a5b-0000-1000-8000-00805f9b34fb"
#define CSC_FEATURE_UUID      "00002a5c-0000-1000-8000-00805f9b34fb"

// Nordic UART Service (NUS) — the de-facto "BLE serial port", used here as a one-way debug
// log channel (device → client). The app subscribes to the TX characteristic on the same
// connection it already uses for CSC; generic tools (e.g. nRF Connect) can read it too. Only
// low-rate lines (lifecycle events + the 5 s health line + a boot summary) are streamed, so
// BLE airtime stays bounded — per-revolution logs remain on USB serial. See emitLogf()/bleLog().
#define NUS_SERVICE_UUID      "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_TX_UUID           "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"

BLECharacteristic *measurementChar;
BLECharacteristic *logChar = nullptr;        // NUS TX (debug log notifications), null until setup()
BLE2902 *logCccd = nullptr;                  // logChar's 0x2902 CCCD; getNotifications() = subscribed?
esp_reset_reason_t bootReason = ESP_RST_UNKNOWN;  // captured in setup() for the boot summary line

// Wheel revolutions are a UINT32 per the CSC spec — they "cannot practically roll
// over during the life of the Sensor," so we accumulate without wrapping.
volatile uint32_t wheelRevolutions = 0;
volatile unsigned long lastTrigger = 0;  // ms of last accepted trigger (debounce)

// Single-producer (ISR) / single-consumer (loop) ring buffer of revolution events.
// A bare "pendingNotify" flag coalesced bursts: if several revolutions occurred
// between loop() passes, only the last (revs,time) was ever sent, collapsing the
// per-revolution time-series the Collector relies on. The buffer captures every
// accepted edge so loop() can emit one notification per revolution. Size is a power
// of two so head/tail wrap with a cheap mask.
#define RB_SIZE 32
volatile uint32_t rbRevs[RB_SIZE];
volatile uint16_t rbTime[RB_SIZE];        // 1/1024 sec units, captured in the ISR
volatile uint8_t  rbHead = 0;             // advanced by the ISR (producer), published with release
volatile uint8_t  rbTail = 0;             // advanced by loop() (consumer), published with release

// --- Diagnostics counters (observability) ---
volatile uint32_t droppedRevolutions = 0; // ring-buffer overflows (distance still safe; only the
                                          // individual timestamp is lost — see onMagnet())
volatile uint8_t  rbHighWater = 0;        // peak ring-buffer occupancy seen (how close to overflow)
volatile bool     deviceConnected = false;
volatile uint32_t disconnectCount = 0;    // BLE drops since boot
uint32_t          notificationsSent = 0;  // CSC packets sent (loop-only, no sync needed)

// True when a client is connected AND has enabled notifications on the NUS TX CCCD. We poll
// the descriptor's value (which the BLE stack updates on the client's CCCD write) rather than
// rely on a descriptor onWrite callback — BLE2902 does not reliably deliver one in this core.
bool logSubscribed() {
  return deviceConnected && logCccd != nullptr && logCccd->getNotifications();
}

// Sends one already-formatted string over the NUS TX characteristic as BLE notifications,
// but only when a client is connected AND subscribed (so we never waste airtime otherwise).
// Chunked to 20 bytes — safe for the default 23-byte ATT MTU — and the caller includes a
// trailing '\n' so the app can reassemble the byte stream into lines.
void bleLog(const char *s) {
  if (logChar == nullptr || !logSubscribed()) return;
  size_t len = strlen(s);
  const size_t CHUNK = 20;
  for (size_t off = 0; off < len; off += CHUNK) {
    size_t n = (len - off < CHUNK) ? (len - off) : CHUNK;
    logChar->setValue((uint8_t *)(s + off), n);
    logChar->notify();
  }
}

// Emits a diagnostic line to USB serial AND (when subscribed) over BLE. Used for the
// lifecycle events, the periodic health line, and the boot summary. The format string must
// NOT include a trailing newline: Serial.println adds one, and a '\n' is appended for BLE.
void emitLogf(const char *fmt, ...) {
  char buf[192];
  va_list ap;
  va_start(ap, fmt);
  int len = vsnprintf(buf, sizeof(buf) - 1, fmt, ap);  // -1 leaves room for the BLE '\n'
  va_end(ap);
  if (len < 0) return;
  if ((size_t)len >= sizeof(buf) - 1) len = sizeof(buf) - 2;  // truncated; keep room for '\n'
  Serial.println(buf);
  buf[len] = '\n';
  buf[len + 1] = '\0';
  bleLog(buf);
}

// Track connection state (so we only transmit when a client is listening) and
// restart advertising on disconnect so the sensor is rediscoverable without a
// power cycle.
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    deviceConnected = true;
    emitLogf("[event] client connected");
  }
  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;  // logSubscribed() now returns false, so bleLog() won't touch a dead link
    disconnectCount++;
    emitLogf("[event] client disconnected; re-advertising");
    server->startAdvertising();
  }
};

// Wheel detection runs off a falling-edge interrupt (A3144 pulls LOW on detect),
// so a fast wheel can't slip between poll samples. BLE work can't happen in an ISR,
// so we record the event in the ring buffer and let loop() send the notification.
void IRAM_ATTR onMagnet() {
  unsigned long now = millis();
  if (now - lastTrigger > MIN_MS) {
    lastTrigger = now;
    wheelRevolutions++;
    uint16_t eventTime = (uint16_t)(((uint64_t)now * 1024) / 1000);  // ms → 1/1024 sec (64-bit to avoid overflow)
    // Only the ISR writes rbHead, so a plain read is fine; read rbTail with acquire
    // and publish rbHead with release so the consumer never observes the new head
    // before the matching data writes (correct single-producer/single-consumer order).
    uint8_t head = rbHead;
    uint8_t tail = __atomic_load_n(&rbTail, __ATOMIC_ACQUIRE);
    uint8_t next = (uint8_t)((head + 1) & (RB_SIZE - 1));
    if (next != tail) {
      rbRevs[head] = wheelRevolutions;
      rbTime[head] = eventTime;
      __atomic_store_n(&rbHead, next, __ATOMIC_RELEASE);
      uint8_t occupancy = (uint8_t)((next - tail) & (RB_SIZE - 1));
      if (occupancy > rbHighWater) rbHighWater = occupancy;
    } else {
      // Buffer full (only reachable if loop() is starved). The cumulative count keeps
      // advancing, so the next delivered packet still carries correct distance; only
      // this revolution's individual timestamp is lost. Count it for diagnostics.
      droppedRevolutions++;
    }
  }
}

// Builds and sends a CSC measurement packet for wheel (speed) data.
// eventTime is in 1/1024 second units, as required by the CSC spec.
// The Collector derives speed from successive revolution/time pairs and the
// configured wheel circumference: speed = Δrevs * circumference / Δtime.
void notifyCSC(uint32_t revs, uint16_t eventTime) {
  uint8_t data[7];
  data[0] = 0x01;                    // flags: wheel revolution data present
  data[1] = revs & 0xFF;             // cumulative wheel revolutions (UINT32, little-endian)
  data[2] = (revs >> 8) & 0xFF;
  data[3] = (revs >> 16) & 0xFF;
  data[4] = (revs >> 24) & 0xFF;
  data[5] = eventTime & 0xFF;        // last wheel event time (low byte)
  data[6] = (eventTime >> 8) & 0xFF; // last wheel event time (high byte)
  measurementChar->setValue(data, 7);
  measurementChar->notify();
  notificationsSent++;
}

// Human-readable reset cause, so the serial log shows whether the last reboot was a
// normal power-on, a crash/panic, or a watchdog timeout (the key debuggability signal).
const char *resetReasonStr(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON:  return "power-on";
    case ESP_RST_EXT:      return "external";
    case ESP_RST_SW:       return "software";
    case ESP_RST_PANIC:    return "panic/exception";
    case ESP_RST_INT_WDT:  return "interrupt watchdog";
    case ESP_RST_TASK_WDT: return "task watchdog";
    case ESP_RST_WDT:      return "other watchdog";
    case ESP_RST_DEEPSLEEP: return "deep sleep wake";
    case ESP_RST_BROWNOUT: return "brownout";
    default:               return "unknown";
  }
}

// Blinks the onboard LED n times (active low: LOW = on, HIGH = off). Blocking — only
// called from the boot self-test, before the watchdog is armed, so the delays can't trip it.
void blinkLed(uint8_t n, uint16_t onMs, uint16_t offMs) {
  for (uint8_t i = 0; i < n; i++) {
    digitalWrite(LED_BUILTIN, LOW);   // on
    delay(onMs);
    digitalWrite(LED_BUILTIN, HIGH);  // off
    delay(offMs);
  }
}

// Power-on wiring self-test for the hall sensor's three wires (GND, V→3.3V, OUT→D0).
//
// The A3144 is open-collector and active low: with no magnet near, its output transistor is
// OFF and the line is high-impedance, held HIGH only by the MCU's internal pull-up. In that
// idle state a correctly-wired sensor and a *disconnected* OUT pin read identically (both
// HIGH), so the idle level alone cannot prove the wiring.
//
// The definitive check is a magnet pass. Only a sensor that is powered (V + GND correct) AND
// whose OUT actually reaches D0 (signal correct) can sink the line LOW, so a debounced
// HIGH->sustained-LOW transition confirms all three wires at once. We first establish the
// idle-HIGH baseline, then wait up to SELFTEST_WINDOW_MS for such a transition; on success the
// LED flashes three times. A line that NEVER clears to HIGH within the window is a real fault
// (OUT shorted to GND, or a jammed sensor) and is reported as FAIL — but a momentary low at boot
// is NOT treated as a fault, since a wheel parked with the magnet at the sensor holds a correctly
// wired output low until it moves. If no magnet passes in the window the result is inconclusive
// (not a failure) and the sensor boots normally — so a bike-mounted, power-banked unit isn't
// blocked just because nobody waved a magnet at it.
//
// Runs before attachInterrupt()/the watchdog: it polls digitalRead directly, so it neither
// disturbs the revolution count nor races the ISR, and its blocking waits predate the WDT.
// Returns true only when a magnet pass confirmed the wiring.
bool selfTestWiring() {
  if (SELFTEST_WINDOW_MS == 0) return false;  // self-test disabled (setup() already set INPUT_PULLUP)

  // A genuine magnet pass holds OUT low for many ms; a disconnected (floating, antenna-like) OUT
  // can momentarily dip low for a single sample. Require the low to PERSIST this long before we
  // trust it, so noise on an unconnected pin can't fake a pass.
  const uint16_t LOW_CONFIRM_MS = 8;

  // We must observe the idle-HIGH baseline before we trust a low as a real magnet pass. Reading
  // low at boot is NOT proof of a fault: a wheel parked with the magnet next to the sensor holds
  // a correctly-wired output low. So we wait for the line to clear to HIGH first; only a line that
  // stays low for the entire window (a true OUT→GND short, or a jammed sensor) is reported as a
  // fault. This avoids the common false-FAIL of powering up with the magnet parked at the sensor.
  bool sawHigh = (digitalRead(SENSOR_PIN) == HIGH);
  if (sawHigh) {
    emitLogf("[selftest] line idle HIGH; pass a magnet within %ds to confirm wiring...",
             SELFTEST_WINDOW_MS / 1000);
  } else {
    emitLogf("[selftest] signal LOW at boot — waiting for it to clear (magnet parked? else OUT shorted to GND)...");
  }

  unsigned long start = millis();
  while (millis() - start < SELFTEST_WINDOW_MS) {
    int level = digitalRead(SENSOR_PIN);

    if (!sawHigh) {
      // Still establishing the idle baseline. A parked-magnet low clears once the wheel moves; a
      // hard short never does (handled as a FAIL after the window).
      if (level == HIGH) {
        sawHigh = true;
        emitLogf("[selftest] line cleared to idle HIGH; pass a magnet to confirm wiring...");
      }
    } else if (level == LOW) {
      // Candidate magnet pass: confirm the low is SUSTAINED (debounce) so a single noise dip on a
      // floating/disconnected OUT pin can't fake it. Only a powered sensor sinking current holds it.
      bool sustained = true;
      unsigned long lowStart = millis();
      while (millis() - lowStart < LOW_CONFIRM_MS) {
        if (digitalRead(SENSOR_PIN) != LOW) { sustained = false; break; }
        delay(1);
      }
      if (sustained) {
        // A debounced HIGH→sustained-LOW transition: only a sensor that is powered (V + GND) AND
        // whose OUT reaches D0 can produce it, so this confirms all three wires at once.
        emitLogf("[selftest] PASS: magnet detected — GND, V and OUT all wired correctly");
        // Let the line return HIGH before setup() arms the FALLING-edge ISR, so a still-present
        // magnet doesn't cost the first revolution (FALLING needs a HIGH→LOW transition).
        unsigned long clearStart = millis();
        while (digitalRead(SENSOR_PIN) == LOW && millis() - clearStart < 2000) delay(2);
        blinkLed(3, 120, 180);  // 3 flashes = wiring confirmed
        return true;
      }
    }

    // Quick wink every ~250 ms while waiting, so it's visibly distinct from loop()'s steady
    // ~1 Hz advertising blink and the user can tell the unit is in the self-test window.
    digitalWrite(LED_BUILTIN, ((millis() / 125) % 2) ? HIGH : LOW);
    delay(5);
  }

  digitalWrite(LED_BUILTIN, HIGH);  // LED off; loop() takes over the status pattern
  if (!sawHigh) {
    emitLogf("[selftest] FAIL: signal stuck LOW for %ds (OUT shorted to GND, or sensor jammed on)",
             SELFTEST_WINDOW_MS / 1000);
  } else {
    emitLogf("[selftest] inconclusive: no magnet seen in %ds (booting normally)",
             SELFTEST_WINDOW_MS / 1000);
  }
  return false;
}

// Subscribe loop() to the task watchdog so a wedged BLE stack or a hung loop reboots
// the sensor instead of silently going dark. Handles both the already-initialized
// (Arduino default) and uninitialized cases.
void setupWatchdog() {
  esp_task_wdt_config_t cfg = {
    .timeout_ms = WDT_TIMEOUT_S * 1000,
    .idle_core_mask = 0,      // watch loop(), not the idle tasks
    .trigger_panic = true,    // panic + reboot on timeout
  };
  if (esp_task_wdt_reconfigure(&cfg) != ESP_OK) {
    esp_task_wdt_init(&cfg);  // not yet initialized
  }
  esp_task_wdt_add(NULL);     // subscribe the current (loop) task
}

void setup() {
  Serial.begin(115200);

  pinMode(SENSOR_PIN, INPUT_PULLUP);  // A3144 is open-collector, pulls LOW when magnet detected
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);    // off (active low)

  // Boot diagnostics: the reset reason distinguishes a clean start from a crash or a
  // watchdog reboot; the build stamp confirms which firmware is actually flashed.
  bootReason = esp_reset_reason();  // kept for the BLE boot summary sent on client subscribe
  Serial.println();
  Serial.println("=== Bike Speed booting ===");
  Serial.printf("reset reason: %s\n", resetReasonStr(bootReason));
  Serial.printf("build:        %s %s\n", __DATE__, __TIME__);

  // Append the last two MAC bytes so multiple units are distinguishable in a
  // scan list (e.g. "Bike Speed 3F9A") instead of all advertising the same name.
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_BT);
  char deviceName[24];
  snprintf(deviceName, sizeof(deviceName), "%s %02X%02X", DEVICE_NAME, mac[4], mac[5]);

  BLEDevice::init(deviceName);
  BLEServer *server = BLEDevice::createServer();
  server->setCallbacks(new ServerCallbacks());
  BLEService *service = server->createService(CSC_SERVICE_UUID);

  // Measurement characteristic: notifies connected clients on each wheel revolution
  measurementChar = service->createCharacteristic(
    CSC_MEASUREMENT_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  measurementChar->addDescriptor(new BLE2902());

  // Feature characteristic: declares which CSC features this sensor supports
  BLECharacteristic *featureChar = service->createCharacteristic(
    CSC_FEATURE_UUID,
    BLECharacteristic::PROPERTY_READ
  );
  uint16_t feature = 0x0001;  // bit 0: wheel revolution data supported
  featureChar->setValue((uint8_t *)&feature, 2);

  service->start();

  // Device Information Service (0x180A): static strings the app reads once after
  // connecting, so it can show which firmware is flashed and the hardware — no notify
  // and no advertising needed. The firmware revision carries the build date too, so the
  // app surfaces exactly what's on the device without a serial console.
  // (setValue copies the bytes into the characteristic, so these need not outlive
  // setup(); featureChar above relies on the same copy with a plain stack local.
  // static here is purely to keep the larger string literals off the stack.)
  static const char *MANUFACTURER = "round-earth-project";
  static const char *MODEL        = "Bike Speed (XIAO ESP32-C6)";
  static const char *FW_REVISION  = FW_VERSION " (build " __DATE__ ")";
  // These three read-only characteristics need ~7 handles, well under createService's
  // default 15-handle budget. Pass an explicit count here if a 4th+ is ever added,
  // otherwise registration can silently fail at runtime (it still compiles in CI).
  BLEService *infoService = server->createService(BLEUUID((uint16_t)0x180A));
  infoService->createCharacteristic(BLEUUID((uint16_t)0x2A29), BLECharacteristic::PROPERTY_READ)
    ->setValue((uint8_t *)MANUFACTURER, strlen(MANUFACTURER));  // Manufacturer Name
  infoService->createCharacteristic(BLEUUID((uint16_t)0x2A24), BLECharacteristic::PROPERTY_READ)
    ->setValue((uint8_t *)MODEL, strlen(MODEL));                // Model Number
  infoService->createCharacteristic(BLEUUID((uint16_t)0x2A26), BLECharacteristic::PROPERTY_READ)
    ->setValue((uint8_t *)FW_REVISION, strlen(FW_REVISION));    // Firmware Revision
  infoService->start();

  // Nordic UART Service: a single notify-only TX characteristic used to stream debug logs to
  // the app (and generic BLE tools). Its 0x2902 CCCD carries LogCccdCallbacks so the firmware
  // only transmits while a client is subscribed. Not advertised — the app finds it by service
  // discovery on the connection it already opens for CSC. (1 service + 1 char + CCCD ≈ 4
  // handles, well under createService's default 15-handle budget.)
  BLEService *logService = server->createService(NUS_SERVICE_UUID);
  logChar = logService->createCharacteristic(NUS_TX_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  logCccd = new BLE2902();             // kept global; logSubscribed() polls its notifications bit
  logChar->addDescriptor(logCccd);
  logService->start();

  // Advertise using the 16-bit CSC service UUID so cycling apps can discover it
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLEUUID((uint16_t)0x1816));
  advertising->setScanResponse(true);
  advertising->start();

  // Boot wiring self-test before the ISR is attached and the watchdog armed: flashes the
  // LED 3x if a magnet pass proves the hall sensor's GND/V/OUT wiring. Polls the pin directly
  // (leaves wheelRevolutions untouched) and an inconclusive result still boots normally.
  selfTestWiring();

  attachInterrupt(digitalPinToInterrupt(SENSOR_PIN), onMagnet, FALLING);

  // Arm the watchdog last, once BLE is up and the ISR is attached.
  setupWatchdog();

  Serial.printf("advertising as: %s\n", deviceName);
}

void loop() {
  esp_task_wdt_reset();

  // On the rising edge of a client subscribing to logs, send a one-line boot summary so the app
  // sees the last reset cause (e.g. a watchdog reboot) and build stamp immediately, without a
  // serial console. Edge-detected by polling logSubscribed() (which tracks the CCCD bit and the
  // link), so it re-fires for each fresh subscriber after a reconnect.
  static bool prevLogSub = false;
  bool nowLogSub = logSubscribed();
  if (nowLogSub && !prevLogSub) {
    emitLogf("[boot] reset=%s build=%s %s fw=%s", resetReasonStr(bootReason),
             __DATE__, __TIME__, FW_VERSION);
  }
  prevLogSub = nowLogSub;

  // Drain every buffered revolution (one CSC notification each, so a burst is never
  // collapsed into one packet). Read rbHead with acquire to pair with the ISR's release.
  // Notify only when a client is connected; otherwise discard — the cumulative count
  // stays current so a re-connecting client resumes correctly, and the buffer can't
  // overflow while idle.
  uint8_t tail = rbTail;
  while (tail != __atomic_load_n(&rbHead, __ATOMIC_ACQUIRE)) {
    uint32_t revs = rbRevs[tail];
    uint16_t eventTime = rbTime[tail];
    tail = (uint8_t)((tail + 1) & (RB_SIZE - 1));
    __atomic_store_n(&rbTail, tail, __ATOMIC_RELEASE);

    // The tail was advanced (event consumed) BEFORE this connected-check on purpose: if a
    // client disconnects mid-drain, remaining buffered events are dropped, not held. That is
    // intentional and correct — the cumulative count rides in the first packet after
    // reconnect, restoring distance — and must NOT be changed to retain events: a parked but
    // disconnected sensor would overflow the ring buffer with stale-timestamped entries.
    if (deviceConnected) {
      notifyCSC(revs, eventTime);
#if DEBUG_VERBOSE
      Serial.printf("[rev] revs=%lu t=%u\n", (unsigned long)revs, eventTime);
#endif
    }
  }

  // Periodic health line for field diagnostics: uptime, revolution rate, dropped
  // events, ring-buffer high-water mark, notifications sent, link state, and free heap.
  static unsigned long lastHealth = 0;
  static uint32_t lastHealthRevs = 0;
  unsigned long nowMs = millis();
  if (nowMs - lastHealth >= HEALTH_INTERVAL_MS) {
    // These ISR-written counters (wheelRevolutions, droppedRevolutions, disconnectCount,
    // and the uint8_t rbHighWater below) are read here without the acquire/release the
    // ring-buffer head/tail use. That is fine: they are diagnostic-only, and an aligned
    // 32-bit (or single-byte) load is atomic on the ESP32-C6, so the worst case is a value
    // one tick stale in a log line — never a torn read. Do NOT copy this relaxed pattern to
    // the ring buffer, whose head/tail ordering relative to the data writes matters.
    uint32_t revs = wheelRevolutions;
    float dt = (nowMs - lastHealth) / 1000.0f;
    float rate = dt > 0.0f ? (revs - lastHealthRevs) / dt : 0.0f;
    lastHealth = nowMs;
    lastHealthRevs = revs;
    // emitLogf mirrors this to USB serial AND (when subscribed) over BLE; no trailing '\n'
    // in the format — emitLogf adds it for both sinks.
    emitLogf(
      "[health] up=%lus revs=%lu rate=%.1f/s drops=%lu hwm=%u/%u notif=%lu conn=%d disc=%lu heap=%lu",
      nowMs / 1000, (unsigned long)revs, rate, (unsigned long)droppedRevolutions,
      rbHighWater, RB_SIZE - 1, (unsigned long)notificationsSent,
      deviceConnected ? 1 : 0, (unsigned long)disconnectCount, (unsigned long)ESP.getFreeHeap());
  }

  // Status LED (active low): solid = a client is connected, ~1 Hz blink = advertising.
  // Lets you read the link state at a glance without a serial console.
  if (deviceConnected) {
    digitalWrite(LED_BUILTIN, LOW);
  } else {
    digitalWrite(LED_BUILTIN, ((nowMs / 500) % 2) ? LOW : HIGH);
  }

  delay(10);
}
