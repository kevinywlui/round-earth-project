# Bike Speedometer

An ESP32-based bike speedometer that broadcasts speed and cadence over Bluetooth Low Energy using the official **Cycling Speed and Cadence (CSC) Profile 1.0.1** specification. Compatible with any CSC-capable app or head unit (e.g., Garmin, Wahoo, Strava).

## How It Works

A hall effect sensor is mounted on the bike frame and a magnet is attached to the wheel (and optionally the crank). Each time the magnet passes the sensor, the ESP32 records a timestamp and cumulative revolution count. This data is packaged into the CSC Measurement characteristic and broadcast over BLE.

## Parts

| Part | Notes |
|------|-------|
| ESP32 | Any variant with BLE support |
| Hall effect sensor | e.g., A3144 or SS49E |
| Magnets | Small neodymium magnets work well |
| Resistor (10kΩ) | Pull-up for sensor signal line |

## Wiring

```
Hall Effect Sensor
  VCC  → 3.3V
  GND  → GND
  OUT  → GPIO (configurable in code) + 10kΩ pull-up to 3.3V
```

## Bluetooth Specification

This firmware implements the [Cycling Speed and Cadence Profile 1.0.1](https://www.bluetooth.com/specifications/specs/cycling-speed-and-cadence-profile-1-0-1/).

- **Service UUID:** `0x1816` (Cycling Speed and Cadence)
- **Measurement Characteristic:** `0x2A5B` — notifies connected clients with wheel/crank revolution counts and event timestamps
- **Feature Characteristic:** `0x2A5C` — advertises which features are supported (wheel revolutions, crank revolutions)

## Setup

1. Install the [Arduino IDE](https://www.arduino.cc/en/software) or PlatformIO.
2. Install the ESP32 board package.
3. Open the project and set your wheel circumference in the config.
4. Flash to your ESP32.
5. Pair with your preferred cycling app.

## Configuration

| Parameter | Description |
|-----------|-------------|
| `WHEEL_CIRCUMFERENCE_MM` | Circumference of your wheel in millimeters |
| `SENSOR_PIN` | GPIO pin connected to the hall effect sensor |
| `DEVICE_NAME` | BLE device name advertised to clients |
