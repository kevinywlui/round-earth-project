#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <esp_mac.h>

// --- Configuration ---
#define DEVICE_NAME  "Bike Speed"  // a per-device suffix from the MAC is appended at boot
#define SENSOR_PIN   D0    // hall effect sensor OUT pin (XIAO ESP32-C6)
#define MIN_MS       60    // minimum ms between triggers (~16.6 rev/s; ~125 km/h on a 2.1 m wheel)

// Bluetooth CSC (Cycling Speed and Cadence) standard UUIDs
#define CSC_SERVICE_UUID      "00001816-0000-1000-8000-00805f9b34fb"
#define CSC_MEASUREMENT_UUID  "00002a5b-0000-1000-8000-00805f9b34fb"
#define CSC_FEATURE_UUID      "00002a5c-0000-1000-8000-00805f9b34fb"

BLECharacteristic *measurementChar;

// Wheel revolutions are a UINT32 per the CSC spec — they "cannot practically roll
// over during the life of the Sensor," so we accumulate without wrapping.
volatile uint32_t wheelRevolutions = 0;
volatile uint16_t lastEventTime = 0;     // 1/1024 sec units, captured in the ISR
volatile bool pendingNotify = false;     // set by ISR, consumed in loop()
volatile unsigned long lastTrigger = 0;  // ms of last accepted trigger (debounce)

// Restart advertising when a client disconnects so the sensor is rediscoverable
// without a power cycle.
class ServerCallbacks : public BLEServerCallbacks {
  void onDisconnect(BLEServer *server) override { server->startAdvertising(); }
};

// Wheel detection runs off a falling-edge interrupt (A3144 pulls LOW on detect),
// so a fast wheel can't slip between poll samples. BLE work can't happen in an ISR,
// so we just record the event and flag loop() to send the notification.
void IRAM_ATTR onMagnet() {
  unsigned long now = millis();
  if (now - lastTrigger > MIN_MS) {
    wheelRevolutions++;
    lastEventTime = (uint16_t)(((uint64_t)now * 1024) / 1000);  // ms → 1/1024 sec (64-bit to avoid overflow)
    lastTrigger = now;
    pendingNotify = true;
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
}

void setup() {
  Serial.begin(115200);

  pinMode(SENSOR_PIN, INPUT_PULLUP);  // A3144 is open-collector, pulls LOW when magnet detected
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);    // off (active low)

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

  // Advertise using the 16-bit CSC service UUID so cycling apps can discover it
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLEUUID((uint16_t)0x1816));
  advertising->setScanResponse(true);
  advertising->start();

  attachInterrupt(digitalPinToInterrupt(SENSOR_PIN), onMagnet, FALLING);

  Serial.print("advertising as: ");
  Serial.println(deviceName);
}

void loop() {
  if (pendingNotify) {
    pendingNotify = false;
    // Snapshot the ISR-updated values; brief detach guards against a torn read
    // if a new edge fires between the two volatile loads.
    noInterrupts();
    uint32_t revs = wheelRevolutions;
    uint16_t eventTime = lastEventTime;
    interrupts();

    notifyCSC(revs, eventTime);
    Serial.print("wheel revs: ");
    Serial.println(revs);
  }

  // LED mirrors magnet state as a visual heartbeat
  digitalWrite(LED_BUILTIN, digitalRead(SENSOR_PIN) == LOW ? LOW : HIGH);
  delay(10);
}
