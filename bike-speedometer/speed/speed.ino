#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLE2902.h>
#include <esp_mac.h>
#include <esp_system.h>    // esp_reset_reason() for boot diagnostics
#include <esp_task_wdt.h>  // task watchdog (requires ESP32 Arduino core 3.x / IDF 5)
#include <esp_attr.h>      // RTC_NOINIT_ATTR — retained-RAM marker for the power-loss boot diagnostic
#include <esp_timer.h>     // esp_timer_get_time() — IRAM-resident clock, safe to call from the ISR
#include <Preferences.h>   // NVS-backed key/value store for the per-minute backlog ring (survives power loss)
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

// Backlog service (custom, NOT a SIG profile) — lets the app recover wheel revolutions that
// happened while it was disconnected, including across a sensor reboot/power-loss. Every minute
// the firmware appends (boot_id, record_index, uptime_s, cumulative_revs) to an NVS ring in flash
// (so it survives power removal); on connect the app reads the Info characteristic (boot_id +
// current uptime = its clock anchor + the ring's index range) and subscribes to the Data
// characteristic, which streams the whole ring as 16-byte records then a terminator. Un-advertised
// (only CSC is advertised), so a generic CSC head unit never sees it — graceful interop. No
// app→device writes: the app pulls by subscribing, exactly like the NUS boot-summary replay.
// NOTE: review+compile validated only, NOT yet hardware-tested — see the per-function caveats.
#define BACKLOG_SERVICE_UUID  "5245424C-0000-1000-8000-00805f9b34fb"  // "REBL" in the first bytes
#define BACKLOG_INFO_UUID     "52454201-0000-1000-8000-00805f9b34fb"  // READ:   20-byte info block
#define BACKLOG_DATA_UUID     "52454202-0000-1000-8000-00805f9b34fb"  // NOTIFY: 16-byte records + terminator

#define LOG_INTERVAL_MS    60000   // one backlog record per minute (per-minute resolution is enough)
#define LOG_RING_SIZE      180     // bounded ring: ~3 h of riding-minutes (only minutes that advanced
                                   // the count are written). On overflow the oldest record is
                                   // overwritten and backlogOverflow is incremented so the loss is
                                   // observable (surfaced in the Info block + health line), never silent.
#define LOG_BOOTID_STABLE_S 5      // commit the next boot_id only after the unit survives this long,
                                   // so a brownout/power-bank reboot loop doesn't burn NVS wear (or
                                   // churn boot_ids) on a supply that's already failing.
#define LOG_RECORD_BYTES   16      // (u32 boot_id, u32 record_index, u32 uptime_s, u32 cumulative_revs)

BLECharacteristic *measurementChar;
BLECharacteristic *logChar = nullptr;        // NUS TX (debug log notifications), null until setup()
BLE2902 *logCccd = nullptr;                  // logChar's 0x2902 CCCD; getNotifications() = subscribed?
esp_reset_reason_t bootReason = ESP_RST_UNKNOWN;  // captured in setup() for the boot summary line

// Power-loss boot diagnostic. RTC_NOINIT_ATTR lives in the always-on RTC RAM domain: it is NOT
// re-initialized at startup, so it survives a software/watchdog reset (and a shallow brownout that
// stays above the RTC retention floor) but is lost (the VERY thing we test for) when VDD is fully
// removed — an unplug, a power bank that auto-shuts off under the sensor's low draw, or a deep
// brownout that collapses VDD toward 0. So on boot, a matching magic marker means "RTC RAM was
// retained → NOT a full power loss"; a mismatch means power was actually cut. Combined with the
// reset reason this separates the three "shuts off after a few seconds" causes: power-bank
// cutoff (reason power-on, RTC cleared), brownout (reason brownout), watchdog (reason task wdt).
#define RTC_BOOT_MAGIC 0xB1CE5EEDu        // arbitrary sentinel; any fixed non-trivial value
RTC_NOINIT_ATTR uint32_t rtcBootMagic;    // == RTC_BOOT_MAGIC iff RTC RAM survived the last reset
RTC_NOINIT_ATTR uint32_t rtcPrevUptimeS;  // seconds the previous run survived (valid iff magic matched)
bool     rtcRetained = false;             // set in setup(): true = RTC RAM survived (no full power loss)
uint32_t prevUptimeS = 0;                 // previous run's survived seconds, when rtcRetained

// Outcome of the power-on wiring self-test (selfTestWiring()), captured so loop() can replay it
// over BLE when a client subscribes — the test runs in setup() before any client connects, so its
// serial lines would otherwise never reach the app (the only log a field unit has).
enum WiringResult { WIRING_UNTESTED = 0, WIRING_PASS, WIRING_INCONCLUSIVE, WIRING_FAIL_STUCK_LOW, WIRING_DISABLED };
WiringResult wiringResult = WIRING_UNTESTED;

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
volatile uint32_t debouncedRejects = 0;   // edges rejected by the MIN_MS debounce — instrumentation so
                                          // the health line reveals if a genuinely fast wheel is ever
                                          // being undercounted (vs. real contact bounce).

// --- Per-minute backlog ring (NVS-backed; see BACKLOG_SERVICE_UUID) ---
Preferences   backlogPrefs;               // NVS namespace "backlog"; holds boot_id + the ring slots
BLECharacteristic *backlogInfoChar = nullptr;  // READ: 20-byte info block (boot_id, uptime, range, overflow)
BLECharacteristic *backlogDataChar = nullptr;  // NOTIFY: streams 16-byte records on subscribe
BLE2902 *backlogDataCccd = nullptr;       // Data char CCCD; polled to edge-detect a subscriber (like NUS)
uint32_t backlogBootId   = 0;             // this power-on's id (NVS monotonic; survives full power loss)
bool     backlogBootIdCommitted = false;  // true once the NEXT boot_id has been persisted (after stable uptime)
uint32_t backlogHead     = 0;             // total records ever written = next record_index. Persisted in
                                          // NVS so it is GLOBALLY monotonic across reboots; slot = head %
                                          // LOG_RING_SIZE. (mac, record_index) is therefore unique even if
                                          // a sub-stable boot reuses a boot_id — boot_id is kept only to
                                          // detect the cumulative_revs reset a reboot causes.
uint32_t backlogOverflow = 0;             // records overwritten before the app drained them (observability)
uint32_t backlogNvsErr   = 0;             // failed NVS writes — a non-zero value means the persisted
                                          // cursor may lag (data-loss risk); surfaced on the health line
uint32_t lastLoggedRevs  = 0;             // cumulative_revs of the last record — only log when it advances
// The ring lives in RAM and is persisted as ONE NVS blob (not per-slot keys): far less NVS entry/GC
// churn, it fits the stock 20 KB `nvs` partition, and replay streams from RAM with no flash reads.
uint8_t  backlogRing[LOG_RING_SIZE * LOG_RECORD_BYTES];

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

// Refreshes the backlog Info block on every READ so the app's clock anchor (current uptime) and the
// ring's index range are current at the moment it reads, not stale from boot.
class BacklogInfoCallbacks : public BLECharacteristicCallbacks {
  void onRead(BLECharacteristic *c) override { backlogFillInfo(); }
};

// Wheel detection runs off a falling-edge interrupt (A3144 pulls LOW on detect),
// so a fast wheel can't slip between poll samples. BLE work can't happen in an ISR,
// so we record the event in the ring buffer and let loop() send the notification.
void IRAM_ATTR onMagnet() {
  // millis() lives in flash and is therefore UNSAFE to call from an IRAM ISR while a flash write
  // is in progress (the per-minute backlog commit disables the cache, unmapping flash code — an
  // edge landing then would fault on this single-core part). esp_timer_get_time() is IRAM-resident,
  // so the ISR stays safe to fire during a backlog write. It returns microseconds since boot as a
  // 64-bit value, which also sidesteps the 49.7-day millis() wrap. (HARDWARE-VALIDATION ITEM.)
  unsigned long now = (unsigned long)(esp_timer_get_time() / 1000);  // ms since boot
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
  } else {
    // Edge inside the debounce window. Usually contact bounce (correctly suppressed), but on a
    // genuinely fast wheel it could be a real revolution being undercounted; count it so the
    // health line can reveal that case instead of it being silent. Aligned 32-bit, no sync needed.
    debouncedRejects++;
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

// Little-endian u32 store, matching the CSC packet packing and the app's readUInt32 decoder so the
// app can reuse the same little-endian readers for backlog records.
static inline void putLE32(uint8_t *p, uint32_t v) {
  p[0] = v & 0xFF; p[1] = (v >> 8) & 0xFF; p[2] = (v >> 16) & 0xFF; p[3] = (v >> 24) & 0xFF;
}

// Opens the NVS-backed backlog store and loads the persisted cursor. boot_id is the value reserved
// for THIS boot by the previous stable run (default 1 on a virgin device); it is NOT advanced here
// — loop() commits boot_id+1 only after LOG_BOOTID_STABLE_S of uptime so a brownout loop can't burn
// NVS wear. backlogHead is the global, reboot-surviving record counter. Empty ring slots are marked
// by a 0 boot_id (boot_id is never 0 for a real record), so a virgin ring streams nothing.
void backlogInit() {
  backlogPrefs.begin("backlog", false);          // read/write namespace
  backlogBootId = backlogPrefs.getUInt("bootId", 1);
  backlogHead   = backlogPrefs.getUInt("head", 0);
  memset(backlogRing, 0, sizeof(backlogRing));
  backlogPrefs.getBytes("ring", backlogRing, sizeof(backlogRing));  // restore prior records (no-op if absent)
  lastLoggedRevs = 0;
  backlogOverflow = (backlogHead > LOG_RING_SIZE) ? (backlogHead - LOG_RING_SIZE) : 0;
}

// Appends one (boot_id, record_index, uptime_s, cumulative_revs) record to the NVS ring, flushed
// immediately so a brownout can lose at most the in-progress minute (never an already-written one).
// Called ONLY from loop() (never the ISR) and ONLY when the count advanced. The caller straddles
// this with esp_task_wdt_reset() because an NVS page-compaction erase can take longer than usual.
// HARDWARE-VALIDATION ITEM: NVS commit latency under BLE load, and ring sizing vs. the NVS partition.
void backlogWrite(uint32_t revs, uint32_t uptimeS) {
  uint32_t slot = backlogHead % LOG_RING_SIZE;
  uint8_t *rec = backlogRing + slot * LOG_RECORD_BYTES;
  putLE32(rec + 0,  backlogBootId);
  putLE32(rec + 4,  backlogHead);                // record_index = global head (unique forever)
  putLE32(rec + 8,  uptimeS);
  putLE32(rec + 12, revs);

  // Persist the whole ring, then the advanced cursor. Advance the persisted head ONLY if BOTH writes
  // succeed: otherwise a full/failed NVS would let the RAM head outrun the stored head and, after a
  // reboot, reuse a record_index for a different record — which the app silently dedups away (its key
  // is (mac, record_index)). Counting failures surfaces that data-loss risk instead of hiding it.
  bool ok = backlogPrefs.putBytes("ring", backlogRing, sizeof(backlogRing)) == sizeof(backlogRing) &&
            backlogPrefs.putUInt("head", backlogHead + 1) == sizeof(uint32_t);
  if (ok) {
    backlogHead++;
    backlogOverflow = (backlogHead > LOG_RING_SIZE) ? (backlogHead - LOG_RING_SIZE) : 0;
    lastLoggedRevs = revs;
  } else {
    backlogNvsErr++;  // leave head unadvanced; next minute overwrites this slot, indices stay consistent
  }
}

// Fills the READ Info block the app pulls once on connect: its clock anchor (boot_id + current
// uptime, so it can back-compute each record's wall-clock) plus the ring's index range and the
// overflow count (so it can mark a gap rather than assume continuity). 20 bytes, all LE u32.
void backlogFillInfo() {
  if (backlogInfoChar == nullptr) return;
  uint32_t oldest = (backlogHead > LOG_RING_SIZE) ? (backlogHead - LOG_RING_SIZE) : 0;
  uint8_t info[20];
  putLE32(info + 0,  backlogBootId);
  putLE32(info + 4,  (uint32_t)(esp_timer_get_time() / 1000000));  // current uptime, seconds
  putLE32(info + 8,  oldest);             // smallest record_index still in the ring
  putLE32(info + 12, backlogHead);        // one past the newest record_index
  putLE32(info + 16, backlogOverflow);    // records overwritten before being drained
  backlogInfoChar->setValue(info, sizeof(info));
}

// Streams every still-present ring record over the Data NOTIFY characteristic (one 16-byte record
// per notification — fits the 23-byte default ATT MTU, no fragmentation), oldest first, then a
// terminator (record_index = 0xFFFFFFFF) so the app knows the drain completed. Called from loop()
// on the rising edge of a Data-char subscription (mirrors the NUS boot-summary-on-subscribe). The
// app dedups by (mac, record_index) with INSERT OR IGNORE, so re-streaming the whole ring on every
// reconnect is harmless — which is why no app→device cursor/ack write is needed.
void backlogStreamAll() {
  if (backlogDataChar == nullptr || !deviceConnected) return;
  uint32_t oldest = (backlogHead > LOG_RING_SIZE) ? (backlogHead - LOG_RING_SIZE) : 0;
  for (uint32_t idx = oldest; idx < backlogHead; idx++) {
    uint8_t *rec = backlogRing + (idx % LOG_RING_SIZE) * LOG_RECORD_BYTES;  // from RAM, no flash reads
    if (rec[0] == 0 && rec[1] == 0 && rec[2] == 0 && rec[3] == 0) continue;  // empty slot (boot_id 0)
    backlogDataChar->setValue(rec, LOG_RECORD_BYTES);
    backlogDataChar->notify();
    if (!deviceConnected) return;                 // client vanished mid-stream — stop touching a dead link
    esp_task_wdt_reset();                          // a long ring shouldn't starve the watchdog
  }
  uint8_t term[LOG_RECORD_BYTES] = {0};
  putLE32(term + 4, 0xFFFFFFFF);                   // terminator: record_index = 0xFFFFFFFF
  backlogDataChar->setValue(term, LOG_RECORD_BYTES);
  backlogDataChar->notify();
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

// A remedy hint for the reset reasons that signal a fault, so the boot log points at the likely
// fix without the reader consulting a table. Empty string for benign reasons (power-on, etc.).
const char *resetReasonHint(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_BROWNOUT: return "supply sagged — check the cable/power bank and that the U.FL antenna is attached";
    case ESP_RST_TASK_WDT:
    case ESP_RST_INT_WDT:
    case ESP_RST_WDT:      return "loop() stalled past WDT_TIMEOUT_S — a wedged BLE stack?";
    case ESP_RST_PANIC:    return "firmware crash — see the panic backtrace on serial";
    default:               return "";
  }
}

// Human-readable wiring self-test outcome for the BLE [boot] summary, so the app shows whether
// the hall sensor is correctly wired (the serial lines selfTestWiring() prints are invisible to
// a field unit with no serial console).
const char *wiringResultStr(WiringResult r) {
  switch (r) {
    case WIRING_PASS:           return "PASS — magnet seen: GND, V and OUT all wired correctly";
    case WIRING_INCONCLUSIVE:   return "inconclusive — no magnet waved at boot (wave one within the window and reboot to confirm)";
    case WIRING_FAIL_STUCK_LOW: return "FAIL — signal stuck LOW (OUT shorted to GND, or sensor jammed on)";
    case WIRING_DISABLED:       return "skipped (SELFTEST_WINDOW_MS=0)";
    default:                    return "untested";
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
  if (SELFTEST_WINDOW_MS == 0) {              // self-test disabled (setup() already set INPUT_PULLUP)
    wiringResult = WIRING_DISABLED;
    return false;
  }

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
        wiringResult = WIRING_PASS;
        // Wait for the line to return HIGH before continuing. (The ISR is now armed before this
        // self-test, so this no longer protects the "first revolution"; it just avoids the confirming
        // magnet wave registering as extra edges while it's still held against the sensor.)
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
    wiringResult = WIRING_FAIL_STUCK_LOW;
    emitLogf("[selftest] FAIL: signal stuck LOW for %ds (OUT shorted to GND, or sensor jammed on)",
             SELFTEST_WINDOW_MS / 1000);
  } else {
    wiringResult = WIRING_INCONCLUSIVE;
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

  // Read the retained-RAM marker BEFORE we rewrite it: if it survived, this was not a full power
  // loss (so a "shuts off" complaint is a brownout/watchdog, not a power-bank cutoff/unplug);
  // if it was cleared, VDD was actually removed. Then re-arm the marker and reset the uptime
  // store for this run (loop() keeps rtcPrevUptimeS current so the NEXT boot can report it).
  //
  // Trust the marker only when the reset reason is consistent with the chip having stayed powered:
  // a power-on reset means VDD WAS cycled by definition, so distrust a "retained"-looking marker
  // then — that kills both the ~1-in-2^32 first-cold-boot magic match and a fast re-plug that left
  // the RTC caps charged (both report reason power-on). Unknown reset reason is distrusted too.
  rtcRetained = (rtcBootMagic == RTC_BOOT_MAGIC) &&
                bootReason != ESP_RST_POWERON && bootReason != ESP_RST_UNKNOWN;
  prevUptimeS = rtcRetained ? rtcPrevUptimeS : 0;
  rtcBootMagic = RTC_BOOT_MAGIC;
  rtcPrevUptimeS = 0;

  const char *hint = resetReasonHint(bootReason);
  Serial.println();
  Serial.println("=== Bike Speed booting ===");
  Serial.printf("reset reason: %s%s%s\n", resetReasonStr(bootReason), hint[0] ? "  <- " : "", hint);
  if (rtcRetained) {
    Serial.printf("prev run:     survived %lus before this reset (RTC RAM retained — not a full power loss)\n",
                  (unsigned long)prevUptimeS);
  } else {
    Serial.println("prev run:     RTC RAM cleared — VDD was fully removed (unplug / power-bank auto-shutoff), or a deep brownout");
  }
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

  // Backlog service: lets the app recover revolutions logged while disconnected (across reboots).
  // Info (READ) carries the clock anchor + ring range, refreshed per-read; Data (NOTIFY) streams the
  // ring on subscribe. Not advertised — discovered by service discovery like NUS. (1 service + 2
  // chars + 1 CCCD ≈ 6 handles, under the 15-handle default.) backlogInit() must precede any read.
  backlogInit();
  BLEService *backlogService = server->createService(BACKLOG_SERVICE_UUID);
  backlogInfoChar = backlogService->createCharacteristic(BACKLOG_INFO_UUID, BLECharacteristic::PROPERTY_READ);
  backlogInfoChar->setCallbacks(new BacklogInfoCallbacks());
  backlogFillInfo();                   // seed an initial value (refreshed on each read)
  backlogDataChar = backlogService->createCharacteristic(BACKLOG_DATA_UUID, BLECharacteristic::PROPERTY_NOTIFY);
  backlogDataCccd = new BLE2902();     // polled in loop() to edge-detect a subscriber, like logCccd
  backlogDataChar->addDescriptor(backlogDataCccd);
  backlogService->start();

  // Advertise using the 16-bit CSC service UUID so cycling apps can discover it
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLEUUID((uint16_t)0x1816));
  advertising->setScanResponse(true);
  advertising->start();

  // Attach the wheel ISR BEFORE the (up-to-8 s) wiring self-test so revolutions during the boot
  // window are counted: a unit that reboots mid-ride (brownout / power-bank glitch) would otherwise
  // silently drop up to ~8 s of distance that the backlog can never recover. The self-test polls the
  // pin directly so it still works; the only effect is its confirming magnet wave adds a rev or two.
  attachInterrupt(digitalPinToInterrupt(SENSOR_PIN), onMagnet, FALLING);

  // Boot wiring self-test: flashes the LED 3x if a magnet pass proves the hall sensor's GND/V/OUT
  // wiring. Inconclusive (no magnet waved) still boots normally.
  selfTestWiring();

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
    // Mirror the power diagnostic over BLE — for a power-bank/brownout death the app is the only
    // place this is visible, since the unit isn't on a serial console out in the field.
    const char *hint = resetReasonHint(bootReason);
    if (hint[0]) emitLogf("[boot] hint: %s", hint);
    if (rtcRetained) {
      emitLogf("[boot] prev run survived %lus before this reset (not a full power loss)",
               (unsigned long)prevUptimeS);
    } else {
      emitLogf("[boot] power was fully removed since last run (unplug / power-bank cutoff, or a deep brownout)");
    }
    // Replay the power-on wiring self-test verdict: it ran before any client could subscribe, so
    // this is the only way the app learns whether the hall sensor is correctly wired.
    emitLogf("[selftest] wiring %s", wiringResultStr(wiringResult));
  }
  prevLogSub = nowLogSub;

  // On the rising edge of a client subscribing to the backlog Data characteristic, stream the whole
  // NVS ring (then a terminator). Mirrors the NUS boot-summary replay: edge-detected by polling the
  // CCCD bit, so it re-fires for each fresh subscriber after a reconnect. The app dedups records by
  // (mac, record_index) with INSERT OR IGNORE, so re-streaming the ring every reconnect is harmless.
  static bool prevBacklogSub = false;
  bool nowBacklogSub = deviceConnected && backlogDataCccd != nullptr && backlogDataCccd->getNotifications();
  if (nowBacklogSub && !prevBacklogSub) backlogStreamAll();
  prevBacklogSub = nowBacklogSub;

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

  unsigned long nowMs = millis();

  // Keep the retained-RAM uptime current so the NEXT boot can report how long this run survived
  // before a reset (a cheap RTC-RAM write; see the rtcBootMagic diagnostic above).
  rtcPrevUptimeS = nowMs / 1000;

  // Commit the NEXT boot_id once this run has proven stable, so a brownout/power-bank reboot loop
  // (which dies before this) reuses the same boot_id and can't burn NVS wear on a failing supply.
  if (!backlogBootIdCommitted && nowMs >= (unsigned long)LOG_BOOTID_STABLE_S * 1000) {
    backlogPrefs.putUInt("bootId", backlogBootId + 1);
    backlogBootIdCommitted = true;
  }

  // Per-minute backlog: once a minute, IF the wheel count advanced since the last record, append one
  // record to the NVS ring. Only-when-advanced skips idle/parked minutes (no wear, no noise). Straddle
  // the flash write with watchdog feeds since an NVS page compaction can run long. From loop(), never
  // the ISR. uptime is taken from esp_timer (64-bit, wrap-free) to match the Info block's anchor.
  static unsigned long lastLog = 0;
  if (nowMs - lastLog >= LOG_INTERVAL_MS) {
    lastLog = nowMs;
    uint32_t revs = wheelRevolutions;            // single aligned load; may advance after, never torn
    if (revs > lastLoggedRevs) {                 // only log minutes that actually moved
      esp_task_wdt_reset();
      backlogWrite(revs, (uint32_t)(esp_timer_get_time() / 1000000));
      esp_task_wdt_reset();
    }
  }

  // Early boot heartbeat: until the first HEALTH_INTERVAL_MS health line, tick uptime once per
  // second so a unit that dies within "a few seconds" (a power-bank auto-shutoff or brownout)
  // still leaves a trail of how long it ran — the first full health line only appears at 5 s.
  static unsigned long lastTick = 0;
  if (nowMs < HEALTH_INTERVAL_MS && nowMs - lastTick >= 1000) {
    lastTick = nowMs;
    emitLogf("[alive] up=%lus conn=%d heap=%lu", nowMs / 1000, deviceConnected ? 1 : 0,
             (unsigned long)ESP.getFreeHeap());
  }

  // Periodic health line for field diagnostics: uptime, revolution rate, dropped
  // events, ring-buffer high-water mark, notifications sent, link state, and free heap.
  static unsigned long lastHealth = 0;
  static uint32_t lastHealthRevs = 0;
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
      "[health] up=%lus revs=%lu rate=%.1f/s drops=%lu drej=%lu hwm=%u/%u notif=%lu conn=%d disc=%lu "
      "boot=%lu log=%lu ovf=%lu nvserr=%lu heap=%lu",
      nowMs / 1000, (unsigned long)revs, rate, (unsigned long)droppedRevolutions,
      (unsigned long)debouncedRejects, rbHighWater, RB_SIZE - 1, (unsigned long)notificationsSent,
      deviceConnected ? 1 : 0, (unsigned long)disconnectCount, (unsigned long)backlogBootId,
      (unsigned long)backlogHead, (unsigned long)backlogOverflow, (unsigned long)backlogNvsErr,
      (unsigned long)ESP.getFreeHeap());
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
