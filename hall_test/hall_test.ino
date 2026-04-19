#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <SPI.h>

const int hallPin = 5; // Updated to Pin 5
const int ledPin = 12;

#define TFT_CS         7
#define TFT_DC         39
#define TFT_RST        40
#define TFT_BACKLIGHT  45

Adafruit_ST7789 tft = Adafruit_ST7789(TFT_CS, TFT_DC, TFT_RST);

void setup() {
  Serial.begin(115200);
  
  pinMode(hallPin, INPUT_PULLUP);
  pinMode(ledPin, OUTPUT);
  
  pinMode(TFT_BACKLIGHT, OUTPUT);
  digitalWrite(TFT_BACKLIGHT, HIGH); 
  
  tft.init(135, 240);
  tft.setRotation(3);
  tft.fillScreen(ST77XX_BLACK);
  
  tft.setCursor(0, 0);
  tft.setTextColor(ST77XX_WHITE);
  tft.setTextSize(3);
  tft.println("Hall Test");
  
  Serial.println("Hall Effect (Pin 5) & TFT Initialized.");
}

bool lastState = -1; // Force first update

void loop() {
  int sensorValue = digitalRead(hallPin);
  bool magnetExists = (sensorValue == LOW);
  
  if (magnetExists != lastState) {
    tft.fillRect(0, 40, 240, 60, ST77XX_BLACK);
    tft.setCursor(0, 50);
    
    if (magnetExists) {
      tft.setTextColor(ST77XX_GREEN);
      tft.println("MAGNET: YES");
      digitalWrite(ledPin, HIGH);
      Serial.println("Magnet: Detected");
    } else {
      tft.setTextColor(ST77XX_RED);
      tft.println("MAGNET: NO");
      digitalWrite(ledPin, LOW);
      Serial.println("Magnet: None");
    }
    lastState = magnetExists;
  }
  
  delay(100);
}
