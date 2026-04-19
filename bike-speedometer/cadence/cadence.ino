#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// --- Configuration ---
#define DEVICE_NAME  "Bike Cadence"
#define SENSOR_PIN   D0    // hall effect sensor OUT pin (XIAO ESP32-C6)
#define MIN_MS       80    // minimum ms between triggers (~750 RPM max, well above any real cadence)

// Bluetooth CSC (Cycling Speed and Cadence) standard UUIDs
#define CSC_SERVICE_UUID      "00001816-0000-1000-8000-00805f9b34fb"
#define CSC_MEASUREMENT_UUID  "00002a5b-0000-1000-8000-00805f9b34fb"
#define CSC_FEATURE_UUID      "00002a5c-0000-1000-8000-00805f9b34fb"

BLECharacteristic *measurementChar;

uint16_t crankRevolutions = 0;
bool lastMagnet = false;
unsigned long lastTrigger = 0;

// Builds and sends a CSC measurement packet for crank data.
// eventTime is in 1/1024 second units, as required by the CSC spec.
void notifyCSC(uint16_t revs, uint16_t eventTime) {
  uint8_t data[5];
  data[0] = 0x02;                    // flags: crank revolution data present
  data[1] = revs & 0xFF;             // cumulative crank revolutions (low byte)
  data[2] = (revs >> 8) & 0xFF;      // cumulative crank revolutions (high byte)
  data[3] = eventTime & 0xFF;        // last crank event time (low byte)
  data[4] = (eventTime >> 8) & 0xFF; // last crank event time (high byte)
  measurementChar->setValue(data, 5);
  measurementChar->notify();
}

void setup() {
  Serial.begin(115200);

  pinMode(SENSOR_PIN, INPUT_PULLUP);  // A3144 is open-collector, pulls LOW when magnet detected
  pinMode(LED_BUILTIN, OUTPUT);
  digitalWrite(LED_BUILTIN, HIGH);    // off (active low)

  BLEDevice::init(DEVICE_NAME);
  BLEServer *server = BLEDevice::createServer();
  BLEService *service = server->createService(CSC_SERVICE_UUID);

  // Measurement characteristic: notifies connected clients on each crank revolution
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
  uint16_t feature = 0x0002;  // bit 1: crank revolution data supported
  featureChar->setValue((uint8_t *)&feature, 2);

  service->start();

  // Advertise using the 16-bit CSC service UUID so cycling apps can discover it
  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(BLEUUID((uint16_t)0x1816));
  advertising->setScanResponse(true);
  advertising->start();

  Serial.println("advertising as: " DEVICE_NAME);
}

void loop() {
  bool magnet = digitalRead(SENSOR_PIN) == LOW;

  if (magnet && !lastMagnet) {
    unsigned long now = millis();
    if (now - lastTrigger > MIN_MS) {
      crankRevolutions++;
      uint16_t eventTime = (uint16_t)((now * 1024UL) / 1000UL);  // ms → 1/1024 sec
      notifyCSC(crankRevolutions, eventTime);
      Serial.print("crank revs: ");
      Serial.println(crankRevolutions);
      lastTrigger = now;
    }
  }

  // LED mirrors magnet state as a visual heartbeat
  digitalWrite(LED_BUILTIN, magnet ? LOW : HIGH);
  lastMagnet = magnet;
  delay(10);
}
