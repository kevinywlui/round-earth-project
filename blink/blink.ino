#include "MorseBlinker.h"

const int externalLed = 12;
const int tftBacklight = 45; // Keep the screen glowing

MorseBlinker morse(externalLed, 200); // 200ms unit delay for faster Morse

void setup() {
  Serial.begin(115200);
  
  pinMode(tftBacklight, OUTPUT);
  digitalWrite(tftBacklight, HIGH);
  
  morse.begin();
  Serial.println("Feather ESP32-S2 TFT: Blinking 'Hello World' on Pin 12...");
}

void loop() {
  Serial.println("Starting 'Hello World'...");
  morse.blinkMessage("Hello World");
  delay(5000); // 5-second pause between repetitions
}
