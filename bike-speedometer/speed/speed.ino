#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <esp_mac.h>
#include <esp_system.h>    // esp_reset_reason() for boot diagnostics
#include <esp_task_wdt.h>  // task watchdog (requires ESP32 Arduino core 3.x / IDF 5)

// --- Configuration ---
#define DEVICE_NAME  "Bike Speed"  // a per-device suffix from the MAC is appended at boot
#define FW_VERSION   "1.0"         // reported over the BLE Device Information Service (0x180A)
#define SENSOR_PIN   D0    // hall effect sensor OUT pin (XIAO ESP32-C6)
#define MIN_MS       60    // minimum ms between triggers (~16.6 rev/s; ~125 km/h on a 2.1 m wheel)
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

BLECharacteristic *measurementChar;

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

// Track connection state (so we only transmit when a client is listening) and
// restart advertising on disconnect so the sensor is rediscoverable without a
// power cycle.
class ServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer *server) override {
    deviceConnected = true;
    Serial.println("[event] client connected");
  }
  void onDisconnect(BLEServer *server) override {
    deviceConnected = false;
    disconnectCount++;
    Serial.println("[event] client disconnected; re-advertising");
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
  Serial.println();
  Serial.println("=== Bike Speed booting ===");
  Serial.printf("reset reason: %s\n", resetReasonStr(esp_reset_reason()));
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

  // Advertise using the 16-bit CSC service UUID so cycling apps can discover it
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLEUUID((uint16_t)0x1816));
  advertising->setScanResponse(true);
  advertising->start();

  attachInterrupt(digitalPinToInterrupt(SENSOR_PIN), onMagnet, FALLING);

  // Arm the watchdog last, once BLE is up and the ISR is attached.
  setupWatchdog();

  Serial.printf("advertising as: %s\n", deviceName);
}

void loop() {
  esp_task_wdt_reset();

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
    Serial.printf(
      "[health] up=%lus revs=%lu rate=%.1f/s drops=%lu hwm=%u/%u notif=%lu conn=%d disc=%lu heap=%lu\n",
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
